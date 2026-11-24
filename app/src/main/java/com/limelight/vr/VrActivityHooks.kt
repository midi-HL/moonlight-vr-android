package com.limelight.vr

import android.app.Activity
import android.app.Dialog
import android.hardware.input.InputManager
import android.os.Build
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.activity.OnBackPressedDispatcherOwner

/**
 * A Window.Callback decorator that installs the VR input interceptor on every
 * Activity without editing each Activity class. All 600+ existing screens keep
 * their own dispatchKeyEvent/onKeyDown logic; we simply get first refusal.
 */
class VrActivityHooks(
    private val activity: Activity,
    private val delegate: Window.Callback,
    private val inDialog: Boolean = false,
    private val inputWindow: Window = activity.window,
    private val backOwner: Any = delegate,
) : Window.Callback {
    private val inputState = VrInputDispatcher.WindowState(
        activity.getSystemService(android.content.Context.INPUT_SERVICE) as? InputManager,
        owner = activity,
        invokeBack = ::dispatchNativeBack,
    ) { inputWindow.decorView.findFocus() }

    @Suppress("DEPRECATION")
    private fun dispatchNativeBack() {
        when {
            backOwner is OnBackPressedDispatcherOwner -> backOwner.onBackPressedDispatcher.onBackPressed()
            backOwner is Dialog -> backOwner.onBackPressed()
            activity is OnBackPressedDispatcherOwner -> activity.onBackPressedDispatcher.onBackPressed()
            else -> activity.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VrSession.enabled && !VrSession.panelOpen && activity !is com.limelight.Game &&
            event.action == KeyEvent.ACTION_DOWN && !event.isCanceled && event.repeatCount == 0 &&
            VrInputDispatcher.isControllerKey(event) &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode in VrKeys.FOCUS_KEYS)) {
            ensureControllerFocus()
        }
        return VrInputDispatcher.dispatchKey(activity, event, inDialog, inputState, delegate::dispatchKeyEvent)
    }

    /** Consumed A/axis events never reach ViewRoot's normal touch-mode transition. */
    private fun ensureControllerFocus() {
        val decor = inputWindow.decorView
        val focused = decor.findFocus()
        if (focused != null && focused !== decor && focused.id != android.R.id.content) return
        fun find(view: View): View? {
            if (view.visibility != View.VISIBLE || !view.isEnabled || view is VrTheaterView || view is SurfaceView) return null
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return view.takeIf { it.isFocusable && it !== decor && it.id != android.R.id.content }
        }
        find(decor)?.requestFocusFromTouch()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (VrSession.enabled && !VrSession.panelOpen && activity !is com.limelight.Game &&
            VrGamepadRouter.isGamepad(event.source) && event.actionMasked == MotionEvent.ACTION_MOVE &&
            (kotlin.math.abs(event.getAxisValue(VrKeys.AXIS_X)) > VrSession.settings.gamepadDeadZone ||
                kotlin.math.abs(event.getAxisValue(VrKeys.AXIS_Y)) > VrSession.settings.gamepadDeadZone ||
                kotlin.math.abs(event.getAxisValue(VrKeys.AXIS_HAT_X)) > 0.5f ||
                kotlin.math.abs(event.getAxisValue(VrKeys.AXIS_HAT_Y)) > 0.5f)) ensureControllerFocus()
        if (VrInputDispatcher.dispatchMotion(activity, event, inDialog, inputState, delegate::dispatchKeyEvent)) return true
        return delegate.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // P-E1: with the GL layer promoted on top, the 2D UI is invisible but
        // still focusable/touchable. A stray tap inside the headset used to open
        // a hidden dialog and steal input. Swallow the whole gesture while VR is
        // on. P-E10 exception: a genuine short tap (no drag, no long press)
        // recentres the view, so the user need not take the headset off.
        return VrInputDispatcher.dispatchTouch(activity, event, inDialog, delegate::dispatchTouchEvent)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
        delegate.dispatchTrackballEvent(event)

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
        delegate.dispatchKeyShortcutEvent(event)

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(event)

    override fun onCreatePanelView(featureId: Int): View? =
        delegate.onCreatePanelView(featureId)

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean =
        delegate.onCreatePanelMenu(featureId, menu)

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
        delegate.onPreparePanel(featureId, view, menu)

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean =
        delegate.onMenuOpened(featureId, menu)

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean =
        delegate.onMenuItemSelected(featureId, item)

    override fun onPanelClosed(featureId: Int, menu: Menu) =
        delegate.onPanelClosed(featureId, menu)

    override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? =
        delegate.onWindowStartingActionMode(callback)

    @Suppress("DEPRECATION")
    override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) delegate.onWindowStartingActionMode(callback, type)
        else delegate.onWindowStartingActionMode(callback)

    override fun onActionModeStarted(mode: ActionMode) = delegate.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode) = delegate.onActionModeFinished(mode)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (inputState.onWindowFocusChanged(hasFocus)) VrSession.onWindowInputCanceled()
        delegate.onWindowFocusChanged(hasFocus)
    }

    override fun onAttachedToWindow() = delegate.onAttachedToWindow()
    override fun onDetachedFromWindow() {
        if (inputState.onWindowFocusChanged(false)) VrSession.onWindowInputCanceled()
        delegate.onDetachedFromWindow()
    }

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) =
        delegate.onWindowAttributesChanged(attrs)

    override fun onContentChanged() {
        delegate.onContentChanged()
        VrSession.onWindowDirty?.invoke()
        if (!inDialog) VrSession.onHostContentChanged(activity)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    @Suppress("DEPRECATION")
    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

    @Suppress("DEPRECATION")
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) delegate.onSearchRequested(searchEvent)
        else delegate.onSearchRequested()
}
