package com.limelight.vr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
    private val hosts = LinkedHashMap<Activity, VrOverlayHost>()
    @Volatile private var activeActivity: Activity? = null
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

    fun install(application: Application) {
        if (!attached.compareAndSet(false, true)) return
        app = application
        application.registerActivityLifecycleCallbacks(this)
        reloadFromStore(application)
        if (enabled && android.os.Build.VERSION.SDK_INT < 24) {
            Log.w(TAG, "VR requires API 24+; disabling persisted VR flag")
            enabled = false
            router.vrEnabled = false
            VrSettingsStore.save(application, settings.copy(enabled = false))
        }
        Log.i(TAG, "install: vrEnabled=$enabled")
    }

    fun reloadFromStore(context: Context) {
        settings = VrSettingsStore.load(context)
        enabled = settings.enabled
        router.vrEnabled = enabled
        router.deadZone = settings.gamepadDeadZone
        router.repeatMs = settings.menuRepeatMs
        if (!enabled) setPanelOpen(false)
        refreshHosts()
    }

    fun updateSettings(context: Context, value: VrSettings) {
        val clean = value.sanitized()
        val wasEnabled = enabled
        VrSettingsStore.save(context, clean)
        settings = clean
        enabled = clean.enabled
        if (wasEnabled && !enabled) restoreAllWindows()
        router.vrEnabled = enabled
        router.deadZone = clean.gamepadDeadZone
        router.repeatMs = clean.menuRepeatMs
        if (!enabled) setPanelOpen(false)
        refreshHosts()
        Log.i(TAG, "updateSettings: enabled=$enabled scale=${clean.screenScale}")
    }

    fun setEnabled(context: Context, value: Boolean) {
        if (value && android.os.Build.VERSION.SDK_INT < 24) {
            Log.w(TAG, "VR requires API 24+ (Cardboard SDK); staying in 2D")
            return
        }
        updateSettings(context, settings.copy(enabled = value))
        if (value && settings.recenterOnEnter) {
            mainHandler.post { onRecenter?.invoke() }
        }
    }

    fun toggle(context: Context) = setEnabled(context, !enabled)

    fun setPanelOpen(value: Boolean) {
        if (panelOpen == value) return
        panelOpen = value
        router.panelOpen = value
        editing = false
        router.editing = false
        if (!value) selectedIndex = 0
        refreshHosts()
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index.coerceIn(0, VrQuickPanel.rows.lastIndex)
        refreshHosts()
    }

    fun setEditing(value: Boolean) {
        editing = value
        router.editing = value
        router.resetAxisHold()
        refreshHosts()
    }

    /** Returns true when the event was consumed by the VR layer. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepad = VrGamepadRouter.isGamepad(source)
        val command = router.onKey(event.keyCode, event.action, isGamepad)
        return handle(command)
    }

    fun onUiNavigation(x: Float, y: Float, hatY: Float, timeMs: Long): Boolean =
        router.onUiNavigation(x, y, hatY, timeMs)

    fun drainNavDirection(): Int = router.drainNavDirection()

    /**
     * Dialogs own a separate Window, so decor.draw() on the Activity cannot see
     * them. Screens that open a Dialog register its decor here; the capture then
     * draws it on top of the Activity decor. Weakly referenced and cleared when
     * the Dialog is dismissed (detected by isShowing / attached state).
     */
    private val dialogDecors = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<View>>()

    fun registerDialogDecor(decor: View?) {
        if (decor == null) return
        dialogDecors.removeAll { it.get() == null || it.get() === decor }
        dialogDecors.add(java.lang.ref.WeakReference(decor))
        onWindowDirty?.invoke()
    }

    fun activeDialogDecors(): List<View> = dialogDecors.mapNotNull { ref ->
        ref.get()?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    }

    /** Returns true when the motion event was consumed by the VR layer. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!enabled || !panelOpen) return false
        val hat = router.onHat(event.getAxisValue(VrKeys.AXIS_HAT_Y))
        val axis = router.onAxis(
            event.getAxisValue(VrKeys.AXIS_X),
            event.getAxisValue(VrKeys.AXIS_Y),
            event.eventTime,
        )
        val command = if (hat is VrGamepadRouter.Command.Move) hat else axis
        return handle(command)
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
                setPanelOpen(false)
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
        hosts.values.forEach { it.onStateChanged() }
        bindActiveCallbacks()
        onWindowDirty?.invoke()
    }

    private fun cancelHold() {
        holdRunnable?.let { mainHandler.removeCallbacks(it) }
        holdRunnable = null
    }

    // ---- ActivityLifecycleCallbacks: attach an overlay to every screen ----

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val window = activity.window ?: return
        val delegate = window.callback ?: activity
        if (delegate !is VrActivityHooks) {
            window.callback = VrActivityHooks(activity, delegate)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (hosts.containsKey(activity)) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val host = VrOverlayHost(activity, decor)
        hosts[activity] = host
        host.onStateChanged()
        Log.i(TAG, "attach overlay: ${activity.javaClass.simpleName}")
    }

    override fun onActivityResumed(activity: Activity) {
        activeActivity = activity
        bindActiveCallbacks()
        hosts[activity]?.onStateChanged()
        applyImmersive(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // Do NOT guess the next Activity here: during a transition the guessed
        // host may still be paused/background. onActivityResumed binds the real
        // top-resumed Activity; meanwhile keep no stale GL callbacks.
        if (activeActivity === activity) {
            activeActivity = null
            onRecenter = null
            onWindowDirty = null
        }
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Only the topmost Activity owns the GL callbacks; others keep a passive overlay. */
    private fun bindActiveCallbacks() {
        val host = activeActivity?.let { hosts[it] }
        onRecenter = host?.let { { it.theaterView()?.recenter() } }
        onWindowDirty = host?.let { { it.theaterView()?.markDirty() } }
    }

    override fun onActivityDestroyed(activity: Activity) {
        hosts.remove(activity)?.release()
        windowStates.remove(activity)
        if (activeActivity === activity) {
            activeActivity = null
            onRecenter = null
            onWindowDirty = null
        }
        if (hosts.isEmpty()) cancelHold()
    }

    /** Original per-Activity window state, restored verbatim when VR is turned off. */
    private data class WindowState(val uiVisibility: Int, val keepScreenOn: Boolean)
    private val windowStates = HashMap<Activity, WindowState>()

    private fun applyImmersive(activity: Activity) {
        if (!enabled) {
            restoreWindow(activity)
            return
        }
        val window = activity.window
        @Suppress("DEPRECATION")
        windowStates.getOrPut(activity) {
            WindowState(
                uiVisibility = window.decorView.systemUiVisibility,
                keepScreenOn = window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
            )
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
        if (!state.keepScreenOn) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Called from updateSettings when the VR flag transitions to off. */
    private fun restoreAllWindows() {
        windowStates.keys.toList().forEach(::restoreWindow)
    }
}
