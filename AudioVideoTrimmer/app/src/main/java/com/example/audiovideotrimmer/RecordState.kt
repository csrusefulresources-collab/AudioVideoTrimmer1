package com.example.audiovideotrimmer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class RecordKind { AUDIO, VIDEO, SCREEN }
enum class Phase { IDLE, COUNTDOWN, RECORDING, PAUSED }

data class RecState(
    val kind: RecordKind? = null,
    val phase: Phase = Phase.IDLE,
    val elapsedMs: Long = 0L,
    val countdown: Int = 0,
    val level: Float = 0f
)

/** Shared between the recording services, the camera recorder and the Record screen. */
object RecordState {
    val state = MutableStateFlow(RecState())

    /** Short messages that the UI shows and speaks ("Saved…", "Noise reduction is not available…"). */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val isBusy: Boolean get() = state.value.phase != Phase.IDLE

    fun post(message: String) {
        events.tryEmit(message)
    }
}
