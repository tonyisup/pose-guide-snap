package com.tonyisup.poseguidesnap.domain.model

enum class BodySide {
    LEFT,
    RIGHT,
}

enum class CoachingJoint {
    SHOULDER,
    ELBOW,
    WRIST,
    HIP,
    KNEE,
    ANKLE,
}

enum class CoachingDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    FORWARD,
    BACKWARD,
}

/** Fixed semantic coaching vocabulary; adapters own user-facing wording and speech. */
sealed interface CoachingCue {
    data class MoveJoint(
        val joint: CoachingJoint,
        val side: BodySide,
        val direction: CoachingDirection,
    ) : CoachingCue

    data class TurnShoulders(val direction: CoachingDirection) : CoachingCue {
        init {
            require(direction == CoachingDirection.LEFT || direction == CoachingDirection.RIGHT) {
                "shoulders may only turn left or right"
            }
        }
    }

    data class LeanTorso(val direction: CoachingDirection) : CoachingCue {
        init {
            require(direction == CoachingDirection.LEFT || direction == CoachingDirection.RIGHT) {
                "torso may only lean left or right"
            }
        }
    }

    data object CenterInFrame : CoachingCue
    data object IncludeFullBody : CoachingCue
    data object MoveCloser : CoachingCue
    data object MoveFartherAway : CoachingCue
    data object HoldStill : CoachingCue
    data object PoseMatched : CoachingCue
    data object CaptureStarting : CoachingCue
    data object NextPose : CoachingCue
    data object Paused : CoachingCue
    data object ShootComplete : CoachingCue
}
