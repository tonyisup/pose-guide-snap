package com.tonyisup.poseguidesnap.ui.camera

import android.content.Context
import android.os.SystemClock
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot

internal fun createGuidedCameraViewModel(
    applicationContext: Context,
    snapshot: GuidedSessionSnapshot,
): GuidedCameraViewModel {
    val workflow = createRoomGuidedCaptureWorkflow(applicationContext)
    return try {
        GuidedCameraViewModel(
            snapshot = snapshot,
            workflow = workflow,
            eventClockNanos = SystemClock::elapsedRealtimeNanos,
        )
    } catch (failure: Throwable) {
        try {
            workflow.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}
