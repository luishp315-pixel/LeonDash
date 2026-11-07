package com.luis.leondash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Conexión con un lector OBD2 Bluetooth tipo ELM327 (vLinker, OBDLink, etc.)
 * y cálculo de los datos en tiempo real y del viaje actual.
 */
object ObdManager {
    private const val TAG = "LeonDash"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Gasolina: relación estequiométrica y densidad
    private const val AFR = 14.7
    private const val FUEL_DENSITY_G_L = 745.0

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile var state = State.DISCONNECTED; private set
    @Volatile var status = "Sin conectar"; private set
    @Volatile var supportedPids: Set<Int> = emptySet(); private set

    /** Valores actuales por id de GaugeDef. */
    val values = ConcurrentHashMap<String, Double>()

    // Viaje actual
    @Volatile var tripStart = 0L; private set
    @Volatile var tripKm = 0.0; private set
    @Volatile var tripLiters = 0.0; private set
    @Volatile var tripMaxSpeed = 0.0; private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyListeners() = listeners.forEach { try { it() } catch (e: Exception) { Log.w(TAG, e) } }

    private var worker: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopRequested = false
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        if (!::appCtx.isInitialized) {
            appCtx = ctx.applicationContext
            supportedPids = Store.loadSupportedPids(appCtx)
        }
    }

    fun connect(ctx: Context) {
        init(ctx)
        if (state != State.DISCONNECTED) return
        val mac = Store.settings(appCtx).deviceMac
        if (mac == null) {
            setStatus("Elige el lector OBD en la app del móvil"); return
        }
        stopRequested = false
        state = State.CONNECTING
        setStatus("Conectando…")
        worker = Thread({ run(mac) }, "obd").also { it.start() }
    }

    fun disconnect() {
        stopRequested = true
        worker?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun setStatus(s: String) { status = s; notifyListeners() }

    @SuppressLint("MissingPermission")
    private fun run(mac: String) {
        var reconnectDelay = 3000L
        var failures = 0
        while (!stopRequested) {
            try {
                val bm = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter: BluetoothAdapter = bm.adapter ?: throw Exception("Este móvil no tiene Bluetooth")
                if (!adapter.isEnabled) throw Exception("Activa el Bluetooth del móvil")
                val dev = adapter.getRemoteDevice(mac)
                adapter.cancelDiscovery()
                val s = try {
                    dev.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                } catch (e: Exception) {
                    // Algunos clónicos solo aceptan el socket "inseguro"
                    dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                }
                socket = s
                state = State.CONNECTING
                setStatus("Iniciando lector…")
                val elm = Elm(s.inputStream, s.outputStream)
                initElm(elm)
                state = State.CONNECTED
                reconnectDelay = 3000L
                failures = 0
                setStatus("Conectado")
                startTrip()
                pollLoop(elm)
            } catch (e: Exception) {
                Log.w(TAG, "OBD: ${e.message}")
                if (!stopRequested) setStatus("Sin conexión: ${e.message ?: "error"}. Reintentando…")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                endTrip()
                values.clear()
                if (state != State.DISCONNECTED) { state = State.CONNECTING }
            }
            if (stopRequested) break
            // Coche apagado o lector desenchufado: deja de intentarlo tras ~10 minutos
            if (++failures >= 20) break
            try { Thread.sleep(reconnectDelay) } catch (_: InterruptedException) { break }
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
        }
        state = State.DISCONNECTED
        setStatus("Desconectado")
    }

    private fun initElm(elm: Elm) {
        elm.cmd("ATZ", 2500)
        elm.cmd("ATE0"); elm.cmd("ATL0"); elm.cmd("ATS0"); elm.cmd("ATH0")
        elm.cmd("ATAT1")
        elm.cmd("ATSP0")
        // Primera petición: el lector busca el protocolo (CAN en el León)
        val r = elm.cmd("0100", 8000)
        if (!r.contains("4100")) throw Exception("el coche no responde (¿contacto puesto?)")
        val pids = HashSet<Int>()
        var base = 0x00
        var resp: String? = r
        while (resp != null && base <= 0xA0) {
            val bytes = parse(resp, base) ?: break
            if (bytes.size < 4) break
            val bits = (bytes[0].toLong() shl 24) or (bytes[1].toLong() shl 16) or (bytes[2].toLong() shl 8) or bytes[3].toLong()
            for (i in 0 until 32) if (bits and (1L shl (31 - i)) != 0L) pids.add(base + i + 1)
            if ((base + 0x20) !in pids) break
            base += 0x20
            resp = elm.cmd(String.format("01%02X", base))
        }
        pids.add(0x01) // estado de averías: siempre existe
        supportedPids = pids
        Store.saveSupportedPids(appCtx, pids)
    }

    /** Devuelve los bytes de datos de la respuesta "41 PID ...". */
    private fun parse(resp: String, pid: Int): IntArray? {
        val key = String.format("41%02X", pid)
        for (line in resp.split('\r', '\n')) {
            val l = line.replace(" ", "").uppercase()
            val idx = l.indexOf(key)
            if (idx < 0) continue
            val hex = l.substring(idx + 4)
            val n = hex.length / 2
            if (n == 0) continue
            val out = IntArray(n)
            for (i in 0 until n) out[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            return out
        }
        return null
    }

    private val FAST = listOf(0x0C, 0x0D, 0x10, 0x5E, 0x0B, 0x11, 0x04)
    private val SLOW = listOf(0x05, 0x0F, 0x5C, 0x46, 0x42, 0x2F, 0x0E, 0x23, 0x3C, 0x06, 0x07, 0x33, 0x1F, 0x01, 0x31)

    private fun pollLoop(elm: Elm) {
        var lastT = System.currentTimeMillis()
        var cycle = 0
        val slow = SLOW.filter { it in supportedPids }
        var slowIdx = 0
        while (!stopRequested) {
            for (pid in FAST) if (pid in supportedPids) read(elm, pid)
            // Un dato lento por vuelta, para no ralentizar los rápidos
            if (slow.isNotEmpty()) { read(elm, slow[slowIdx % slow.size]); slowIdx++ }
            if (cycle == 0) for (pid in slow) read(elm, pid) // primera vuelta: todos
            cycle++

            val now = System.currentTimeMillis()
            val dtH = (now - lastT) / 3_600_000.0
            lastT = now
            computeDerived(dtH)
            notifyListeners()
        }
    }

    private fun read(elm: Elm, pid: Int) {
        val r = elm.cmd(String.format("01%02X", pid))
        if (r.contains("UNABLE") || r.contains("ERROR") || r.contains("STOPPED")) {
            if (r.contains("UNABLE")) throw Exception("se perdió la comunicación con el coche")
            return
        }
        val d = parse(r, pid) ?: return
        fun a() = d[0].toDouble()
        fun ab() = (d[0] * 256 + d.getOrElse(1) { 0 }).toDouble()
        when (pid) {
            0x01 -> values["dtc"] = (d[0] and 0x7F).toDouble()
            0x04 -> values["load"] = a() * 100 / 255
            0x05 -> values["coolant"] = a() - 40
            0x06 -> values["stft"] = (a() - 128) * 100 / 128
            0x07 -> values["ltft"] = (a() - 128) * 100 / 128
            0x0B -> values["map"] = a()
            0x0C -> values["rpm"] = ab() / 4
            0x0D -> values["speed"] = a()
            0x0E -> values["timing"] = a() / 2 - 64
            0x0F -> values["intake"] = a() - 40
            0x10 -> values["maf"] = ab() / 100
            0x11 -> values["throttle"] = a() * 100 / 255
            0x1F -> values["runtime"] = ab() / 60
            0x23 -> values["rail_press"] = ab() * 10 / 100 // kPa -> bar
            0x2F -> values["fuel_level"] = a() * 100 / 255
            0x31 -> values["dist_dtc"] = ab()
            0x33 -> values["baro"] = a()
            0x3C -> values["cat_temp"] = ab() / 10 - 40
            0x42 -> values["voltage"] = ab() / 1000
            0x46 -> values["ambient"] = a() - 40
            0x5C -> values["oil_temp"] = a() - 40
            0x5E -> values["fuel_rate_raw"] = ab() / 20
        }
    }

    private fun computeDerived(dtH: Double) {
        val speed = values["speed"] ?: 0.0
        // Turbo: presión de admisión menos atmosférica
        values["map"]?.let { map -> values["boost"] = (map - (values["baro"] ?: 101.3)) / 100.0 }
        // Gasto en l/h: dato directo si existe, si no a partir del caudal de aire
        val lh = values["fuel_rate_raw"] ?: values["maf"]?.let { it * 3600.0 / (AFR * FUEL_DENSITY_G_L) }
        if (lh != null) {
            values["fuel_rate"] = lh
            values["cons_inst"] = if (speed >= 5) lh / speed * 100.0 else lh
        }
        // Viaje (ignora huecos raros > 10 s)
        if (dtH > 0 && dtH < 10.0 / 3600) {
            tripKm += speed * dtH
            if (lh != null) tripLiters += lh * dtH
        }
        if (speed > tripMaxSpeed) tripMaxSpeed = speed
        values["trip_km"] = tripKm
        values["trip_l"] = tripLiters
        if (tripKm > 0.3) values["cons_trip"] = tripLiters / tripKm * 100.0
        val mins = (System.currentTimeMillis() - tripStart) / 60000.0
        if (mins > 0.5) values["trip_vavg"] = tripKm / (mins / 60.0)
        // Autonomía con el consumo medio del viaje o, si no, del historial
        values["fuel_level"]?.let { lvl ->
            val avg = values["cons_trip"] ?: historicAvg
            if (avg != null && avg > 1) values["range"] = (lvl / 100.0 * Gauges.TANK_LITERS) / avg * 100.0
        }
    }

    @Volatile private var historicAvg: Double? = null

    private fun startTrip() {
        tripStart = System.currentTimeMillis(); tripKm = 0.0; tripLiters = 0.0; tripMaxSpeed = 0.0
        val trips = Store.trips(appCtx).take(30)
        val km = trips.sumOf { it.km }
        historicAvg = if (km > 20) trips.sumOf { it.liters } / km * 100.0 else null
    }

    private fun endTrip() {
        if (tripStart > 0 && tripKm > 0.3) {
            Store.addTrip(appCtx, Trip(tripStart, System.currentTimeMillis(), tripKm, tripLiters, tripMaxSpeed))
        }
        tripStart = 0; tripKm = 0.0; tripLiters = 0.0; tripMaxSpeed = 0.0
    }

    /** Diálogo básico con el ELM327: envía comando y lee hasta el prompt '>'. */
    private class Elm(private val inp: InputStream, private val out: OutputStream) {
        fun cmd(c: String, timeoutMs: Long = 1500): String {
            while (inp.available() > 0) inp.read() // limpia restos
            out.write((c + "\r").toByteArray()); out.flush()
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val ch = inp.read()
                    if (ch < 0) throw Exception("conexión cerrada")
                    if (ch.toChar() == '>') return sb.toString().replace("SEARCHING...", "").trim()
                    sb.append(ch.toChar())
                } else {
                    Thread.sleep(5)
                }
            }
            if (sb.isEmpty()) throw Exception("el lector no responde")
            return sb.toString()
        }
    }
}
