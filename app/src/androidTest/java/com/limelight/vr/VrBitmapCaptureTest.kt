package com.limelight.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.grid.assets.CachedAppAssetLoader
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VrBitmapCaptureTest {
    @Test
    fun cachedArtLoadedBeforeVrCanBeDrawnIntoTheWindowCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loader = DiskAssetLoader(context)
        val computer = ComputerDetails().apply { uuid = UUID.randomUUID().toString() }
        val tuple = CachedAppAssetLoader.LoaderTuple(computer, NvApp("VR bitmap test", 1, false))
        val path = requireNotNull(loader.getFile(requireNotNull(computer.uuid), tuple.app.appId))
        val input = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.rgb(80, 160, 240))
        path.parentFile?.mkdirs()
        try {
            path.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = requireNotNull(loader.loadBitmapFromCache(tuple, 1)?.bitmap)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    assertFalse(decoded.config == Bitmap.Config.HARDWARE)
                }
                instrumentation.runOnMainSync {
                    val image = ImageView(context).apply {
                        setImageBitmap(decoded)
                        layout(0, 0, 64, 64)
                    }
                    val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    try {
                        image.draw(Canvas(frame))
                        assertTrue(Color.alpha(frame.getPixel(32, 32)) > 0)
                    } finally {
                        frame.recycle()
                    }
                }
            } finally {
                decoded.recycle()
            }
        } finally {
            input.recycle()
            path.delete()
            path.parentFile?.delete()
        }
    }
}
