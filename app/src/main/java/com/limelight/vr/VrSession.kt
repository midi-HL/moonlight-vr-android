package com.limelight.vr

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.Gravity
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
    // Paused Activities can still be visible during a window transition. Their projection remains
    // attached until onStop, but only the resumed host owns capture/input callbacks.
    private val hosts = LinkedHashMap<Activity, VrOverlayHost>()
    private var systemBackRegistration: SystemBackRegistration? = null
    private val attached = AtomicBoolean(false)

    @Volatile var settings: VrSettings = VrSettings.DEFAULT
        private set
    @Volatile var enabled: Boolean = false
        private set
    internal data class PanelState(val open: Boolean, val generation: Long)

    @Volatile private var panelState = PanelState(open = false, generation = 0L)
    val panelOpen: Boolean get() = panelState.open
    internal val panelSnapshot: PanelState get() = panelState
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
        val activity = activeActivity
        if (value.enabled && activity != null && rejectsCurrentMultiWindow(activity)) {
            showWindowRequirement(activity)
            return value.copy(enabled = false)
        }
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
            VrInputDispatcher.cancelNativeWindows()
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
        val previous = panelState
        if (previous.open == value) return
        VrInputDispatcher.cancelNativeWindows()
        router.panelOpen = value
        editing = false
        router.editing = false
        router.resetAxisHold()
        if (value) driverSnapshots.beginCapture()
        if (!value) selectedIndex = 0
        panelState = PanelState(value, if (value) previous.generation + 1L else previous.generation)
        (activeActivity as? com.limelight.Game)?.onVrPanelChanged(value)
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

    private val backOwnership = VrBackKeyOwnership()

    /** Returns true when the event was consumed by the VR layer. */
    fun onKeyEvent(
        event: KeyEvent,
        isControllerKey: Boolean,
        backOwnedByHost: Boolean,
        captureUiMenuKeys: Boolean = false,
        controllerBackAsSelect: Boolean = false,
    ): Boolean {
        // A mouse/controller press already sent to Game must retain its matching release
        // even if a different device opens the VR panel while that press is held.
        if (event.keyCode == VrKeys.BACK && !controllerBackAsSelect) {
            return when (backOwnership.onKey(event.action, event.deviceId, event.downTime,
                    event.repeatCount > 0, event.isCanceled, enabled,
                    backOwnedByHost || !panelOpen, panelOpen)) {
                VrBackKeyOwnership.Result.PassThrough -> false
                VrBackKeyOwnership.Result.Consumed -> true
                VrBackKeyOwnership.Result.InvokeBack -> { onBackInvoked(); true }
            }
        }
        val keyCode = if (controllerBackAsSelect) VrKeys.BUTTON_SELECT else event.keyCode
        return handle(router.onKey(keyCode, event.action, isControllerKey,
            event.deviceId, event.repeatCount > 0, captureUiMenuKeys, downTime = event.downTime))
    }

    fun onBackInvoked(): Boolean {
        if (!enabled || !panelOpen) return false
        if (editing) setEditing(false) else setPanelOpen(false)
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
        // Alpha exactly zero makes Android 15 InputDispatcher reject this focused window as NOT_VISIBLE.
        val alpha: VrFloatingWindowAlpha = VrFloatingWindowAlpha(0.0001f),
        val dim: VrFloatingWindowAlpha = VrFloatingWindowAlpha(),
    )
    private val dialogDecors = ArrayList<DialogWindow>()

    /** Install the input hook in the actual focused Window and register the decor for capture. */
    fun registerDialog(dialog: Dialog) {
        val window = dialog.window ?: return
        val owner = dialog.ownerActivity ?: findActivity(dialog.context) ?: return
        val decor = window.decorView
        if (window.callback !is VrActivityHooks) {
            window.callback = VrActivityHooks(owner, window.callback ?: dialog, inDialog = true,
                inputWindow = window, backOwner = dialog)
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
                    // View still reports attached inside this callback. Dialog.dismiss()
                    // is already removing it, so only restore the saved attributes in memory.
                    updateFloatingWindow(entry, forceVisible = true, notifyWindow = false)
                }
            })
        }
        dialogDecors.remove(registration)
        dialogDecors.add(registration)
        // Run after the caller's post-show sizing/opacity updates.
        decor.post { updateFloatingWindow(registration) }
        onWindowDirty?.invoke()
    }

    private fun updateFloatingWindow(
        entry: DialogWindow,
        forceVisible: Boolean = false,
        notifyWindow: Boolean = true,
    ) {
        val owner = entry.owner.get() ?: return
        val decor = entry.decor.get() ?: return
        val hidden = !forceVisible && enabled && hosts.containsKey(owner) && decor.isAttachedToWindow
        val window = entry.window?.get()
        if (window != null) {
            val attributes = window.attributes
            val alpha = entry.alpha.update(attributes.alpha, hidden)
            val dim = entry.dim.update(attributes.dimAmount, hidden)
            if (alpha == null && dim == null) return
            if (alpha != null) attributes.alpha = alpha
            if (dim != null) attributes.dimAmount = dim
            if (notifyWindow && decor.isAttachedToWindow) window.attributes = attributes
        } else {
            val attributes = decor.layoutParams as? WindowManager.LayoutParams ?: return
            val alpha = entry.alpha.update(attributes.alpha, hidden)
            val dim = entry.dim.update(attributes.dimAmount, hidden)
            if (alpha == null && dim == null) return
            if (alpha != null) attributes.alpha = alpha
            if (dim != null) attributes.dimAmount = dim
            if (notifyWindow && decor.isAttachedToWindow) {
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

    internal data class CapturedFloatingWindow(val decor: View, val alpha: Float, val dimAmount: Float)

    /** Refresh hidden native attributes and retain their original opacity in the projected copy. */
    internal fun captureFloatingWindows(source: View): List<CapturedFloatingWindow> = dialogDecors.mapNotNull { entry ->
        if (entry.owner.get()?.window?.decorView !== source) return@mapNotNull null
        val decor = entry.decor.get()?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
            ?: return@mapNotNull null
        // GameMenu can change Window.alpha from its opacity slider between capture ticks.
        updateFloatingWindow(entry)
        val attributes = entry.window?.get()?.attributes
            ?: (decor.layoutParams as? WindowManager.LayoutParams)
        val alpha = entry.alpha.valueForCapture(attributes?.alpha ?: 1f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val dim = if (attributes != null && attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
            entry.dim.valueForCapture(attributes.dimAmount).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        } else 0f
        CapturedFloatingWindow(decor, alpha, dim)
    }

    private fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> context.baseContext?.takeIf { it !== context }?.let(::findActivity)
        else -> null
    }

    /** Returns true when the motion event was consumed by the VR layer. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        return onNavigationAxes(event.getAxisValue(VrKeys.AXIS_X), event.getAxisValue(VrKeys.AXIS_Y),
            event.getAxisValue(VrKeys.AXIS_HAT_X), event.getAxisValue(VrKeys.AXIS_HAT_Y), event.eventTime)
    }

    internal fun onNavigationAxes(x: Float, y: Float, hatX: Float, hatY: Float, timeMs: Long): Boolean {
        if (!enabled || !panelOpen) return false
        val hat = router.onHat(hatX, hatY, timeMs)
        if (hat is VrGamepadRouter.Command.Move || hat is VrGamepadRouter.Command.Adjust) return handle(hat)
        return handle(router.onAxis(x, y, timeMs))
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
        if (!enabled) {
            releaseAllHosts()
        } else activeActivity?.let { activity ->
            applyImmersive(activity)
            activeHost = ensureHost(activity)?.also { it.resume() }
        }
        syncSystemBackRegistration()
        syncFloatingWindows()
        bindActiveCallbacks()
        onWindowDirty?.invoke()
    }

    private fun ensureHost(activity: Activity): VrOverlayHost? {
        hosts[activity]?.let { return it }
        val decor = activity.window.decorView as? ViewGroup ?: return null
        return VrOverlayHost(activity, decor) { onHostWindowChanged(activity) }.also { host ->
            hosts[activity] = host
            host.install()
            if (activeActivity !== activity) host.pause()
        }
    }

    private fun rejectsCurrentMultiWindow(activity: Activity): Boolean {
        val decor = activity.window.decorView
        return VrWindowPolicy.rejectsMultiWindow(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode,
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            decor.width, decor.height)
    }

    private fun showWindowRequirement(activity: Activity) {
        android.widget.Toast.makeText(activity, com.limelight.R.string.vr_fullscreen_landscape_required,
            android.widget.Toast.LENGTH_LONG).show()
    }

    private fun onHostWindowChanged(activity: Activity) {
        if (!enabled || activeActivity !== activity || !rejectsCurrentMultiWindow(activity)) return
        // Configuration changes can arrive inside a pre-draw traversal. Restore the ordinary
        // window on the next main-loop turn, rechecking ownership after any Activity handoff.
        mainHandler.post {
            if (enabled && activeActivity === activity && rejectsCurrentMultiWindow(activity)) {
                setEnabled(activity, false)
                showWindowRequirement(activity)
            }
        }
    }

    /** Host root rebuilding may restore its normal 2D system-bar flags after setContentView. */
    internal fun onHostContentChanged(activity: Activity) {
        if (!enabled || activeActivity !== activity) return
        val host = activeHost ?: return
        val window = activity.window ?: return
        val decor = window.decorView
        decor.post {
            // An old post must never reopen VR or change a paused/replaced window.
            if (enabled && activeActivity === activity && activeHost === host &&
                activity.window === window && window.decorView === decor &&
                decor.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) {
                applyImmersive(activity)
            }
        }
    }

    private fun clearSystemBackRegistration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackRegistration?.unregister()
        }
        systemBackRegistration = null
    }

    private fun syncSystemBackRegistration() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = activeActivity
        if (!enabled || !panelOpen || activity == null) clearSystemBackRegistration()
        else if (systemBackRegistration == null) systemBackRegistration = SystemBackRegistration(activity)
    }

    private fun releaseHost(activity: Activity) {
        val host = hosts.remove(activity) ?: return
        if (activeHost === host) activeHost = null
        host.release()
        restoreFloatingWindows(activity)
    }

    private fun releaseAllHosts() {
        clearSystemBackRegistration()
        hosts.keys.toList().forEach(::releaseHost)
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
        onWindowInputCanceled()
        router.reset()
        backOwnership.clear()
    }

    /** Focus may move to a native dialog without ending a host-owned BACK press. */
    internal fun onWindowInputCanceled() {
        cancelHold()
        router.cancelWindowInput()
        tapCancelled = true
    }

    internal fun isInputOwnerResumed(activity: Activity): Boolean = activeActivity === activity

    // ---- ActivityLifecycleCallbacks: attach an overlay to every screen ----

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        restoreAutomaticLaunchOrientation(activity)
    }

    private fun restoreAutomaticLaunchOrientation(activity: Activity) {
        // Manifest BEHIND is only the initial VR handoff contract. Ordinary 2D destinations
        // retain their original automatic rotation, including launches from a fixed Game.
        if (!enabled && activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Pre-created callbacks start at API 29. On older versions do this only if the
        // Activity has not already chosen an explicit orientation during its own onCreate.
        restoreAutomaticLaunchOrientation(activity)
        val window = activity.window ?: return
        val delegate = window.callback ?: activity
        if (delegate !is VrActivityHooks) {
            window.callback = VrActivityHooks(activity, delegate)
        }
        // Cover the destination before its first draw. An orientation request is asynchronous;
        // the host waits for the actual landscape layout before constructing Cardboard.
        if (enabled) {
            applyImmersive(activity)
            ensureHost(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (activeActivity !== activity) {
            activeHost?.pause()
            clearSystemBackRegistration()
            cancelPendingInput()
        }
        activeActivity = activity
        if (enabled && rejectsCurrentMultiWindow(activity)) {
            setEnabled(activity, false)
            showWindowRequirement(activity)
            return
        }
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
        VrInputDispatcher.cancelNativeWindows(activity)
        if (activeActivity === activity) {
            cancelPendingInput()
            activeHost?.pause()
            activeActivity = null
            activeHost = null
            clearSystemBackRegistration()
            bindActiveCallbacks()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        releaseHost(activity)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Paused hosts retain their visible projection; only the resumed host owns live callbacks. */
    private fun bindActiveCallbacks() {
        val host = activeHost
        onRecenter = host?.let { { it.theaterView()?.recenter() } }
        onWindowDirty = host?.let { { it.theaterView()?.markDirty() } }
    }

    override fun onActivityDestroyed(activity: Activity) {
        onActivityPaused(activity)
        releaseHost(activity)
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
        val floatingLayout: FloatingActivityLayout?,
    )
    private data class FloatingActivityLayout(val width: Int, val height: Int, val gravity: Int, val dim: Float)
    private val windowStates = HashMap<Activity, WindowState>()

    private fun applyImmersive(activity: Activity) {
        if (!enabled) {
            restoreWindow(activity)
            return
        }
        val window = activity.window
        val floating = activity.hasFloatingOrTranslucentWindow()
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
                floatingLayout = if (floating) FloatingActivityLayout(
                    attrs.width, attrs.height, attrs.gravity, attrs.dimAmount) else null,
            )
        }
        // Android 8.0 rejects orientation requests from floating or translucent Activities.
        // Multi-window hosts already follow the display orientation.
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !activity.isInMultiWindowMode) &&
            !floating) {
            val state = windowStates.getValue(activity)
            if (state.orientation == null) {
                val originalOrientation = activity.requestedOrientation.let { orientation ->
                    if (orientation == ActivityInfo.SCREEN_ORIENTATION_BEHIND)
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else orientation
                }
                windowStates[activity] = state.copy(orientation = originalOrientation)
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (floating) {
            // A dialog-sized Activity would place both eyes inside the little native window.
            // Project it over the full display while keeping its inherited source orientation.
            window.attributes = window.attributes.also {
                it.width = ViewGroup.LayoutParams.MATCH_PARENT
                it.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.gravity = Gravity.FILL
                it.dimAmount = 0f
            }
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
        state.floatingLayout?.let { original ->
            activity.window.attributes = activity.window.attributes.also {
                it.width = original.width
                it.height = original.height
                it.gravity = original.gravity
                it.dimAmount = original.dim
            }
        }
    }

    /** Restore any activity that had a VR overlay in this session. */
    private fun restoreAllWindows() {
        val activities = windowStates.keys.toList()
        activities.forEach(::restoreWindow)
        // Game can have been created with VR already active, so its saved snapshot
        // may itself be SENSOR_LANDSCAPE rather than the user's normal 2D preference.
        activities.filterIsInstance<com.limelight.Game>().forEach { it.restore2dOrientationAfterVr() }
    }

    private fun Activity.hasFloatingOrTranslucentWindow(): Boolean {
        val value = android.util.TypedValue()
        return intArrayOf(android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent).any { attribute ->
            theme.resolveAttribute(attribute, value, true) &&
                value.type == android.util.TypedValue.TYPE_INT_BOOLEAN && value.data != 0
        }
    }
}
