package com.limelight.vr

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class VrRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun panelDrawsEverySelectionInBothLanguagesAndSizes() {
        instrumentation.runOnMainSync {
            for (language in listOf("en", "zh")) {
                val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale(language))
                })
                val view = VrPanelView(localized)
                for ((width, height) in listOf(1024 to 576, 640 to 360)) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    view.layoutFor(width, height)
                    for (row in VrQuickPanel.rows.indices) {
                        bitmap.eraseColor(0)
                        view.drawInto(Canvas(bitmap), VrSettings.DEFAULT, row, row % 2 == 0)
                        assertEquals("Outside padding must remain transparent", 0, bitmap.getPixel(0, 0))
                        assertTrue("Controls must be opaque and visible", bitmap.getPixel(width / 2, height / 2) ushr 24 > 240)
                        if (width == 1024 && row in listOf(0, 4, 12, 15)) save(bitmap, "panel-$language-$row.png")
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun stereoLifecycleDialogAndKeyDispatchStayUsable() {
        val original = VrSession.settings
        val launch = Intent(context, PcView::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<PcView>(launch)
        try {
            scenario.onActivity { activity ->
                assertTrue("Device must support the bundled Cardboard runtime", VrSession.isSupported())
                VrSession.setEnabled(activity, true)
                assertTrue(VrSession.enabled)
                val decor = activity.window.decorView
                assertEquals(1, countTheaters(decor))
                val time = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    activity.window.callback.dispatchKeyEvent(KeyEvent(time, time, action,
                        KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, 9, 0, 0, InputDevice.SOURCE_DPAD))
                }
                VrSession.setPanelOpen(true)
                VrSession.setSelectedIndex(1)
                assertTrue(VrSession.panelOpen)
                VrSession.setPanelOpen(false)
                val dialog = AlertDialog.Builder(activity).setTitle("VR dialog test")
                    .setMessage("Keep this dialog in both eyes")
                    .setPositiveButton(android.R.string.ok, null).create()
                dialog.show()
                VrSession.registerDialog(dialog)
                assertTrue(VrSession.activeDialogDecors(decor).contains(dialog.window!!.decorView))
                assertTrue(dialog.window!!.callback is VrActivityHooks)
                dialog.dismiss()
                VrSession.setEnabled(activity, false)
                assertFalse(VrSession.enabled)
                assertEquals(0, countTheaters(decor))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(VrSession.enabled)
                assertEquals(0, countTheaters(activity.window.decorView))
            }
        } finally {
            instrumentation.runOnMainSync { VrSession.updateSettings(context, original) }
            scenario.close()
        }
    }

    private fun countTheaters(view: View): Int {
        if (view is VrTheaterView) return 1
        if (view !is ViewGroup) return 0
        return (0 until view.childCount).sumOf { countTheaters(view.getChildAt(it)) }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(context.getExternalFilesDir(null), "vr-review").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
