package com.blinkboard.accessibilitycaptions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionEventBusTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        CaptionEventBus.setCommandMode(false)
        CaptionEventBus.setListening(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSetCommandModeUpdatesStateFlow() {
        assertFalse(CaptionEventBus.isCommandMode.value)
        CaptionEventBus.setCommandMode(true)
        assertTrue(CaptionEventBus.isCommandMode.value)
        CaptionEventBus.setCommandMode(false)
        assertFalse(CaptionEventBus.isCommandMode.value)
    }

    @Test
    fun testSetListeningUpdatesStateFlow() {
        assertFalse(CaptionEventBus.isListening.value)
        CaptionEventBus.setListening(true)
        assertTrue(CaptionEventBus.isListening.value)
        CaptionEventBus.setListening(false)
        assertFalse(CaptionEventBus.isListening.value)
    }

    @Test
    fun testEmitSpokenTextInCaptionMode() = runTest {
        CaptionEventBus.setCommandMode(false)
        val emittedEvents = mutableListOf<CaptionEvent>()
        val job = launch(testDispatcher) {
            CaptionEventBus.events.collect { emittedEvents.add(it) }
        }

        CaptionEventBus.emitSpokenText("Hello world", isFinal = false)
        CaptionEventBus.emitSpokenText("Hello world final", isFinal = true)

        assertEquals(2, emittedEvents.size)
        assertTrue(emittedEvents[0] is CaptionEvent.CaptionUpdate)
        assertEquals("Hello world", (emittedEvents[0] as CaptionEvent.CaptionUpdate).text)
        assertTrue(emittedEvents[1] is CaptionEvent.CaptionUpdate)
        assertEquals("Hello world final", (emittedEvents[1] as CaptionEvent.CaptionUpdate).text)

        job.cancel()
    }

    @Test
    fun testEmitSpokenTextInCommandModePartial() = runTest {
        CaptionEventBus.setCommandMode(true)
        val emittedEvents = mutableListOf<CaptionEvent>()
        val job = launch(testDispatcher) {
            CaptionEventBus.events.collect { emittedEvents.add(it) }
        }

        CaptionEventBus.emitSpokenText("click submit", isFinal = false)

        assertEquals(1, emittedEvents.size)
        assertTrue(emittedEvents[0] is CaptionEvent.CaptionUpdate)
        assertEquals("🎤 click submit...", (emittedEvents[0] as CaptionEvent.CaptionUpdate).text)

        job.cancel()
    }

    @Test
    fun testEmitSpokenTextInCommandModeFinal() = runTest {
        CaptionEventBus.setCommandMode(true)
        val emittedEvents = mutableListOf<CaptionEvent>()
        val job = launch(testDispatcher) {
            CaptionEventBus.events.collect { emittedEvents.add(it) }
        }

        CaptionEventBus.emitSpokenText("click submit", isFinal = true)

        assertEquals(1, emittedEvents.size)
        assertTrue(emittedEvents[0] is CaptionEvent.VoiceCommand)
        assertEquals("click submit", (emittedEvents[0] as CaptionEvent.VoiceCommand).command)

        job.cancel()
    }

    @Test
    fun testEmitBlankTextIsIgnored() = runTest {
        val emittedEvents = mutableListOf<CaptionEvent>()
        val job = launch(testDispatcher) {
            CaptionEventBus.events.collect { emittedEvents.add(it) }
        }

        CaptionEventBus.emitSpokenText("", isFinal = true)
        CaptionEventBus.emitSpokenText("   ", isFinal = true)
        CaptionEventBus.emitCaptionUpdate("")

        assertEquals(0, emittedEvents.size)

        job.cancel()
    }

    @Test
    fun testDirectEmitCaptionUpdateAndVoiceCommand() = runTest {
        val emittedEvents = mutableListOf<CaptionEvent>()
        val job = launch(testDispatcher) {
            CaptionEventBus.events.collect { emittedEvents.add(it) }
        }

        CaptionEventBus.emitCaptionUpdate("System alert")
        CaptionEventBus.emitVoiceCommand("open camera")

        assertEquals(2, emittedEvents.size)
        assertEquals("System alert", (emittedEvents[0] as CaptionEvent.CaptionUpdate).text)
        assertEquals("open camera", (emittedEvents[1] as CaptionEvent.VoiceCommand).command)

        job.cancel()
    }
}
