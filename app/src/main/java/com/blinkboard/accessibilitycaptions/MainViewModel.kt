package com.blinkboard.accessibilitycaptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    init {
        viewModelScope.launch {
            CaptionEventBus.isCommandMode.collect { isCommand ->
                _uiState.update { it.copy(isCommandMode = isCommand) }
            }
        }
        viewModelScope.launch {
            CaptionEventBus.isListening.collect { listening ->
                _uiState.update {
                    it.copy(
                        isListening = listening,
                        speechErrorCount = if (listening) 0 else it.speechErrorCount
                    )
                }
            }
        }
    }

    fun setCommandMode(isCommand: Boolean) {
        CaptionEventBus.setCommandMode(isCommand)
    }

    fun setListening(listening: Boolean) {
        CaptionEventBus.setListening(listening)
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
}
