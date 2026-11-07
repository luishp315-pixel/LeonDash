package com.luis.leondash

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Punto de entrada de Android Auto. */
class DashCarAppService : CarAppService() {
    // App instalada por ti (fuentes desconocidas): se acepta cualquier host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        ObdManager.init(carContext)
        if (Store.settings(carContext).autoConnect) ObdManager.connect(carContext)
        return DashScreen(carContext)
    }
}

/** Pantalla principal: dibuja los relojes sobre la superficie del coche. */
class DashScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val renderer = GaugeRenderer()
    private var surface: Surface? = null
    private var area = Rect()
    private var page = 0
    private var drawThread: HandlerThread? = null
    private var drawHandler: Handler? = null
    @Volatile private var dirty = true
    private val listener: () -> Unit = { dirty = true }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        ObdManager.addListener(listener)
        val t = HandlerThread("draw").also { it.start() }
        drawThread = t
        drawHandler = Handler(t.looper)
        drawHandler?.post(drawLoop)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        ObdManager.removeListener(listener)
        drawHandler?.removeCallbacksAndMessages(null)
        drawThread?.quitSafely()
        drawThread = null
        drawHandler = null
    }

    private val drawLoop: Runnable by lazy { object : Runnable {
        override fun run() {
            if (dirty) { dirty = false; render() }
            drawHandler?.postDelayed(this, 120) // ~8 imágenes por segundo
        }
    } }

    private fun render() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = try { s.lockHardwareCanvas() } catch (e: Exception) { return }
        try {
            val r = if (area.isEmpty) Rect(0, 0, canvas.width, canvas.height) else Rect(area)
            val settings = Store.settings(carContext)
            val gauges = Gauges.visible(settings, ObdManager.supportedPids)
            renderer.draw(canvas, r, page, gauges, HashMap(ObdManager.values),
                ObdManager.status, ObdManager.state == ObdManager.State.CONNECTED)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    // ---- SurfaceCallback ----
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        if (area.isEmpty) area = Rect(0, 0, surfaceContainer.width, surfaceContainer.height)
        dirty = true
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        area = Rect(visibleArea); dirty = true
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        dirty = true
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    // ---- Botones ----
    override fun onGetTemplate(): Template {
        // En plantillas de navegación solo 1 botón puede llevar texto: el resto van con icono
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Página").setIcon(icon(R.drawable.ic_next)).setOnClickListener {
                page++; dirty = true
            }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_history)).setOnClickListener {
                screenManager.push(TripsScreen(carContext))
            }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_build)).setOnClickListener {
                screenManager.push(ServiceScreen(carContext))
            }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_bt)).setOnClickListener {
                if (ObdManager.state == ObdManager.State.DISCONNECTED) ObdManager.connect(carContext)
                else ObdManager.disconnect()
                invalidate()
            }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}

/** Historial de consumos en la pantalla del coche. */
class TripsScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val trips = Store.trips(carContext)
        val list = ItemList.Builder()
        val last30 = trips.filter { it.start > System.currentTimeMillis() - 30L * 86_400_000L }
        val km30 = last30.sumOf { it.km }
        val l30 = last30.sumOf { it.liters }
        list.addItem(Row.Builder().setTitle("Últimos 30 días")
            .addText("${Store.f1(km30)} km · ${Store.f1(l30)} l · media ${Store.f1(if (km30 > 0.1) l30 / km30 * 100 else Double.NaN)} l/100")
            .build())
        trips.take(5).forEach { t ->
            list.addItem(Row.Builder().setTitle(Store.fmtDateTime(t.start))
                .addText("${Store.f1(t.km)} km · ${Store.f1(t.avgL100)} l/100 · ${t.minutes.toInt()} min")
                .build())
        }
        return ListTemplate.Builder()
            .setTitle("Consumos")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** Próximas revisiones en la pantalla del coche. */
class ServiceScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val st = Store.serviceStatus(carContext)
            .sortedWith(compareBy<ServiceStatus>({ !it.overdue }, { !it.soon }, { it.last == null }))
        val pane = Pane.Builder()
        val odo = Store.odometer(carContext)
        pane.addRow(Row.Builder().setTitle("Kilómetros aprox.")
            .addText(if (odo > 0) "${odo.toInt()} km" else "Introdúcelos en la app del móvil").build())
        st.take(3).forEach { s ->
            val prefix = when { s.overdue -> "⚠ "; s.soon -> "• "; else -> "" }
            pane.addRow(Row.Builder().setTitle(prefix + s.type.name).addText(Store.statusText(s)).build())
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Revisiones")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
