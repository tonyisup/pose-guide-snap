package com.tonyisup.poseguidesnap.calibration

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic rendering only. No activity, camera, private observation or output file is used. */
@RunWith(AndroidJUnit4::class)
class CalibrationGuideScreenTest {
    @Test
    fun previewKeepsReferenceAspectAndStatusDoesNotCoverIt() = onMain {
        for ((width, height) in listOf(1080 to 2200, 2200 to 1080)) {
            val screen = laidOutScreen(width, height)
            try {
                val previewBounds = Rect(0, 0, screen.preview.width, screen.preview.height)
                screen.root.offsetDescendantRectToMyCoords(screen.preview, previewBounds)
                val statusBounds = Rect(0, 0, screen.status.width, screen.status.height)
                screen.root.offsetDescendantRectToMyCoords(screen.status, statusBounds)
                assertTrue(previewBounds.bottom <= statusBounds.top)
                assertTrue(previewBounds.top >= 0 && previewBounds.right <= width)
                assertTrue(statusBounds.bottom <= height)
                assertTrue(CalibrationAlignmentGuide().compatibleAspect(
                    PoseImageSize(screen.preview.width, screen.preview.height),
                    BundledMeditationReference.observation.imageSize,
                ))
                // Exercise actual Canvas drawing with public synthetic evidence; never export bitmap.
                assertTrue(screen.update(BundledMeditationReference.observation, 15))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { screen.root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            } finally { screen.close() }
        }
    }

    @Test
    fun measurementClearsAdjustmentAdviceAndLateFramesCannotReplaceHoldInstruction() = onMain {
        val screen = laidOutScreen(1080, 2200)
        try {
            screen.update(BundledMeditationReference.observation, 15)
            assertTrue(screen.status.text.contains("Framing aligned"))
            screen.finishWarmup()
            screen.status.text = "Hold the meditation pose."
            screen.update(BundledMeditationReference.observation, 0)
            assertEquals("Hold the meditation pose.", screen.status.text.toString())
        } finally { screen.close() }
    }

    private fun laidOutScreen(width: Int, height: Int): CalibrationGuideScreen =
        CalibrationGuideScreen(InstrumentationRegistry.getInstrumentation().targetContext).also {
            it.root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            it.root.layout(0, 0, width, height)
        }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
