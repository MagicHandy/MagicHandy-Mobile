package com.magichandy.mobile.ui.preview

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * Every Compose preview in the debug source set (`ui/preview`), drawn on Robolectric and compared
 * with its recorded image in `src/test/screenshots`. `./gradlew :app:recordRoborazziDebug` records
 * new images after a deliberate change; `check` fails when a screen changes without one.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
internal class PreviewScreenshotTest(private val preview: ComposablePreview<AndroidPreviewInfo>) {
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun matchesItsRecordedImage() {
        val info = preview.previewInfo
        RuntimeEnvironment.setQualifiers("w${info.widthDp}dp-h${info.heightDp}dp-hdpi")
        RuntimeEnvironment.setFontScale(info.fontScale)
        captureRoboImage(
            filePath = "src/test/screenshots/${fileName(preview)}.png",
            // Stray anti-aliasing pixels (under 0.01%) aren't a change; one changed letter is.
            roborazziOptions =
                RoborazziOptions(
                    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0001f)
                ),
        ) {
            preview()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("com.magichandy.mobile.ui.preview")
                .includePrivatePreviews()
                .getPreviews()

        /** The preview's function name, with its parameter's index when it has one. */
        private fun fileName(preview: ComposablePreview<AndroidPreviewInfo>): String =
            preview.methodName + (preview.previewIndex?.let { "_$it" } ?: "")
    }
}
