package com.limelight.utils

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source barriers stay testable without invoking Android networking or installers on the JVM. */
class UpstreamUpdateChannelContractTest {
    private val manager = appFile("src/main/java/com/limelight/utils/UpdateManager.kt").readText()

    @Test
    fun reviewBuildAloneDisablesTheUpstreamUpdateChannel() {
        assertTrue(appFile("build.gradle").readText().contains(
            "buildConfigField \"boolean\", \"UPSTREAM_UPDATES_ENABLED\", (!vrReviewBuild).toString()"
        ))
    }

    @Test
    fun automaticAndManualChecksShareAGateBeforeAnyScheduling() {
        assertGateBefore("private fun enqueueUpdateCheck(", "isChecking.getAndSet(true)")
        val entry = manager.substringAfter("private fun enqueueUpdateCheck(")
            .substringBefore("if (isChecking.getAndSet(true))")
        assertTrue(entry.contains("if (showToast)"))
        assertTrue(entry.contains("R.string.toast_vr_review_updates_disabled"))
        assertTrue(entry.contains("return"))
        assertTrue(manager.contains("enqueueUpdateCheck(context, showToast, enforceStartupInterval = false)"))
        assertTrue(manager.contains("enqueueUpdateCheck(context, showToast = false, enforceStartupInterval = true)"))
    }

    @Test
    fun ignoredDownloadCompletionStillFinishesBeforeAccessingProviders() {
        assertGateBefore("fun onDownloadComplete(", "val appContext = context.applicationContext")
        val entry = manager.substringAfter("fun onDownloadComplete(")
            .substringBefore("val appContext = context.applicationContext")
        assertTrue(entry.contains("onFinished?.invoke()"))
        assertTrue(entry.indexOf("onFinished?.invoke()") < entry.indexOf("return"))
    }

    @Test
    fun permissionReturnAndFinalDownloadInstallPathsRemainGated() {
        assertGateBefore("fun onInstallPermissionResult(", "canInstallApk(context)")
        val permission = manager.substringAfter("fun onInstallPermissionResult(")
            .substringBefore("if (pendingUpdateInfo != null")
        assertTrue(permission.contains("pendingUpdateInfo = null"))
        assertTrue(permission.contains("return"))
        assertGateBefore("private fun startDirectDownload(", "try {")
        assertGateBefore("private fun installApk(", "try {")
    }

    private fun assertGateBefore(function: String, effect: String) {
        assertTrue("Missing entry point: $function", manager.contains(function))
        val body = manager.substringAfter(function)
        val gate = body.indexOf("if (!BuildConfig.UPSTREAM_UPDATES_ENABLED)")
        val firstEffect = body.indexOf(effect)
        assertTrue("$function must gate before $effect", gate >= 0 && firstEffect > gate)
    }

    private fun appFile(path: String): File = listOf(File("app/$path"), File(path))
        .first(File::isFile)
}
