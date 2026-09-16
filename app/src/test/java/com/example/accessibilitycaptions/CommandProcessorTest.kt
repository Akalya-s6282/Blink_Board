package com.example.accessibilitycaptions

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
        assertTrue(result is CommandResult.ShowToast)
    }

    @Test
    fun testProcessScrollCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("scroll up", null)
        assertTrue(result is CommandResult.PerformAction)
    }

    @Test
    fun testProcessTypeCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("type hello world", null)
        assertTrue(result is CommandResult.PerformAction)
    }
}
