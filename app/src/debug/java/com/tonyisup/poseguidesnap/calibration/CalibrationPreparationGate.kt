package com.tonyisup.poseguidesnap.calibration

/** The only successful exit is fresh READY evidence; elapsed time never authorizes a hold. */
internal class CalibrationPreparationGate(
    private val elapsedRealtimeMs: () -> Long,
    private val sleepMs: (Long) -> Unit,
    private val decision: (Long) -> CalibrationPreparationDecision,
) {
    fun awaitReady(onTimeout: () -> Unit) {
        while (true) {
            when (decision(elapsedRealtimeMs())) {
                CalibrationPreparationDecision.READY -> return
                CalibrationPreparationDecision.TIMED_OUT -> {
                    onTimeout()
                    error("Preparation timed out without settled, resolved adjustments")
                }
                CalibrationPreparationDecision.WAITING -> sleepMs(100L)
            }
        }
    }

    companion object {
        const val TIMEOUT_MESSAGE =
            "I could not confirm a settled position and the adjustments in time. Test stopped. You can relax."
    }
}
