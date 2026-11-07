package com.luis.leondash

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Settings(
    val deviceMac: String?,
    val deviceName: String?,
    val autoHide: Boolean,
    val hidden: Set<String>,
    val autoConnect: Boolean
)

data class Trip(
    val start: Long,
    val end: Long,
    val km: Double,
    val liters: Double,
    val maxSpeed: Double
) {
    val avgL100: Double get() = if (km > 0.1) liters / km * 100.0 else Double.NaN
    val minutes: Double get() = (end - start) / 60000.0
    val avgSpeed: Double get() = if (minutes > 0.1) km / (minutes / 60.0) else Double.NaN
}

data class ServiceType(val id: String, val name: String, val defKm: Int, val defMonths: Int)

data class ServiceEntry(val typeId: String, val date: Long, val km: Int, val note: String)

/** Estado de una revisión: cuándo toca la siguiente. */
data class ServiceStatus(
    val type: ServiceType,
    val last: ServiceEntry?,
    val nextKm: Int?,
    val nextDate: Long?,
    val kmLeft: Int?,
    val daysLeft: Int?
) {
    val overdue: Boolean get() = (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0)
    val soon: Boolean get() = !overdue && ((kmLeft != null && kmLeft < 1500) || (daysLeft != null && daysLeft < 30))
}

object Store {
    private const val PREFS = "leondash"

    // Intervalos orientativos para el 1.4 TSI (EA211). Editables en la app:
    // compruébalos con el libro de mantenimiento de tu coche.
    val SERVICE_TYPES = listOf(
        ServiceType("oil", "Aceite y filtro de aceite", 15000, 12),
        ServiceType("air", "Filtro de aire", 30000, 24),
        ServiceType("cabin", "Filtro de habitáculo (polen)", 15000, 12),
        ServiceType("plugs", "Bujías", 60000, 48),
        ServiceType("brake_fluid", "Líquido de frenos", 0, 24),
        ServiceType("brakes", "Pastillas / discos de freno", 30000, 0),
        ServiceType("tyres", "Neumáticos", 40000, 60),
        ServiceType("coolant", "Líquido refrigerante", 0, 48),
        ServiceType("itv", "ITV", 0, 24),
        ServiceType("other", "Otro", 0, 0)
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Ajustes ----------
    fun settings(ctx: Context): Settings {
        val p = prefs(ctx)
        return Settings(
            deviceMac = p.getString("mac", null),
            deviceName = p.getString("macName", null),
            autoHide = p.getBoolean("autoHide", true),
            hidden = p.getStringSet("hidden", emptySet())?.toSet() ?: emptySet(),
            autoConnect = p.getBoolean("autoConnect", true)
        )
    }

    fun setDevice(ctx: Context, mac: String, name: String) {
        prefs(ctx).edit().putString("mac", mac).putString("macName", name).apply()
    }

    fun setAutoHide(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("autoHide", v).apply()
    fun setAutoConnect(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("autoConnect", v).apply()

    fun setHidden(ctx: Context, id: String, hidden: Boolean) {
        val cur = settings(ctx).hidden.toMutableSet()
        if (hidden) cur.add(id) else cur.remove(id)
        prefs(ctx).edit().putStringSet("hidden", cur).apply()
    }

    /** PIDs que el coche dijo soportar la última vez (para ocultar datos aunque no esté conectado). */
    fun saveSupportedPids(ctx: Context, pids: Set<Int>) {
        prefs(ctx).edit().putString("pids", pids.joinToString(",")).apply()
    }

    fun loadSupportedPids(ctx: Context): Set<Int> =
        prefs(ctx).getString("pids", "")!!.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    // ---------- Odómetro (introducido a mano + km de viajes) ----------
    fun odometer(ctx: Context): Double = prefs(ctx).getFloat("odo", 0f).toDouble()
    fun setOdometer(ctx: Context, km: Double) = prefs(ctx).edit().putFloat("odo", km.toFloat()).apply()

    // ---------- Viajes ----------
    fun trips(ctx: Context): List<Trip> {
        val arr = JSONArray(prefs(ctx).getString("trips", "[]"))
        val out = ArrayList<Trip>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Trip(o.getLong("s"), o.getLong("e"), o.getDouble("km"), o.getDouble("l"), o.optDouble("vmax", 0.0)))
        }
        return out.sortedByDescending { it.start }
    }

    fun addTrip(ctx: Context, t: Trip) {
        val list = (listOf(t) + trips(ctx)).take(500)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("s", it.start).put("e", it.end).put("km", it.km).put("l", it.liters).put("vmax", it.maxSpeed))
        }
        val odo = odometer(ctx)
        prefs(ctx).edit().putString("trips", arr.toString()).apply()
        if (odo > 0) setOdometer(ctx, odo + t.km)
    }

    fun clearTrips(ctx: Context) = prefs(ctx).edit().putString("trips", "[]").apply()

    // ---------- Revisiones ----------
    fun intervalKm(ctx: Context, t: ServiceType) = prefs(ctx).getInt("ikm_" + t.id, t.defKm)
    fun intervalMonths(ctx: Context, t: ServiceType) = prefs(ctx).getInt("imo_" + t.id, t.defMonths)
    fun setInterval(ctx: Context, t: ServiceType, km: Int, months: Int) {
        prefs(ctx).edit().putInt("ikm_" + t.id, km).putInt("imo_" + t.id, months).apply()
    }

    fun services(ctx: Context): List<ServiceEntry> {
        val arr = JSONArray(prefs(ctx).getString("services", "[]"))
        val out = ArrayList<ServiceEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(ServiceEntry(o.getString("t"), o.getLong("d"), o.getInt("km"), o.optString("n", "")))
        }
        return out.sortedByDescending { it.date }
    }

    private fun saveServices(ctx: Context, list: List<ServiceEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.typeId).put("d", it.date).put("km", it.km).put("n", it.note)) }
        prefs(ctx).edit().putString("services", arr.toString()).apply()
    }

    fun addService(ctx: Context, e: ServiceEntry) {
        saveServices(ctx, services(ctx) + e)
        if (e.km > odometer(ctx)) setOdometer(ctx, e.km.toDouble())
    }

    fun removeService(ctx: Context, e: ServiceEntry) = saveServices(ctx, services(ctx).filterNot { it == e })

    fun serviceStatus(ctx: Context): List<ServiceStatus> {
        val all = services(ctx)
        val odo = odometer(ctx).toInt()
        val now = System.currentTimeMillis()
        return SERVICE_TYPES.filter { it.id != "other" }.map { t ->
            val last = all.filter { it.typeId == t.id }.maxByOrNull { it.date }
            val ikm = intervalKm(ctx, t)
            val imo = intervalMonths(ctx, t)
            var nextKm: Int? = null
            var nextDate: Long? = null
            if (last != null) {
                if (ikm > 0) nextKm = last.km + ikm
                if (imo > 0) {
                    val c = Calendar.getInstance(); c.timeInMillis = last.date; c.add(Calendar.MONTH, imo)
                    nextDate = c.timeInMillis
                }
            }
            ServiceStatus(
                t, last, nextKm, nextDate,
                if (nextKm != null && odo > 0) nextKm - odo else null,
                nextDate?.let { ((it - now) / 86_400_000L).toInt() }
            )
        }
    }

    // ---------- Utilidades ----------
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES"))
    private val dateTimeFmt = SimpleDateFormat("dd/MM HH:mm", Locale("es", "ES"))
    fun fmtDate(t: Long): String = dateFmt.format(Date(t))
    fun fmtDateTime(t: Long): String = dateTimeFmt.format(Date(t))
    fun parseDate(s: String): Long? = try { dateFmt.parse(s.trim())?.time } catch (e: Exception) { null }
    fun f1(v: Double): String = if (v.isNaN()) "--" else String.format(Locale("es", "ES"), "%.1f", v)

    fun statusText(s: ServiceStatus): String {
        if (s.last == null) return "Sin registrar"
        val parts = ArrayList<String>()
        if (s.kmLeft != null) parts.add(if (s.kmLeft < 0) "pasada ${-s.kmLeft} km" else "faltan ${s.kmLeft} km")
        else if (s.nextKm != null) parts.add("a los ${s.nextKm} km")
        if (s.daysLeft != null) parts.add(if (s.daysLeft < 0) "vencida hace ${-s.daysLeft} días" else "antes del ${fmtDate(s.nextDate!!)}")
        return if (parts.isEmpty()) "Hecho el ${fmtDate(s.last.date)}" else parts.joinToString(" · ")
    }
}
