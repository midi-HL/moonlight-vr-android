package com.limelight.vr

import android.app.Activity
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * A Window.Callback decorator that installs the VR input interceptor on every
 * Activity without editing each Activity class. All 600+ existing screens keep
 * their own dispatchKeyEvent/onKeyDown logic; we simply get first refusal.
 */
class VrActivityHooks(private val activity: Activity, private val delegate: Window.Callback) :
    Window.Callback {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VrInputDispatcher.dispatchKey(activity, event)) return true
        return delegate.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (VrInputDispatcher.dispatchMotion(activity, event)) return true
        return delegate.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // P-E1: with the GL layer promoted on top, the 2D UI is invisible but
        // still focusable/touchable. A stray tap inside the headset used to open
        // a hidden dialog and steal input. Swallow the whole gesture while VR is
        // on. P-E10 exception: a genuine short tap (no drag, no long press)
        // recentres the view, so the user need not take the headset off.
        if (VrSession.enabled) {
            VrSession.onVrTap(event)
            return true
        }
        return delegate.dispatchTouchEvent(event)
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
        delegate.onWindowStartingActionMode(callback, type)

    override fun onActionModeStarted(mode: ActionMode) = delegate.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode) = delegate.onActionModeFinished(mode)

    override fun onWindowFocusChanged(hasFocus: Boolean) =
        delegate.onWindowFocusChanged(hasFocus)

    override fun onAttachedToWindow() = delegate.onAttachedToWindow()
    override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) =
        delegate.onWindowAttributesChanged(attrs)

    override fun onContentChanged() {
        delegate.onContentChanged()
        VrSession.onWindowDirty?.invoke()
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) = delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    @Suppress("DEPRECATION")
    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

    @Suppress("DEPRECATION")
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean =
        delegate.onSearchRequested(searchEvent)
}
