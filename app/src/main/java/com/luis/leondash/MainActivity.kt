package com.luis.leondash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged

/** App del móvil: conexión, ajustes de relojes, historial de consumo, revisiones y ficha del coche. */
class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var connectBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var tab = 0
    private var liveText: TextView? = null

    private val bg = Color.rgb(12, 14, 18)
    private val card = Color.rgb(24, 28, 36)
    private val red = Color.rgb(232, 64, 46)
    private val dim = Color.rgb(160, 168, 180)

    private val refresher = object : Runnable {
        override fun run() {
            updateHeader()
            liveText?.text = liveValues()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdManager.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // Cabecera con estado y botón
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        statusView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        header.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        connectBtn = Button(this).apply { setOnClickListener { toggleConnect() } }
        header.addView(connectBtn)
        root.addView(header)

        // Pestañas
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        listOf("En vivo", "Relojes", "Consumos", "Revisiones", "Mi coche").forEachIndexed { i, name ->
            tabs.addView(Button(this).apply {
                text = name; isAllCaps = false
                setOnClickListener { tab = i; render() }
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tabs) })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        ensurePermission()
        render()
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    // ---------------- utilidades de UI ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = content.addView(TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(6))
    })

    private fun note(t: String) = content.addView(TextView(this).apply {
        text = t; setTextColor(dim); textSize = 13f; setPadding(0, dp(2), 0, dp(6))
    })

    private fun cardText(t: String, color: Int = Color.WHITE): TextView {
        val tv = TextView(this).apply {
            text = t; setTextColor(color); textSize = 15f; setBackgroundColor(card); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return tv
    }

    private fun button(t: String, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    })

    private fun field(hint: String, value: String, numeric: Boolean): EditText = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(Color.WHITE); setHintTextColor(dim)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    }

    private fun updateHeader() {
        val s = Store.settings(this)
        statusView.text = "${ObdManager.status}\nLector: ${s.deviceName ?: "sin elegir"}"
        connectBtn.text = if (ObdManager.state == ObdManager.State.DISCONNECTED) "Conectar" else "Desconectar"
    }

    private fun render() {
        content.removeAllViews()
        liveText = null
        when (tab) {
            0 -> renderLive()
            1 -> renderGauges()
            2 -> renderTrips()
            3 -> renderService()
            4 -> renderCar()
        }
        updateHeader()
    }

    // ---------------- conexión ----------------
    private fun ensurePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return false
        }
        return true
    }

    private fun toggleConnect() {
        if (ObdManager.state != ObdManager.State.DISCONNECTED) { ObdManager.disconnect(); return }
        if (!ensurePermission()) return
        if (Store.settings(this).deviceMac == null) { chooseDevice(); return }
        ObdManager.connect(this)
    }

    @SuppressLint("MissingPermission")
    private fun chooseDevice() {
        if (!ensurePermission()) return
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val devs = bm.adapter?.bondedDevices?.toList().orEmpty()
        if (devs.isEmpty()) {
            Toast.makeText(this, "Primero empareja el lector OBD en Ajustes > Bluetooth del móvil", Toast.LENGTH_LONG).show(); return
        }
        val names = devs.map { (it.name ?: "Sin nombre") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Elige el lector OBD")
            .setItems(names) { _, i ->
                Store.setDevice(this, devs[i].address, devs[i].name ?: devs[i].address)
                render()
            }.show()
    }

    // ---------------- pestaña: en vivo ----------------
    private fun liveValues(): String {
        val gauges = Gauges.visible(Store.settings(this), ObdManager.supportedPids)
        val v = ObdManager.values
        return gauges.joinToString("\n") { g -> "${g.label}: ${Gauges.format(g, v[g.id])} ${g.unit}" }
    }

    private fun renderLive() {
        title("Datos en tiempo real")
        note("Con el contacto puesto y el lector enchufado. En Android Auto verás lo mismo en forma de relojes.")
        liveText = cardText(liveValues())
        button("Elegir lector OBD") { chooseDevice() }
        content.addView(CheckBox(this).apply {
            text = "Conectar automáticamente al abrir en Android Auto"; setTextColor(Color.WHITE)
            isChecked = Store.settings(this@MainActivity).autoConnect
            setOnCheckedChangeListener { _, c -> Store.setAutoConnect(this@MainActivity, c) }
        })
    }

    // ---------------- pestaña: relojes ----------------
    private fun renderGauges() {
        title("Qué datos mostrar")
        val s = Store.settings(this)
        val sup = ObdManager.supportedPids
        content.addView(CheckBox(this).apply {
            text = "Ocultar automáticamente los datos que mi coche no envía"; setTextColor(Color.WHITE)
            isChecked = s.autoHide
            setOnCheckedChangeListener { _, c -> Store.setAutoHide(this@MainActivity, c); render() }
        })
        note(if (sup.isEmpty()) "Conecta una vez con el coche para saber qué datos envía."
             else "Tu coche envía ${sup.size} tipos de datos OBD2.")
        Gauges.ALL.forEach { g ->
            val available = Gauges.isAvailable(g, sup)
            val extra = when {
                g.pids.isEmpty() -> " (no disponible en OBD2)"
                !available -> " (tu coche no lo envía)"
                else -> ""
            }
            content.addView(CheckBox(this).apply {
                text = g.label + extra
                setTextColor(if (available) Color.WHITE else dim)
                isChecked = g.id !in s.hidden
                setOnCheckedChangeListener { _, c -> Store.setHidden(this@MainActivity, g.id, !c) }
            })
            if (g.note.isNotEmpty()) note("   " + g.note)
        }
    }

    // ---------------- pestaña: consumos ----------------
    private fun renderTrips() {
        val trips = Store.trips(this)
        title("Viaje actual")
        if (ObdManager.state == ObdManager.State.CONNECTED) {
            val km = ObdManager.tripKm; val l = ObdManager.tripLiters
            cardText("${Store.f1(km)} km · ${Store.f1(l)} l · ${Store.f1(if (km > 0.3) l / km * 100 else Double.NaN)} l/100 km")
        } else note("Sin viaje en curso.")

        title("Resumen")
        fun summary(label: String, days: Int?) {
            val list = if (days == null) trips else trips.filter { it.start > System.currentTimeMillis() - days * 86_400_000L }
            val km = list.sumOf { it.km }; val l = list.sumOf { it.liters }
            cardText("$label: ${Store.f1(km)} km · ${Store.f1(l)} l · media ${Store.f1(if (km > 0.1) l / km * 100 else Double.NaN)} l/100 km")
        }
        summary("7 días", 7); summary("30 días", 30); summary("1 año", 365); summary("Total", null)

        // Por meses
        title("Por meses")
        val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale("es", "ES"))
        trips.groupBy { fmt.format(java.util.Date(it.start)) }.entries.take(12).forEach { (m, list) ->
            val km = list.sumOf { it.km }; val l = list.sumOf { it.liters }
            cardText("$m: ${Store.f1(km)} km · ${Store.f1(if (km > 0.1) l / km * 100 else Double.NaN)} l/100 km · ${list.size} viajes")
        }

        title("Últimos viajes")
        if (trips.isEmpty()) note("Los viajes se guardan solos al desconectar el lector (al apagar el coche).")
        trips.take(50).forEach { t ->
            cardText("${Store.fmtDateTime(t.start)} · ${t.minutes.toInt()} min\n" +
                "${Store.f1(t.km)} km · ${Store.f1(t.liters)} l · ${Store.f1(t.avgL100)} l/100 · media ${Store.f1(t.avgSpeed)} km/h · máx ${t.maxSpeed.toInt()} km/h")
        }
        note("El consumo se calcula con el caudal de aire del motor. Compáralo con el del cuadro tras algunos depósitos: suele desviarse un 5-10 %.")
        button("Borrar historial") {
            AlertDialog.Builder(this).setMessage("¿Borrar todos los viajes guardados?")
                .setPositiveButton("Borrar") { _, _ -> Store.clearTrips(this); render() }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    // ---------------- pestaña: revisiones ----------------
    private fun renderService() {
        title("Kilómetros del coche")
        note("El OBD2 no da los km del cuadro: ponlos aquí de vez en cuando. La app suma solos los km de cada viaje.")
        val odo = field("Km actuales", Store.odometer(this).toInt().takeIf { it > 0 }?.toString() ?: "", true)
        content.addView(odo)
        button("Guardar km") {
            odo.text.toString().toDoubleOrNull()?.let { Store.setOdometer(this, it); render() }
        }

        title("Próximas revisiones")
        Store.serviceStatus(this).forEach { s ->
            val color = when { s.overdue -> red; s.soon -> Color.rgb(255, 170, 0); else -> Color.WHITE }
            cardText("${s.type.name}\n${Store.statusText(s)}", color)
        }

        title("Apuntar revisión hecha")
        val types = Store.SERVICE_TYPES
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types.map { it.name })
        }
        content.addView(spinner)
        val date = field("Fecha (dd/mm/aaaa)", Store.fmtDate(System.currentTimeMillis()), false)
        val km = field("Km", Store.odometer(this).toInt().takeIf { it > 0 }?.toString() ?: "", true)
        val noteF = field("Nota (taller, precio, aceite usado…)", "", false)
        content.addView(date); content.addView(km); content.addView(noteF)
        button("Guardar revisión") {
            val d = Store.parseDate(date.text.toString())
            val k = km.text.toString().toIntOrNull()
            if (d == null || k == null) {
                Toast.makeText(this, "Revisa la fecha y los km", Toast.LENGTH_SHORT).show()
            } else {
                Store.addService(this, ServiceEntry(types[spinner.selectedItemPosition].id, d, k, noteF.text.toString()))
                render()
            }
        }

        title("Historial de revisiones")
        val entries = Store.services(this)
        if (entries.isEmpty()) note("Aún no hay revisiones apuntadas.")
        entries.forEach { e ->
            val name = types.firstOrNull { it.id == e.typeId }?.name ?: e.typeId
            val tv = cardText("${Store.fmtDate(e.date)} · ${e.km} km\n$name" + (if (e.note.isNotBlank()) "\n${e.note}" else ""))
            tv.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("¿Borrar esta revisión?")
                    .setPositiveButton("Borrar") { _, _ -> Store.removeService(this, e); render() }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
        if (entries.isNotEmpty()) note("Mantén pulsada una revisión para borrarla.")

        title("Intervalos")
        note("Valores orientativos para tu motor. Ajústalos a tu libro de mantenimiento (0 = no aplica).")
        types.filter { it.id != "other" }.forEach { t ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = t.name; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1.4f))
            val k = field("km", Store.intervalKm(this, t).toString(), true)
            val m = field("meses", Store.intervalMonths(this, t).toString(), true)
            row.addView(k, LinearLayout.LayoutParams(0, -2, 0.8f))
            row.addView(TextView(this).apply { text = "km"; setTextColor(dim) })
            row.addView(m, LinearLayout.LayoutParams(0, -2, 0.6f))
            row.addView(TextView(this).apply { text = "m"; setTextColor(dim) })
            val save = { Store.setInterval(this, t, k.text.toString().toIntOrNull() ?: 0, m.text.toString().toIntOrNull() ?: 0) }
            k.doAfterTextChanged { save() }; m.doAfterTextChanged { save() }
            content.addView(row)
        }
    }

    // ---------------- pestaña: mi coche ----------------
    private fun renderCar() {
        title("SEAT León FR 2018")
        cardText(
            "Motor: 1.4 TSI ACT, gasolina turbo, 1.395 cc, 4 cilindros\n" +
            "Potencia: 150 CV (110 kW) a 5.000 rpm\n" +
            "Par: 250 Nm desde 1.500 rpm\n" +
            "0-100 km/h: 8,0 s · Velocidad máx.: 215 km/h\n" +
            "Consumo homologado (NEDC): unos 4,7-4,9 l/100 km\n" +
            "Tracción delantera · Depósito: 50 l\n" +
            "Medidas: 4,28 × 1,82 × 1,44 m\n" +
            "Suspensión deportiva FR (sin DCC adaptativa)"
        )
        title("ACT (desconexión de cilindros)")
        note("Apaga los cilindros 2 y 3 con carga baja (aprox. 1.400-4.000 rpm, pie suave). " +
            "Su estado no se puede leer por OBD2 estándar; necesitaría los datos propios de VW.")
        title("Mantenimiento (orientativo)")
        cardText(
            "Aceite: 0W-30 / 5W-30, norma VW 504.00 o 502.00\n" +
            "Capacidad aprox. 4 l con filtro\n" +
            "Comprueba siempre el libro de mantenimiento o pregunta en el taller."
        )
        title("Lo que NO da el OBD2 estándar")
        note("• Presión de aceite: el motor solo tiene interruptor de aviso.\n" +
            "• Km del cuadro, estado del ACT, temperatura del aceite (a veces), datos de la caja DSG.\n" +
            "Para eso harían falta comandos específicos de VW (UDS). Se pueden añadir más adelante.")
    }
}
