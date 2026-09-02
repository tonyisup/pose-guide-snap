package com.tonyisup.poseguidesnap.ui.session

import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle

internal sealed interface StartedSessionBootstrapState {
    data object Loading : StartedSessionBootstrapState {
        override fun toString(): String = "StartedSessionBootstrapState.Loading"
    }

    data class Ready(val snapshot: GuidedSessionSnapshot) : StartedSessionBootstrapState {
        init {
            require(snapshot.lifecycle == GuidedSessionLifecycle.ACTIVE)
            require(snapshot.blockingAttempt == null)
        }

        override fun toString(): String = "StartedSessionBootstrapState.Ready(redacted)"
    }

    data object Completed : StartedSessionBootstrapState {
        override fun toString(): String = "StartedSessionBootstrapState.Completed"
    }

    data object ReconciliationRequired : StartedSessionBootstrapState {
        override fun toString(): String =
            "StartedSessionBootstrapState.ReconciliationRequired"
    }

    data object Missing : StartedSessionBootstrapState {
        override fun toString(): String = "StartedSessionBootstrapState.Missing"
    }

    data class Unavailable(val canRetry: Boolean) : StartedSessionBootstrapState {
        override fun toString(): String =
            "StartedSessionBootstrapState.Unavailable(canRetry=$canRetry)"
    }
}

internal fun interface StartedSessionBootstrapRepositoryPort {
    fun loadGuidedSessionBootstrap(sessionId: String): GuidedSessionBootstrapResult
}

internal fun interface StartedSessionBootstrapWorkflowPort {
    suspend fun load(handle: StartedSessionHandle): StartedSessionBootstrapState
}

internal interface OwnedStartedSessionBootstrapWorkflow : StartedSessionBootstrapWorkflowPort {
    fun close()
}
