package com.limelight.vr

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import java.lang.ref.WeakReference
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-wide VR state. Registered once from [com.limelight.LimelightApplication].
 *
 * Unlike OpenNOW-VR, VR here is NOT tied to a stream session: an overlay is attached
 * to EVERY Activity's decor view (PcView, AppView, StreamSettings, Game, dialogs),
 * the whole window is captured into a GL texture, and the same texture is drawn to
 * both eyes. Gamepad events are injected into the decor so Android's normal focus
 * navigation keeps working while the headset is on.
 */
object VrSession : Application.ActivityLifecycleCallbacks {
    private const val TAG = "MoonlightVR"

    @Volatile private var app: Application? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val router = VrGamepadRouter()
    private val driverSnapshots = VrDriverSnapshotRouter()
    @Volatile private var activeActivity: Activity? = null
    private var activeHost: VrOverlayHost? = null
    private var systemBackRegistration: SystemBackRegistration? = null
    private val attached = AtomicBoolean(false)

    @Volatile var settings: VrSettings = VrSettings.DEFAULT
        private set
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var panelOpen: Boolean = false
        private set
    @Volatile var selectedIndex: Int = 0
        private set
    @Volatile var editing: Boolean = false
        private set

    /** Set by VrOverlayHost so the "立即重置中心点" row can reach the GL thread. */
    @Volatile var onRecenter: (() -> Unit)? = null
    /** Fired when the decor View tree changed and the GL texture must be re-captured. */
    @Volatile var onWindowDirty: (() -> Unit)? = null

    private var holdRunnable: Runnable? = null

    /** The bundled Cardboard library contains arm64 native code only and requires GLES 3.0. */
    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !Process.is64Bit() ||
            !Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
        ) return false
        val glVersion = app?.getSystemService(ActivityManager::class.java)
            ?.deviceConfigurationInfo?.reqGlEsVersion ?: return false
        return glVersion >= 0x00030000
    }

    fun install(application: Application) {
        if (!attached.compareAndSet(false, true)) return
        app = application
        application.registerActivityLifecycleCallbacks(this)
        reloadFromStore(application)
        Log.i(TAG, "install: vrEnabled=$enabled")
    }

    fun reloadFromStore(context: Context) {
        val stored = VrSettingsStore.load(context)
        val supported = filterUnsupportedSettings(stored)
        applySettings(supported)
        if (supported != stored) VrSettingsStore.save(context, supported)
    }

    fun updateSettings(context: Context, value: VrSettings) {
        val clean = filterUnsupportedSettings(value.sanitized())
        applySettings(clean)
        VrSettingsStore.save(context, clean)
    }

    private fun filterUnsupportedSettings(value: VrSettings): VrSettings {
        if (value.enabled && !isSupported()) return value.copy(enabled = false)
        val game = activeActivity as? com.limelight.Game
        if (value.enabled && !enabled && game?.canEnterVrDuringStream() == false) {
            android.widget.Toast.makeText(game, com.limelight.R.string.vr_restart_stream, android.widget.Toast.LENGTH_LONG).show()
            return value.copy(enabled = false)
        }
        return value
    }

    private fun applySettings(clean: VrSettings) {
        if (clean == settings) return
        val wasEnabled = enabled
        settings = clean
        enabled = clean.enabled
        router.vrEnabled = enabled
        router.deadZone = clean.gamepadDeadZone
        router.repeatMs = clean.menuRepeatMs
        if (wasEnabled && !enabled) {
            setPanelOpen(false)
            restoreAllWindows()
        }
        refreshHosts()
        Log.i(TAG, "updateSettings: enabled=$enabled scale=${clean.screenScale}")
    }

    fun setEnabled(context: Context, value: Boolean) {
        if (value && !isSupported()) {
            Log.w(TAG, "VR requires Android 7.0+, a compatible 64-bit runtime, and OpenGL ES 3.0")
            return
        }
        val game = activeActivity as? com.limelight.Game
        if (value && !enabled && game?.canEnterVrDuringStream() == false) {
            android.widget.Toast.makeText(game, com.limelight.R.string.vr_restart_stream, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        updateSettings(context, settings.copy(enabled = value))
    }

    fun toggle(context: Context) = setEnabled(context, !enabled)

    /** A GL-thread output failure rolls back the current projection on the main thread. */
    fun onVideoOutputFailure(isCurrent: () -> Boolean = { true }) {
        mainHandler.post {
            if (!enabled || !isCurrent()) return@post
            val context = activeActivity ?: app ?: return@post
            updateSettings(context, settings.copy(enabled = false))
            android.widget.Toast.makeText(context, com.limelight.R.string.vr_surface_failed,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // P-E10: short-tap recentre state. Touch events are otherwise swallowed by
    // VrActivityHooks so the hidden 2D UI can never steal focus.
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapCancelled = false

    fun onVrTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = event.x
                tapDownY = event.y
                tapCancelled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.x - tapDownX) > 24f || Math.abs(event.y - tapDownY) > 24f) {
                    tapCancelled = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = event.eventTime - event.downTime <= 400L
                if (!tapCancelled && quick) onRecenter?.invoke()
                tapCancelled = false
            }
            MotionEvent.ACTION_CANCEL -> tapCancelled = true
        }
    }

    fun setPanelOpen(value: Boolean) {
        if (panelOpen == value) return
        panelOpen = value
        router.panelOpen = value
        editing = false
        router.editing = false
        router.resetAxisHold()
        if (value) driverSnapshots.beginCapture()
        (activeActivity as? com.limelight.Game)?.onVrPanelChanged(value)
        if (!value) selectedIndex = 0
        refreshHosts()
    }

    fun setSelectedIndex(index: Int) {
        val nextIndex = index.coerceIn(0, VrQuickPanel.rows.lastIndex)
        if (nextIndex != selectedIndex) {
            editing = false
            router.editing = false
            router.resetAxisHold()
        }
        selectedIndex = nextIndex
        refreshHosts()
    }

    fun setEditing(value: Boolean) {
        editing = value
        router.editing = value
        router.resetAxisHold()
        refreshHosts()
    }

    private val backPresses = HashMap<Int, Long>()

    /** Returns true when the event was consumed by the VR layer. */
    fun onKeyEvent(event: KeyEvent, isControllerKey: Boolean, backOwnedByHost: Boolean): Boolean {
        // A focused Android dialog or controller in Game owns BACK until the VR panel is opened.
        if (event.keyCode == VrKeys.BACK && (!backOwnedByHost || panelOpen || event.deviceId in backPresses)) {
            when (event.action) {
                VrKeys.ACTION_DOWN -> if (enabled) {
                    if (event.repeatCount == 0) backPresses[event.deviceId] = event.downTime
                    return true
                }
                VrKeys.ACTION_UP -> {
                    val downTime = backPresses.remove(event.deviceId)
                    if (downTime != null) {
                        if (downTime == event.downTime && !event.isCanceled) onBackInvoked()
                        return true
                    }
                    if (enabled) return true
                }
            }
        }
        return handle(router.onKey(event.keyCode, event.action, isControllerKey,
            event.deviceId, event.repeatCount > 0))
    }

    fun onBackInvoked(): Boolean {
        if (!enabled) return false
        if (panelOpen) setPanelOpen(false)
        else setEnabled(app ?: return true, false)
        return true
    }

    fun onUiNavigation(x: Float, y: Float, hatX: Float, hatY: Float, timeMs: Long): Boolean =
        router.onUiNavigation(x, y, hatX, hatY, timeMs)

    fun drainNavDirection(): Int = router.drainNavDirection()

    /** Must run on main; raw-driver and Android-key events share one ordered input state. */
    internal fun onDriverSnapshot(
        activity: Activity,
        sourceId: Int,
        buttons: Int,
        leftX: Float, leftY: Float,
        rightX: Float, rightY: Float,
        leftTrigger: Float, rightTrigger: Float,
        timeMs: Long,
    ): VrDriverSnapshotRouter.Result {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activeActivity !== activity) return VrDriverSnapshotRouter.Result(buttons, captured = false)
        val result = driverSnapshots.update(sourceId, buttons, leftX, leftY, rightX, rightY,
            leftTrigger, rightTrigger, timeMs, settings.gamepadDeadZone, settings.menuRepeatMs,
            panelOpen = { panelOpen },
            onKey = { key, action, repeated ->
                handle(router.onKey(key, action, true, sourceId, repeated))
            })
        if (enabled && panelOpen && buttons and VrDriverSnapshotRouter.DIRECTION_MASK == 0) {
            handle(router.onAxis(leftX, leftY, timeMs))
        }
        return result
    }

    internal fun onDriverDisconnected(sourceId: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onDriverDisconnected(sourceId) }
            return
        }
        driverSnapshots.forget(sourceId) { key -> handle(router.onKey(key, VrKeys.ACTION_UP, true, sourceId)) }
    }

    private data class DialogWindow(
        val owner: WeakReference<Activity>,
        val decor: WeakReference<View>,
        val window: WeakReference<Window>?,
        val alpha: VrFloatingWindowAlpha = VrFloatingWindowAlpha(),
    )
    private val dialogDecors = ArrayList<DialogWindow>()

    /** Install the input hook in the actual focused Window and register the decor for capture. */
    fun registerDialog(dialog: Dialog) {
        val window = dialog.window ?: return
        val owner = dialog.ownerActivity ?: findActivity(dialog.context) ?: return
        val decor = window.decorView
        if (window.callback !is VrActivityHooks) {
            window.callback = VrActivityHooks(owner, window.callback ?: dialog, inDialog = true)
        }
        registerFloatingWindow(owner, decor, window)
    }

    /** PopupWindow exposes a root View rather than a public Android Window. Call after show(). */
    fun registerPopupWindow(popup: PopupWindow, owner: Activity) {
        if (!popup.isShowing) return
        registerFloatingWindow(owner, popup.contentView.rootView, null)
    }

    private fun registerFloatingWindow(owner: Activity, decor: View, window: Window?) {
        dialogDecors.removeAll { it.decor.get() == null || it.owner.get() == null }
        val existing = dialogDecors.firstOrNull { it.decor.get() === decor }
        val registration = existing ?: DialogWindow(WeakReference(owner), WeakReference(decor),
            window?.let { WeakReference(it) }).also { entry ->
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.post { updateFloatingWindow(entry) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    updateFloatingWindow(entry, forceVisible = true)
                }
            })
        }
        dialogDecors.remove(registration)
        dialogDecors.add(registration)
        // Run after the caller's post-show sizing/opacity updates.
        decor.post { updateFloatingWindow(registration) }
        onWindowDirty?.invoke()
    }

    private fun updateFloatingWindow(entry: DialogWindow, forceVisible: Boolean = false) {
        val owner = entry.owner.get() ?: return
        val decor = entry.decor.get() ?: return
        val hidden = !forceVisible && enabled && activeActivity === owner && decor.isAttachedToWindow
        val window = entry.window?.get()
        if (window != null) {
            val attributes = window.attributes
            val alpha = entry.alpha.update(attributes.alpha, hidden) ?: return
            window.attributes = attributes.also { it.alpha = alpha }
        } else {
            val attributes = decor.layoutParams as? WindowManager.LayoutParams ?: return
            val alpha = entry.alpha.update(attributes.alpha, hidden) ?: return
            attributes.alpha = alpha
            if (decor.isAttachedToWindow) {
                val manager = owner.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                manager.updateViewLayout(decor, attributes)
            }
        }
    }

    private fun syncFloatingWindows() = dialogDecors.toList().forEach { updateFloatingWindow(it) }

    private fun restoreFloatingWindows(owner: Activity) {
        dialogDecors.filter { it.owner.get() === owner }.forEach { updateFloatingWindow(it, forceVisible = true) }
    }

    fun activeDialogDecors(source: View): List<View> = dialogDecors.mapNotNull { entry ->
        if (entry.owner.get()?.window?.decorView !== source) return@mapNotNull null
        entry.decor.get()?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    }

    private fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> context.baseContext?.takeIf { it !== context }?.let(::findActivity)
        else -> null
    }

    /** Returns true when the motion event was consumed by the VR layer. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!enabled || !panelOpen) return false
        val hat = router.onHat(event.getAxisValue(VrKeys.AXIS_HAT_X), event.getAxisValue(VrKeys.AXIS_HAT_Y))
        if (hat is VrGamepadRouter.Command.Move || hat is VrGamepadRouter.Command.Adjust) return handle(hat)
        return handle(router.onAxis(
            event.getAxisValue(VrKeys.AXIS_X),
            event.getAxisValue(VrKeys.AXIS_Y),
            event.eventTime,
        ))
    }

    private fun handle(command: VrGamepadRouter.Command): Boolean {
        val context = app ?: return false
        return when (command) {
            VrGamepadRouter.Command.PassThrough -> false
            VrGamepadRouter.Command.Consumed -> true
            VrGamepadRouter.Command.ToggleVr -> {
                setEnabled(context, true)
                true
            }
            VrGamepadRouter.Command.ArmMenuHold -> {
                cancelHold()
                val runnable = Runnable {
                    holdRunnable = null
                    if (router.onMenuHoldElapsed() != null) setPanelOpen(!panelOpen)
                }
                holdRunnable = runnable
                mainHandler.postDelayed(runnable, VrKeys.MENU_LONG_PRESS_MS)
                true
            }
            VrGamepadRouter.Command.CancelMenuHold -> {
                cancelHold()
                true
            }
            VrGamepadRouter.Command.TogglePanel -> {
                setPanelOpen(!panelOpen)
                true
            }
            VrGamepadRouter.Command.ClosePanel -> {
                if (editing) setEditing(false) else setPanelOpen(false)
                true
            }
            VrGamepadRouter.Command.Confirm -> {
                confirmSelected()
                true
            }
            VrGamepadRouter.Command.Recentre -> {
                onRecenter?.invoke()
                true
            }
            VrGamepadRouter.Command.ExitVr -> {
                setEnabled(context, false)
                true
            }
            is VrGamepadRouter.Command.Move -> {
                setSelectedIndex(selectedIndex + command.delta)
                true
            }
            is VrGamepadRouter.Command.Adjust -> {
                adjustSelected(command.steps)
                true
            }
        }
    }

    private fun confirmSelected() {
        val row = VrQuickPanel.rows[selectedIndex]
        when (row.kind) {
            VrQuickPanel.Kind.ACTION -> when (row.key) {
                "vr_recenter_now" -> onRecenter?.invoke()
                "vr_exit" -> app?.let { setEnabled(it, false) }
            }
            VrQuickPanel.Kind.TOGGLE -> app?.let {
                updateSettings(it, VrQuickPanel.toggle(row, settings))
            }
            VrQuickPanel.Kind.RANGE -> setEditing(!editing)
        }
        onWindowDirty?.invoke()
    }

    private fun adjustSelected(steps: Int) {
        val row = VrQuickPanel.rows[selectedIndex]
        val context = app ?: return
        val next = VrQuickPanel.adjust(row, settings, steps)
        if (next != settings) updateSettings(context, next)
    }

    private fun refreshHosts() {
        syncFloatingWindows()
        val activity = activeActivity ?: return
        if (enabled) {
            if (activeHost == null) {
                val decor = activity.window.decorView as? ViewGroup ?: return
                val host = VrOverlayHost(activity, decor)
                host.install()
                activeHost = host
                applyImmersive(activity)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && systemBackRegistration == null) {
                systemBackRegistration = SystemBackRegistration(activity)
            }
        } else {
            releaseActiveHost()
        }
        bindActiveCallbacks()
        onWindowDirty?.invoke()
    }

    private fun releaseActiveHost() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackRegistration?.unregister()
        }
        systemBackRegistration = null
        activeHost?.release()
        activeHost = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class SystemBackRegistration(activity: Activity) {
        private val dispatcher = activity.onBackInvokedDispatcher
        private val callback = OnBackInvokedCallback { onBackInvoked() }

        init {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        }

        fun unregister() = dispatcher.unregisterOnBackInvokedCallback(callback)
    }

    private fun cancelHold() {
        holdRunnable?.let { mainHandler.removeCallbacks(it) }
        holdRunnable = null
    }

    private fun cancelPendingInput() {
        cancelHold()
        backPresses.clear()
        router.reset()
        tapCancelled = true
    }

    // ---- ActivityLifecycleCallbacks: attach an overlay to every screen ----

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val window = activity.window ?: return
        val delegate = window.callback ?: activity
        if (delegate !is VrActivityHooks) {
            window.callback = VrActivityHooks(activity, delegate)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (activeActivity !== activity) releaseActiveHost()
        activeActivity = activity
        // Another screen may have enabled VR while this stream was kept alive in the background.
        // Recheck the settled Game presentation path before creating its stereo surface.
        if (enabled && activity is com.limelight.Game && !activity.canEnterVrDuringStream()) {
            android.widget.Toast.makeText(activity, com.limelight.R.string.vr_restart_stream,
                android.widget.Toast.LENGTH_LONG).show()
            updateSettings(activity, settings.copy(enabled = false))
            return
        }
        refreshHosts()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activeActivity === activity) {
            restoreFloatingWindows(activity)
            cancelPendingInput()
            activeActivity = null
            releaseActiveHost()
            bindActiveCallbacks()
        }
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Only the resumed Activity owns a projection and its GL callbacks. */
    private fun bindActiveCallbacks() {
        val host = activeHost
        onRecenter = host?.let { { it.theaterView()?.recenter() } }
        onWindowDirty = host?.let { { it.theaterView()?.markDirty() } }
    }

    override fun onActivityDestroyed(activity: Activity) {
        onActivityPaused(activity)
        restoreFloatingWindows(activity)
        dialogDecors.removeAll { it.owner.get() == null || it.owner.get() === activity }
        windowStates.remove(activity)
    }

    /** Per-Activity window settings changed by VR. */
    private data class WindowState(
        val uiVisibility: Int,
        val keepScreenOn: Boolean,
        val orientation: Int?,
        val layoutNoLimits: Boolean,
        val cutoutMode: Int?,
    )
    private val windowStates = HashMap<Activity, WindowState>()

    private fun applyImmersive(activity: Activity) {
        if (!enabled) {
            restoreWindow(activity)
            return
        }
        val window = activity.window
        @Suppress("DEPRECATION")
        windowStates.getOrPut(activity) {
            val attrs = window.attributes
            WindowState(
                uiVisibility = window.decorView.systemUiVisibility,
                keepScreenOn = (attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
                orientation = null,
                layoutNoLimits = (attrs.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0,
                cutoutMode = if (android.os.Build.VERSION.SDK_INT >= 28)
                    attrs.layoutInDisplayCutoutMode else null,
            )
        }
        // Android 8.0 rejects orientation requests from floating or translucent Activities.
        // Multi-window hosts already follow the display orientation.
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !activity.isInMultiWindowMode) &&
            !activity.hasFloatingOrTranslucentWindow()) {
            val state = windowStates.getValue(activity)
            if (state.orientation == null) {
                windowStates[activity] = state.copy(orientation = activity.requestedOrientation)
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun restoreWindow(activity: Activity) {
        val state = windowStates.remove(activity) ?: return
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = state.uiVisibility
        state.orientation?.let { activity.requestedOrientation = it }
        activity.window.setFlags(
            if (state.keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        activity.window.setFlags(
            if (state.layoutNoLimits) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        val cutout = state.cutoutMode
        if (android.os.Build.VERSION.SDK_INT >= 28 && cutout != null) {
            activity.window.attributes = activity.window.attributes.also {
                it.layoutInDisplayCutoutMode = cutout
            }
        }
    }

    /** Restore any activity that had a VR overlay in this session. */
    private fun restoreAllWindows() {
        windowStates.keys.toList().forEach(::restoreWindow)
    }

    private fun Activity.hasFloatingOrTranslucentWindow(): Boolean {
        val value = android.util.TypedValue()
        return intArrayOf(android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent).any { attribute ->
            theme.resolveAttribute(attribute, value, true) &&
                value.type == android.util.TypedValue.TYPE_INT_BOOLEAN && value.data != 0
        }
    }
}
