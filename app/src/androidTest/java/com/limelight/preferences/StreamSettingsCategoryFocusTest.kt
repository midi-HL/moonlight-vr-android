package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import com.limelight.vr.VrSession
import com.limelight.vr.VrSettings
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers controller focus crossing between the fixed landscape sidebar and main settings list. */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi")
class StreamSettingsCategoryFocusTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<StreamSettings>? = null
    private lateinit var originalVrSettings: VrSettings

    @Before
    fun launchLandscapeSettings() {
        val context = instrumentation.targetContext
        originalVrSettings = VrSession.settings
        instrumentation.runOnMainSync {
            VrSession.updateSettings(context, originalVrSettings.copy(enabled = false))
        }
        scenario = ActivityScenario.launch(StreamSettings::class.java)
        withActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitReady { activity ->
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                categoryList(activity).adapter?.itemCount?.let { it > 0 } == true &&
                categoryList(activity).findViewHolderForAdapterPosition(0) != null &&
                preferenceList(activity).adapter is PreferenceGroupAdapter
        }
    }

    @After
    fun closeSettings() {
        scenario?.close()
        instrumentation.runOnMainSync {
            VrSession.updateSettings(instrumentation.targetContext, originalVrSettings)
        }
    }

    @Test
    fun leftFromMainPreferenceFocusesSidebarAndRightReturnsToSameRow() {
        var originalPreferenceRow: View? = null
        lateinit var selectedCategoryRow: View
        var targetPosition = RecyclerView.NO_POSITION
        withActivity { activity ->
            val preferences = preferenceList(activity)
            val adapter = preferences.adapter as PreferenceGroupAdapter
            targetPosition = (0 until adapter.itemCount).first {
                val preference = adapter.getItem(it)
                preference != null && preference !is androidx.preference.PreferenceCategory && preference.isEnabled
            }
            preferences.scrollToPosition(targetPosition)
        }
        awaitReady { activity ->
            preferenceList(activity).findViewHolderForAdapterPosition(targetPosition) != null
        }
        withActivity { activity ->
            originalPreferenceRow = requireNotNull(preferenceList(activity)
                .findViewHolderForAdapterPosition(targetPosition)).itemView
            assertTrue("A settings row can take hardware focus", requireNotNull(originalPreferenceRow).requestFocusFromTouch())
        }
        awaitReady { activity ->
            activity.currentFocus === originalPreferenceRow
        }

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitReady { activity ->
            val item = categoryList(activity).findViewHolderForAdapterPosition(0)?.itemView
            val root = item?.findViewById<View>(R.id.category_item_root)
            root != null && activity.currentFocus === root
        }
        withActivity { activity ->
            selectedCategoryRow = requireNotNull(categoryList(activity)
                .findViewHolderForAdapterPosition(0)?.itemView?.findViewById(R.id.category_item_root))
        }

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitReady { activity -> activity.currentFocus === originalPreferenceRow }
        withActivity { activity ->
            assertSame(originalPreferenceRow, activity.currentFocus)
            assertTrue("Category focus was actually entered before returning", selectedCategoryRow.isShown)
        }
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    private fun awaitReady(condition: (StreamSettings) -> Boolean) {
        compose.waitUntil(10_000) {
            var ready = false
            withActivity { activity ->
                ready = activity.hasWindowFocus() && condition(activity)
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }

    private fun withActivity(action: (StreamSettings) -> Unit) {
        requireNotNull(scenario).onActivity(action)
    }

    private fun categoryList(activity: StreamSettings): RecyclerView =
        activity.findViewById(R.id.category_list)

    private fun preferenceList(activity: StreamSettings): RecyclerView =
        activity.findViewById(androidx.preference.R.id.recycler_view)
}
