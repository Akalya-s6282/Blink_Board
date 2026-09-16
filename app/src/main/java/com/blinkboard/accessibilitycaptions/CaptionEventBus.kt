package com.blinkboard.accessibilitycaptions

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _isCommandMode = MutableStateFlow(false)
    val isCommandMode: StateFlow<Boolean> = _isCommandMode.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun setCommandMode(enabled: Boolean) {
        _isCommandMode.value = enabled
    }

    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun emitSpokenText(text: String, isFinal: Boolean = true) {
        if (text.isNotBlank()) {
            if (_isCommandMode.value) {
                if (isFinal) {
                    emitVoiceCommand(text)
                } else {
                    emitCaptionUpdate("🎤 $text...")
                }
            } else {
                emitCaptionUpdate(text)
            }
        }
    }

    fun emitCaptionUpdate(text: String) {
        _events.tryEmit(CaptionEvent.CaptionUpdate(text))
    }

    fun emitVoiceCommand(command: String) {
        _events.tryEmit(CaptionEvent.VoiceCommand(command))
    }
}
