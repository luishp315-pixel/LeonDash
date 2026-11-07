package com.luis.leondash

/**
 * Definición de todos los datos que la app sabe mostrar.
 * - pids: PIDs OBD2 (modo 01) de los que depende. Si el coche no envía alguno,
 *   el dato se considera "no disponible" y se puede ocultar automáticamente.
 * - pids vacío + manual = dato que NO existe en OBD2 estándar (ej. presión de aceite).
 */
data class GaugeDef(
    val id: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val pids: List<Int>,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
    val anyPid: Boolean = false,      // true = basta con que exista uno de los PIDs
    val derivedFromTrip: Boolean = false,
    val note: String = ""
)

object Gauges {
    // Datos fijos del León FR 1.4 TSI ACT 150 CV (2018)
    const val TANK_LITERS = 50.0
    const val REDLINE_RPM = 6000.0
    const val MAX_RPM = 7000.0

    val ALL: List<GaugeDef> = listOf(
        GaugeDef("speed", "Velocidad", "km/h", 0.0, 240.0, 0, listOf(0x0D)),
        GaugeDef("rpm", "Revoluciones", "rpm", 0.0, MAX_RPM, 0, listOf(0x0C), warnAbove = REDLINE_RPM),
        GaugeDef("cons_inst", "Consumo instantáneo", "l/100", 0.0, 30.0, 1, listOf(0x10, 0x5E), anyPid = true,
            note = "En parado muestra litros/hora"),
        GaugeDef("cons_trip", "Consumo medio viaje", "l/100", 0.0, 15.0, 1, listOf(0x10, 0x5E), anyPid = true, derivedFromTrip = true),
        GaugeDef("fuel_rate", "Gasto de combustible", "l/h", 0.0, 25.0, 2, listOf(0x10, 0x5E), anyPid = true),
        GaugeDef("boost", "Presión turbo", "bar", -1.0, 1.5, 2, listOf(0x0B), warnAbove = 1.3),
        GaugeDef("coolant", "Temp. refrigerante", "°C", 40.0, 130.0, 0, listOf(0x05), warnAbove = 110.0),
        GaugeDef("oil_temp", "Temp. aceite", "°C", 40.0, 150.0, 0, listOf(0x5C), warnAbove = 130.0),
        GaugeDef("oil_press", "Presión aceite", "bar", 0.0, 6.0, 1, emptyList(), warnBelow = 0.8,
            note = "Tu motor solo tiene interruptor de presión; no hay valor en OBD2 estándar"),
        GaugeDef("intake", "Temp. admisión", "°C", -20.0, 80.0, 0, listOf(0x0F), warnAbove = 60.0),
        GaugeDef("ambient", "Temp. exterior", "°C", -20.0, 50.0, 0, listOf(0x46)),
        GaugeDef("load", "Carga motor", "%", 0.0, 100.0, 0, listOf(0x04)),
        GaugeDef("throttle", "Acelerador", "%", 0.0, 100.0, 0, listOf(0x11)),
        GaugeDef("maf", "Caudal de aire", "g/s", 0.0, 150.0, 1, listOf(0x10)),
        GaugeDef("timing", "Avance encendido", "°", -20.0, 50.0, 1, listOf(0x0E)),
        GaugeDef("voltage", "Batería", "V", 10.0, 15.5, 1, listOf(0x42), warnBelow = 11.8, warnAbove = 15.0),
        GaugeDef("fuel_level", "Nivel depósito", "%", 0.0, 100.0, 0, listOf(0x2F), warnBelow = 12.0),
        GaugeDef("range", "Autonomía", "km", 0.0, 1000.0, 0, listOf(0x2F), derivedFromTrip = true),
        GaugeDef("rail_press", "Presión inyección", "bar", 0.0, 250.0, 0, listOf(0x23)),
        GaugeDef("cat_temp", "Temp. catalizador", "°C", 0.0, 1000.0, 0, listOf(0x3C), warnAbove = 900.0),
        GaugeDef("stft", "Corrección corto plazo", "%", -25.0, 25.0, 1, listOf(0x06)),
        GaugeDef("ltft", "Corrección largo plazo", "%", -25.0, 25.0, 1, listOf(0x07), warnAbove = 10.0, warnBelow = -10.0),
        GaugeDef("baro", "Presión atmosférica", "kPa", 60.0, 110.0, 0, listOf(0x33)),
        GaugeDef("runtime", "Tiempo motor encendido", "min", 0.0, 600.0, 0, listOf(0x1F)),
        GaugeDef("trip_km", "Km del viaje", "km", 0.0, 1000.0, 1, listOf(0x0D), derivedFromTrip = true),
        GaugeDef("trip_l", "Litros del viaje", "l", 0.0, 60.0, 2, listOf(0x10, 0x5E), anyPid = true, derivedFromTrip = true),
        GaugeDef("trip_vavg", "Velocidad media viaje", "km/h", 0.0, 200.0, 0, listOf(0x0D), derivedFromTrip = true),
        GaugeDef("dtc", "Averías guardadas", "", 0.0, 20.0, 0, listOf(0x01), warnAbove = 0.5),
        GaugeDef("dist_dtc", "Km desde borrado averías", "km", 0.0, 65535.0, 0, listOf(0x31))
    )

    val BY_ID: Map<String, GaugeDef> = ALL.associateBy { it.id }

    /** ¿El coche envía lo necesario para este dato? */
    fun isAvailable(def: GaugeDef, supportedPids: Set<Int>): Boolean {
        if (def.pids.isEmpty()) return false
        // Si aún no sabemos qué soporta el coche (no conectado), lo damos por disponible
        if (supportedPids.isEmpty()) return true
        return if (def.anyPid) def.pids.any { it in supportedPids } else def.pids.all { it in supportedPids }
    }

    /** Lista de datos a mostrar según ajustes y lo que envía el coche. */
    fun visible(settings: Settings, supportedPids: Set<Int>): List<GaugeDef> =
        ALL.filter { def ->
            def.id !in settings.hidden &&
                (!settings.autoHide || isAvailable(def, supportedPids))
        }

    fun format(def: GaugeDef, v: Double?): String {
        if (v == null || v.isNaN()) return "--"
        return if (def.decimals == 0) Math.round(v).toString()
        else String.format(java.util.Locale("es", "ES"), "%.${def.decimals}f", v)
    }
}
