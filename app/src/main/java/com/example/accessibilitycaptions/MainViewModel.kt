package com.example.accessibilitycaptions

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MainUiState(
    val isCommandMode: Boolean = false,
    val isListening: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isAudioPermissionGranted: Boolean = false,
    val showDisclosureDialog: Boolean = false,
    val speechErrorCount: Int = 0
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun setCommandMode(isCommand: Boolean) {
        _uiState.update { it.copy(isCommandMode = isCommand) }
    }

    fun setListening(listening: Boolean) {
        _uiState.update {
            it.copy(
                isListening = listening,
                speechErrorCount = if (listening) 0 else it.speechErrorCount
            )
        }
    }

    fun updatePermissions(
        accessibilityEnabled: Boolean,
        overlayGranted: Boolean,
        audioGranted: Boolean
    ) {
        _uiState.update {
            it.copy(
                isAccessibilityEnabled = accessibilityEnabled,
                isOverlayGranted = overlayGranted,
                isAudioPermissionGranted = audioGranted
            )
        }
    }

    fun setShowDisclosureDialog(show: Boolean) {
        _uiState.update { it.copy(showDisclosureDialog = show) }
    }

    fun handleSpeechError(): Boolean {
        var shouldRetry = true
        _uiState.update {
            val newCount = it.speechErrorCount + 1
            if (newCount >= 3) {
                shouldRetry = false
                it.copy(speechErrorCount = newCount, isListening = false)
            } else {
                it.copy(speechErrorCount = newCount)
            }
        }
        return shouldRetry
    }

    fun onSpokenTextRecognized(text: String) {
        if (text.isNotBlank()) {
            val isCommand = _uiState.value.isCommandMode
            if (isCommand) {
                CaptionEventBus.emitVoiceCommand(text)
            } else {
                CaptionEventBus.emitCaptionUpdate(text)
            }
        }
    }
}
