package com.blinkboard.accessibilitycaptions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProcessorTest {

    private val processor = CommandProcessor()

    @Test
    fun testLevenshteinExactMatch() {
        val distance = processor.levenshtein("submit", "submit")
        assertEquals(0, distance)
    }

    @Test
    fun testLevenshteinOneEdit() {
        val distance = processor.levenshtein("submit", "submi")
        assertEquals(1, distance)
    }

    @Test
    fun testLevenshteinTypo() {
        val distance = processor.levenshtein("submit", "sumbit")
        assertEquals(2, distance)
    }

    @Test
    fun testProcessClickCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("click submit", null)
        assertTrue(result is CommandResult.UpdateCaption)
        val caption = result as CommandResult.UpdateCaption
        assertTrue(caption.text.contains("No active window found"))
    }

    @Test
    fun testProcessClickWithoutTarget() = runBlocking {
        val result = processor.processCommand("click", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify what to click.", toast.message)
    }

    @Test
    fun testProcessScrollCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("scroll up", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No scrollable area found on screen.", action.failureMessage)
    }

    @Test
    fun testProcessTypeCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("type hello world", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No focused editable input field found. Tap an input field first.", action.failureMessage)
    }

    @Test
    fun testProcessGoBackCommand() = runBlocking {
        val result = processor.processCommand("go back", null)
        assertTrue(result is CommandResult.GoBack)
    }

    @Test
    fun testProcessConfirmWithoutPendingTarget() = runBlocking {
        val result = processor.processCommand("confirm", null)
        assertTrue(result is CommandResult.Handled)
    }

    @Test
    fun testProcessOpenSettings() = runBlocking {
        val result = processor.processCommand("open settings", null)
        assertTrue(result is CommandResult.ShowToast || result is CommandResult.UpdateCaption)
    }
}
