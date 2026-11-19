package com.limelight.vr

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class VrPreferenceKeyboardContractTest {
    @Test
    fun dpadCanChangeEveryPersistedVrSliderByItsDeclaredStep() {
        val xml = listOf(File("src/main/res/xml/preferences.xml"), File("app/src/main/res/xml/preferences.xml"))
            .first(File::isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val entries = factory.newDocumentBuilder().parse(xml)
            .getElementsByTagName("com.limelight.preferences.SeekBarPreference")
        val sliders = (0 until entries.length).map { entries.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID, "key").startsWith("vr_") }
            .associateBy { it.getAttributeNS(ANDROID, "key") }
        val ranges = VrQuickPanel.rows.filter { it.kind == VrQuickPanel.Kind.RANGE }
        assertEquals(ranges.map { it.key }.toSet(), sliders.keys)

        ranges.forEach { row ->
            val slider = sliders.getValue(row.key)
            val step = slider.getAttributeNS(SEEK, "step").toInt()
            val keyStep = slider.getAttributeNS(SEEK, "keyStep").toInt()
            val divisor = slider.getAttributeNS(SEEK, "divisor").toIntOrNull() ?: 1
            val min = slider.getAttributeNS(SEEK, "min").toInt()
            val max = slider.getAttributeNS(ANDROID, "max").toInt()
            assertTrue(row.key + " keyboard input must survive slider snapping", keyStep >= step)
            assertEquals(row.key + " XML and VR panel use the same persisted units", step,
                (row.step * divisor).roundToInt())

            for (persisted in min..max step step) {
                for (direction in listOf(-1, 1)) {
                    // Android changes raw progress first; the shared dialog then snaps to its step.
                    val progress = (persisted + direction * keyStep).coerceIn(min, max)
                    val snapped = ((progress.toFloat() / step).roundToInt() * step).coerceIn(min, max)
                    val current = VrQuickPanel.withValue(VrSettings.DEFAULT, row.key, persisted.toFloat() / divisor)
                    val adjusted = VrQuickPanel.adjust(row, current, direction)
                    val expected = (VrQuickPanel.valueOf(adjusted, row.key) * divisor).roundToInt()
                    assertEquals(row.key + " at " + persisted + " direction " + direction, expected, snapped)
                }
            }
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val SEEK = "http://schemas.moonlight-stream.com/apk/res/seekbar"
    }
}
