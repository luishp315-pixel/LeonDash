package com.luis.leondash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja las páginas de relojes en la pantalla del coche.
 * Página 0: velocidad + revoluciones grandes y datos clave al lado.
 * Páginas 1..n: relojes pequeños (6 por página) con el resto de datos.
 * Última página: tabla con todos los datos en texto.
 */
class GaugeRenderer {
    private val bg = Color.rgb(12, 14, 18)
    private val track = Color.rgb(48, 54, 66)
    private val accent = Color.rgb(232, 64, 46)   // rojo SEAT FR
    private val ok = Color.rgb(64, 196, 255)
    private val warn = Color.rgb(255, 170, 0)
    private val dim = Color.rgb(150, 158, 170)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    data class PageInfo(val count: Int)

    fun pageCount(gauges: List<GaugeDef>): Int {
        val rest = gauges.filter { it.id != "speed" && it.id != "rpm" }
        val smallPages = (rest.size + 5) / 6
        return 1 + smallPages + 1
    }

    fun draw(c: Canvas, area: Rect, page: Int, gauges: List<GaugeDef>, values: Map<String, Double>, status: String, connected: Boolean) {
        c.drawColor(bg)
        val pages = pageCount(gauges)
        val p = ((page % pages) + pages) % pages
        val a = RectF(area)
        val headerH = a.height() * 0.09f
        // Cabecera: estado + página
        text.textAlign = Paint.Align.LEFT
        text.textSize = headerH * 0.6f
        text.color = if (connected) ok else warn
        c.drawText(if (connected) "● OBD conectado" else "● $status", a.left + 12, a.top + headerH * 0.75f, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = dim
        c.drawText("${p + 1}/$pages", a.right - 12, a.top + headerH * 0.75f, text)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE

        val body = RectF(a.left, a.top + headerH, a.right, a.bottom)
        when {
            p == 0 -> drawMain(c, body, gauges, values)
            p == pages - 1 -> drawTable(c, body, gauges, values)
            else -> {
                val rest = gauges.filter { it.id != "speed" && it.id != "rpm" }
                val slice = rest.drop((p - 1) * 6).take(6)
                drawGrid(c, body, slice, values)
            }
        }
    }

    private fun drawMain(c: Canvas, r: RectF, gauges: List<GaugeDef>, values: Map<String, Double>) {
        val ids = gauges.map { it.id }.toSet()
        val big = listOfNotNull(Gauges.BY_ID["speed"]?.takeIf { "speed" in ids }, Gauges.BY_ID["rpm"]?.takeIf { "rpm" in ids })
        val side = gauges.filter { it.id != "speed" && it.id != "rpm" }.take(4)
        val dialsW = if (side.isEmpty()) r.width() else r.width() * 0.68f
        if (big.isNotEmpty()) {
            val w = dialsW / big.size
            big.forEachIndexed { i, g ->
                val cell = RectF(r.left + i * w, r.top, r.left + (i + 1) * w, r.bottom)
                dial(c, cell, g, values[g.id], true)
            }
        }
        if (side.isNotEmpty()) {
            val x0 = r.left + (if (big.isEmpty()) 0f else dialsW)
            val h = r.height() / side.size
            side.forEachIndexed { i, g ->
                tile(c, RectF(x0 + 6, r.top + i * h + 4, r.right - 8, r.top + (i + 1) * h - 4), g, values[g.id])
            }
        }
    }

    private fun drawGrid(c: Canvas, r: RectF, slice: List<GaugeDef>, values: Map<String, Double>) {
        val cols = 3
        val rows = 2
        val w = r.width() / cols
        val h = r.height() / rows
        slice.forEachIndexed { i, g ->
            val cx = i % cols
            val cy = i / cols
            dial(c, RectF(r.left + cx * w, r.top + cy * h, r.left + (cx + 1) * w, r.top + (cy + 1) * h), g, values[g.id], false)
        }
    }

    private fun drawTable(c: Canvas, r: RectF, gauges: List<GaugeDef>, values: Map<String, Double>) {
        val cols = 3
        val rows = ((gauges.size + cols - 1) / cols).coerceAtLeast(1)
        val w = r.width() / cols
        val h = min(r.height() / rows, r.height() / 4)
        gauges.forEachIndexed { i, g ->
            val cx = i % cols
            val cy = i / cols
            val cell = RectF(r.left + cx * w + 4, r.top + cy * h + 2, r.left + (cx + 1) * w - 4, r.top + (cy + 1) * h - 2)
            text.textAlign = Paint.Align.LEFT
            text.textSize = cell.height() * 0.38f
            text.color = dim
            c.drawText(g.label, cell.left + 8, cell.top + cell.height() * 0.45f, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = colorFor(g, values[g.id])
            text.textSize = cell.height() * 0.5f
            c.drawText("${Gauges.format(g, values[g.id])} ${g.unit}", cell.right - 8, cell.bottom - cell.height() * 0.12f, text)
        }
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
    }

    private fun colorFor(g: GaugeDef, v: Double?): Int {
        if (v == null) return dim
        if (g.warnAbove != null && v > g.warnAbove) return accent
        if (g.warnBelow != null && v < g.warnBelow) return warn
        return Color.WHITE
    }

    private fun dial(c: Canvas, cell: RectF, g: GaugeDef, v: Double?, big: Boolean) {
        val size = min(cell.width(), cell.height()) * 0.86f
        val cx = cell.centerX()
        val cy = cell.centerY() + size * 0.04f
        val rad = size / 2f
        val stroke = rad * (if (big) 0.11f else 0.14f)
        val oval = RectF(cx - rad + stroke, cy - rad + stroke, cx + rad - stroke, cy + rad - stroke)
        val start = 135f
        val sweep = 270f

        arcPaint.strokeWidth = stroke
        arcPaint.color = track
        c.drawArc(oval, start, sweep, false, arcPaint)

        // Zona roja (p. ej. corte de revoluciones)
        if (g.warnAbove != null && g.warnAbove < g.max) {
            val f = ((g.warnAbove - g.min) / (g.max - g.min)).toFloat().coerceIn(0f, 1f)
            arcPaint.color = Color.argb(110, 232, 64, 46)
            c.drawArc(oval, start + sweep * f, sweep * (1 - f), false, arcPaint)
        }

        if (v != null && !v.isNaN()) {
            val f = ((v - g.min) / (g.max - g.min)).toFloat().coerceIn(0f, 1f)
            arcPaint.color = when (colorFor(g, v)) { accent -> accent; warn -> warn; else -> if (big) accent else ok }
            if (f > 0.002f) c.drawArc(oval, start, sweep * f, false, arcPaint)
            // Aguja
            val ang = Math.toRadians((start + sweep * f).toDouble())
            val inner = rad - stroke * 2.6f
            fill.color = Color.WHITE
            fill.strokeWidth = stroke * 0.35f
            fill.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx, cy, cx + (cos(ang) * inner).toFloat(), cy + (sin(ang) * inner).toFloat(), fill)
            c.drawCircle(cx, cy, stroke * 0.5f, fill)
        }

        // Marcas grandes (solo relojes grandes)
        if (big) {
            val steps = if (g.id == "rpm") 7 else 12
            tickPaint.strokeWidth = 2f
            text.color = dim
            text.textSize = rad * 0.1f
            for (i in 0..steps) {
                val f = i.toFloat() / steps
                val ang = Math.toRadians((start + sweep * f).toDouble())
                val r1 = rad - stroke * 1.9f
                val r2 = rad - stroke * 2.4f
                c.drawLine(cx + (cos(ang) * r1).toFloat(), cy + (sin(ang) * r1).toFloat(),
                    cx + (cos(ang) * r2).toFloat(), cy + (sin(ang) * r2).toFloat(), tickPaint)
                val lv = g.min + (g.max - g.min) * f
                val label = if (g.id == "rpm") (lv / 1000).toInt().toString() else lv.toInt().toString()
                val r3 = rad - stroke * 3.2f
                c.drawText(label, cx + (cos(ang) * r3).toFloat(), cy + (sin(ang) * r3).toFloat() + text.textSize * 0.35f, text)
            }
        }

        // Valor, unidad y nombre
        text.color = colorFor(g, v)
        text.textSize = rad * (if (big) 0.30f else 0.34f)
        val valueY = cy + rad * (if (big) 0.45f else 0.18f)
        c.drawText(Gauges.format(g, v), cx, valueY, text)
        text.color = dim
        text.textSize = rad * (if (big) 0.11f else 0.15f)
        c.drawText(if (g.id == "rpm") "x1000 rpm" else g.unit, cx, valueY + text.textSize * 1.3f, text)
        text.textSize = rad * (if (big) 0.12f else 0.16f)
        text.color = Color.WHITE
        c.drawText(g.label, cx, cy + rad * 0.92f, text)
    }

    private fun tile(c: Canvas, r: RectF, g: GaugeDef, v: Double?) {
        fill.color = Color.rgb(24, 28, 36)
        c.drawRoundRect(r, 14f, 14f, fill)
        // barrita de nivel
        if (v != null && !v.isNaN()) {
            val f = ((v - g.min) / (g.max - g.min)).toFloat().coerceIn(0f, 1f)
            fill.color = when (colorFor(g, v)) { accent -> accent; warn -> warn; else -> ok }
            c.drawRoundRect(RectF(r.left + 10, r.bottom - 8, r.left + 10 + (r.width() - 20) * f, r.bottom - 4), 2f, 2f, fill)
        }
        text.textAlign = Paint.Align.LEFT
        text.color = dim
        text.textSize = r.height() * 0.24f
        c.drawText(g.label, r.left + 14, r.top + r.height() * 0.32f, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = colorFor(g, v)
        text.textSize = r.height() * 0.42f
        val unitW = text.measureText(" ") + run { text.textSize = r.height() * 0.22f; text.measureText(g.unit) }
        text.textSize = r.height() * 0.22f
        text.color = dim
        c.drawText(g.unit, r.right - 14, r.bottom - r.height() * 0.2f, text)
        text.textSize = r.height() * 0.42f
        text.color = colorFor(g, v)
        c.drawText(Gauges.format(g, v), r.right - 14 - unitW, r.bottom - r.height() * 0.2f, text)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
    }
}
