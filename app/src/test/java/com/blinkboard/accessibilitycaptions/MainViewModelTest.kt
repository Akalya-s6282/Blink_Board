package com.blinkboard.accessibilitycaptions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        CaptionEventBus.setCommandMode(false)
        CaptionEventBus.setListening(false)
        viewModel = MainViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialUiState() {
        val state = viewModel.uiState.value
        assertFalse(state.isCommandMode)
        assertFalse(state.isListening)
        assertFalse(state.isAccessibilityEnabled)
        assertFalse(state.isOverlayGranted)
        assertFalse(state.isAudioPermissionGranted)
        assertFalse(state.showDisclosureDialog)
        assertEquals(0, state.speechErrorCount)
    }

    @Test
    fun testUpdatePermissions() {
        viewModel.updatePermissions(
            accessibilityEnabled = true,
            overlayGranted = true,
            audioGranted = true
        )
        val state = viewModel.uiState.value
        assertTrue(state.isAccessibilityEnabled)
        assertTrue(state.isOverlayGranted)
        assertTrue(state.isAudioPermissionGranted)
    }

    @Test
    fun testSetCommandModeUpdatesViewModelState() {
        assertFalse(viewModel.uiState.value.isCommandMode)
        viewModel.setCommandMode(true)
        assertTrue(viewModel.uiState.value.isCommandMode)
        viewModel.setCommandMode(false)
        assertFalse(viewModel.uiState.value.isCommandMode)
    }

    @Test
    fun testSetListeningUpdatesViewModelState() {
        assertFalse(viewModel.uiState.value.isListening)
        viewModel.setListening(true)
        assertTrue(viewModel.uiState.value.isListening)
        viewModel.setListening(false)
        assertFalse(viewModel.uiState.value.isListening)
    }

    @Test
    fun testSetShowDisclosureDialog() {
        assertFalse(viewModel.uiState.value.showDisclosureDialog)
        viewModel.setShowDisclosureDialog(true)
        assertTrue(viewModel.uiState.value.showDisclosureDialog)
        viewModel.setShowDisclosureDialog(false)
        assertFalse(viewModel.uiState.value.showDisclosureDialog)
    }
}
