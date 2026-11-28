package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.Checkable
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import com.limelight.utils.ConfigurationSyncManager
import com.limelight.vr.VrQuickPanel
import com.limelight.vr.VrSession
import com.limelight.vr.VrSettings
import com.limelight.vr.VrSettingsStore
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the existing settings objects, not a freshly rebuilt PreferenceScreen. */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi")
class StreamSettingsVrPreferenceSyncTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Match StreamSettings' desugared Collections facade.
    @Suppress("unused")
    private val desugarCollectionsCompatibility = Collections.synchronizedMap(mutableMapOf<Any, Any>())

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>
    private var scenario: ActivityScenario<StreamSettings>? = null

    @Before
    fun launchSettings() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalPreferences = snapshotSettingsPreferences(preferences)
        preferences.edit()
            .putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
            .commit()
        instrumentation.runOnMainSync {
            VrSession.updateSettings(context, VrSettings.DEFAULT.copy(enabled = false))
        }
        scenario = ActivityScenario.launch(StreamSettings::class.java)
        withActivity { VrSession.setEnabled(it, true) }
        awaitReady { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            it.window.decorView.width > it.window.decorView.height && VrSession.enabled }
    }

    @After
    fun restoreSettings() {
        instrumentation.runOnMainSync {
            VrSession.setPanelOpen(false)
            VrSession.setEnabled(context, false)
        }
        scenario?.close()
        restoreSettingsPreferences(preferences, originalPreferences)
        instrumentation.runOnMainSync { VrSession.reloadFromStore(context) }
    }

    @Test
    fun quickPanelRangesRefreshExistingValuesSummariesAndNextEditor() {
        for (key in listOf(VrSettingsStore.K_SCALE, VrSettingsStore.K_ZOOM, VrSettingsStore.K_REPEAT)) {
            val row = showPreference(key)
            lateinit var originalActivity: StreamSettings
            lateinit var originalPreference: SeekBarPreference
            var expected = 0
            withActivity { activity ->
                originalActivity = activity
                originalPreference = requireNotNull(fragment(activity).findPreference(key))
                val before = originalPreference.currentValue
                VrSession.setPanelOpen(true)
                VrSession.setSelectedIndex(VrQuickPanel.rows.indexOfFirst { it.key == key })
                press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
                expected = preferences.getInt(key, -1)
                assertTrue("Quick panel must change $key", expected > before)
                press(activity, KeyEvent.KEYCODE_BUTTON_B)
                assertFalse(VrSession.panelOpen)
            }
            awaitReady { activity ->
                fragment(activity).findPreference<SeekBarPreference>(key)?.currentValue == expected &&
                    !fragment(activity).listView.hasPendingAdapterUpdates()
            }
            withActivity { activity ->
                assertSame(originalActivity, activity)
                val preference = requireNotNull(fragment(activity).findPreference<SeekBarPreference>(key))
                assertSame(originalPreference, preference)
                assertEquals(expected, preference.currentValue)
                val display = preference.formatDisplayValue(expected)
                assertTrue(preference.summary.toString().contains(display))
                assertEquals("The already attached summary row must be rebound",
                    preference.summary.toString(), row.findViewById<TextView>(android.R.id.summary).text.toString())
                assertTrue(row.performClick())
                activity.supportFragmentManager.executePendingTransactions()
            }
            awaitReady(requireActivityFocus = false) { activity ->
                val dialog = (activity.supportFragmentManager
                    .findFragmentByTag("SeekBarPreference") as? DialogFragment)?.dialog
                dialog != null && dialog.isShowing && dialog.window?.decorView?.hasWindowFocus() == true
            }
            withActivity { activity ->
                val dialog = requireNotNull((activity.supportFragmentManager
                    .findFragmentByTag("SeekBarPreference") as? DialogFragment)?.dialog)
                assertEquals("Reopened editor must start at the quick panel value", expected,
                    dialog.findViewById<SeekBar>(R.id.pref_seekbar).progress)
                dialog.cancel()
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun quickPanelCheckboxRefreshesExistingWidgetAndNextNativeToggle() {
        val key = VrSettingsStore.K_V_FLIP
        val row = showPreference(key)
        lateinit var originalActivity: StreamSettings
        lateinit var originalPreference: CheckBoxPreference
        withActivity { activity ->
            originalActivity = activity
            originalPreference = requireNotNull(fragment(activity).findPreference(key))
            assertTrue(originalPreference.isChecked)
            VrSession.setPanelOpen(true)
            VrSession.setSelectedIndex(VrQuickPanel.rows.indexOfFirst { it.key == key })
            press(activity, KeyEvent.KEYCODE_BUTTON_A)
            assertFalse(VrSettingsStore.load(activity).verticalFlip)
            press(activity, KeyEvent.KEYCODE_BUTTON_B)
            assertFalse(VrSession.panelOpen)
        }
        awaitReady { activity ->
            fragment(activity).findPreference<CheckBoxPreference>(key)?.isChecked == false &&
                !fragment(activity).listView.hasPendingAdapterUpdates()
        }
        withActivity { activity ->
            assertSame(originalActivity, activity)
            val preference = requireNotNull(fragment(activity).findPreference<CheckBoxPreference>(key))
            assertSame(originalPreference, preference)
            assertFalse(preference.isChecked)
            assertFalse("The existing checkbox widget must be rebound",
                (row.findViewById<View>(android.R.id.checkbox) as Checkable).isChecked)
            assertTrue(row.performClick())
            assertTrue("The next native click must invert the quick panel value", preference.isChecked)
            assertTrue(VrSettingsStore.load(activity).verticalFlip)
            assertTrue(VrSession.settings.verticalFlip)
        }
    }

    @Test
    fun resumeRefreshesVrValuesChangedWhilePreferenceListenerWasPaused() {
        lateinit var originalActivity: StreamSettings
        lateinit var range: SeekBarPreference
        lateinit var checkbox: CheckBoxPreference
        withActivity { activity ->
            originalActivity = activity
            range = requireNotNull(fragment(activity).findPreference(VrSettingsStore.K_SCALE))
            checkbox = requireNotNull(fragment(activity).findPreference(VrSettingsStore.K_RECENTER))
            assertEquals(1000, range.currentValue)
            assertTrue(checkbox.isChecked)
        }
        requireNotNull(scenario).moveToState(Lifecycle.State.STARTED)
        instrumentation.runOnMainSync {
            VrSettingsStore.save(context, VrSession.settings.copy(screenScale = 1.4f, recenterOnEnter = false))
        }
        instrumentation.waitForIdleSync()
        requireNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        awaitReady { activity ->
            fragment(activity).findPreference<SeekBarPreference>(VrSettingsStore.K_SCALE)?.currentValue == 1400 &&
                fragment(activity).findPreference<CheckBoxPreference>(VrSettingsStore.K_RECENTER)?.isChecked == false
        }
        withActivity { activity ->
            assertSame(originalActivity, activity)
            assertSame(range, fragment(activity).findPreference<Preference>(VrSettingsStore.K_SCALE))
            assertSame(checkbox, fragment(activity).findPreference<Preference>(VrSettingsStore.K_RECENTER))
            assertEquals(1400, range.currentValue)
            assertTrue(range.summary.toString().contains(range.formatDisplayValue(1400)))
            assertFalse(checkbox.isChecked)
        }
    }

    private fun withActivity(action: (StreamSettings) -> Unit) { requireNotNull(scenario).onActivity(action) }

    private fun fragment(activity: StreamSettings): StreamSettings.SettingsFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single()

    private fun awaitReady(
        requireActivityFocus: Boolean = true,
        condition: (StreamSettings) -> Boolean = { true },
    ) {
        compose.waitUntil(15_000) {
            var ready = false
            withActivity { activity ->
                val list = fragment(activity).listView
                ready = (!requireActivityFocus || activity.hasWindowFocus()) &&
                    list.isAttachedToWindow && list.isLaidOut &&
                    !list.isComputingLayout && !list.isLayoutRequested && condition(activity)
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }

    private fun showPreference(key: String): View {
        var position = -1
        withActivity { activity ->
            val list = fragment(activity).listView
            val adapter = list.adapter as PreferenceGroupAdapter
            position = (0 until adapter.itemCount).first { adapter.getItem(it)?.key == key }
            list.stopScroll()
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
        awaitReady { activity ->
            fragment(activity).listView.findViewHolderForAdapterPosition(position)?.itemView?.isLaidOut == true
        }
        var row: View? = null
        withActivity { row = fragment(it).listView.findViewHolderForAdapterPosition(position)?.itemView }
        return requireNotNull(row)
    }

    private fun press(activity: StreamSettings, key: Int) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(activity.window.callback.dispatchKeyEvent(KeyEvent(
                time, SystemClock.uptimeMillis(), action, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD)))
        }
    }
}
