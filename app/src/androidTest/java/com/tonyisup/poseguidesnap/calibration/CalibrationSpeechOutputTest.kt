package com.tonyisup.poseguidesnap.calibration

import android.content.Intent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

/** A single audible check, without CameraX or a participant observation. */
@RunWith(AndroidJUnit4::class)
class CalibrationSpeechOutputTest {
    @Test
    fun installedOfflineVoiceCompletesAudibleCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        var speech: CalibrationSpeechOutput? = null
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(TextView(activity).apply {
                    text = "Audio check only. No camera or posing needed."
                    textSize = 24f
                })
                speech = CalibrationSpeechOutput(activity)
            }
            checkNotNull(speech).initialize()
            checkNotNull(speech).sayAndAwait(
                "Audio check. Spoken guidance is ready. You do not need to pose.",
            )
        } finally {
            instrumentation.runOnMainSync {
                try { speech?.close() } finally { activity.finish() }
            }
        }
    }
}
