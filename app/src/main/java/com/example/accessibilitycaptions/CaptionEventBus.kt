package com.example.accessibilitycaptions

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface CaptionEvent {
    data class CaptionUpdate(val text: String) : CaptionEvent
    data class VoiceCommand(val command: String) : CaptionEvent
}

/**
 * Thread-safe, application-wide in-memory event bus that decouples speech recognition,
 * AccessibilityService overlays, and background services without dynamic global broadcasts.
 */
object CaptionEventBus {
    private val _events = MutableSharedFlow<CaptionEvent>(
        extraBufferCapacity = 64
    )
    val events: SharedFlow<CaptionEvent> = _events.asSharedFlow()

    fun emitCaptionUpdate(text: String) {
        _events.tryEmit(CaptionEvent.CaptionUpdate(text))
    }

    fun emitVoiceCommand(command: String) {
        _events.tryEmit(CaptionEvent.VoiceCommand(command))
    }
}
