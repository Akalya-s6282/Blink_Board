package com.blinkboard.accessibilitycaptions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProcessorTest {

    private val processor = CommandProcessor()

    // --- Levenshtein Distance Edge Cases ---

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
    fun testLevenshteinEmptyStrings() {
        assertEquals(0, processor.levenshtein("", ""))
        assertEquals(5, processor.levenshtein("hello", ""))
        assertEquals(5, processor.levenshtein("", "hello"))
    }

    @Test
    fun testLevenshteinCaseSensitivity() {
        assertEquals(0, processor.levenshtein("Submit".lowercase(), "submit".lowercase()))
    }

    // --- Click / Tap / Press Command Target Validation ---

    @Test
    fun testProcessClickWithoutTarget() = runBlocking {
        val result = processor.processCommand("click", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify what to click.", toast.message)
    }

    @Test
    fun testProcessPressWithoutTarget() = runBlocking {
        val result = processor.processCommand("press   ", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify what to click.", toast.message)
    }

    @Test
    fun testProcessTapWithoutTarget() = runBlocking {
        val result = processor.processCommand("tap", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify what to click.", toast.message)
    }

    @Test
    fun testProcessClickCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("click submit", null)
        assertTrue(result is CommandResult.UpdateCaption)
        val caption = result as CommandResult.UpdateCaption
        assertTrue(caption.text.contains("No active window found"))
    }

    // --- Scroll Commands Edge Cases ---

    @Test
    fun testProcessScrollUpWithoutRoot() = runBlocking {
        val result = processor.processCommand("scroll up", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No scrollable area found on screen.", action.failureMessage)
    }

    @Test
    fun testProcessScrollDownWithoutRoot() = runBlocking {
        val result = processor.processCommand("scroll down", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No scrollable area found on screen.", action.failureMessage)
    }

    // --- Type Command Edge Cases ---

    @Test
    fun testProcessTypeWithoutTargetText() = runBlocking {
        val result = processor.processCommand("type", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify text to type.", toast.message)
    }

    @Test
    fun testProcessTypeCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("type hello world", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No focused editable input field found. Tap an input field first.", action.failureMessage)
    }

    // --- Navigation & App Commands Edge Cases ---

    @Test
    fun testProcessGoBackCommand() = runBlocking {
        val result = processor.processCommand("go back", null)
        assertTrue(result is CommandResult.GoBack)
    }

    @Test
    fun testProcessOpenWithoutAppName() = runBlocking {
        val result = processor.processCommand("open", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify an app to open.", toast.message)
    }

    @Test
    fun testProcessOpenSettings() = runBlocking {
        val result = processor.processCommand("open settings", null)
        assertTrue(result is CommandResult.ShowToast || result is CommandResult.UpdateCaption)
    }

    @Test
    fun testProcessOpenUnknownApp() = runBlocking {
        val result = processor.processCommand("open non_existent_app_xyz_123", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertTrue(toast.message == "App 'non_existent_app_xyz_123' not found." || toast.message == "Context unavailable.")
    }

    // --- Call & Element Discovery Commands ---

    @Test
    fun testProcessEndCallWithoutRoot() = runBlocking {
        val result = processor.processCommand("end call", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("End call button not found.", toast.message)
    }

    @Test
    fun testProcessShowElementsWithoutRoot() = runBlocking {
        val result = processor.processCommand("show elements", null)
        assertTrue(result is CommandResult.UpdateCaption)
        val caption = result as CommandResult.UpdateCaption
        assertEquals("No interactive items found.", caption.text)
    }

    // --- Confirmation State Machine Edge Cases ---

    @Test
    fun testProcessConfirmWithoutPendingTarget() = runBlocking {
        val resultConfirm = processor.processCommand("confirm", null)
        assertTrue(resultConfirm is CommandResult.Handled)

        val resultYes = processor.processCommand("yes", null)
        assertTrue(resultYes is CommandResult.Handled)

        val resultOk = processor.processCommand("ok", null)
        assertTrue(resultOk is CommandResult.Handled)
    }

    // --- Unrecognized / Unknown Input ---

    @Test
    fun testProcessUnrecognizedCommand() = runBlocking {
        val result = processor.processCommand("hello world random text", null)
        assertTrue(result is CommandResult.Handled)
    }

    // --- Node Helper Method Edge Cases ---

    @Test
    fun testFindInteractiveNodesNullRoot() {
        val nodes = processor.findInteractiveNodes(null)
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun testFindMatchingNodeNullRoot() {
        val node = processor.findMatchingNode(null, "submit")
        assertNull(node)
    }

    // --- Target Normalization & Filler Word Stripping Tests ---

    @Test
    fun testCleanTargetFillerWords() {
        assertEquals("submit", processor.cleanTarget("on the submit button"))
        assertEquals("search", processor.cleanTarget("click on the search icon"))
        assertEquals("login", processor.cleanTarget("please tap on a login field"))
    }

    @Test
    fun testLevenshteinAllocFreeDistance() {
        assertEquals(0, processor.levenshtein("Camera", "camera"))
        assertEquals(1, processor.levenshtein("settins", "settings"))
        assertEquals(2, processor.levenshtein("submti", "submit"))
    }

    // --- UX Enhancements: Numbers, Synonyms & Global Gestures ---

    @Test
    fun testProcessPureNumberVoiceTriggers() = runBlocking {
        val resultOne = processor.processCommand("1", null)
        assertTrue(resultOne is CommandResult.ClickBadgeNumber)
        assertEquals(1, (resultOne as CommandResult.ClickBadgeNumber).number)

        val resultFive = processor.processCommand("5", null)
        assertTrue(resultFive is CommandResult.ClickBadgeNumber)
        assertEquals(5, (resultFive as CommandResult.ClickBadgeNumber).number)
    }

    @Test
    fun testParseNumberFromSpeechWordNumbers() = runBlocking {
        assertEquals(1, processor.parseNumberFromSpeech("one"))
        assertEquals(2, processor.parseNumberFromSpeech("two"))
        assertEquals(3, processor.parseNumberFromSpeech("three"))
        assertEquals(4, processor.parseNumberFromSpeech("number four"))
        assertEquals(5, processor.parseNumberFromSpeech("click 5"))
        assertEquals(6, processor.parseNumberFromSpeech("tap six"))

        val resultOne = processor.processCommand("one", null)
        assertTrue(resultOne is CommandResult.ClickBadgeNumber)
        assertEquals(1, (resultOne as CommandResult.ClickBadgeNumber).number)

        val resultTwo = processor.processCommand("number two", null)
        assertTrue(resultTwo is CommandResult.ClickBadgeNumber)
        assertEquals(2, (resultTwo as CommandResult.ClickBadgeNumber).number)
    }

    @Test
    fun testProcessShowNumbersAndBadges() = runBlocking {
        val result = processor.processCommand("show numbers", null)
        assertTrue(result is CommandResult.ShowNumbers)

        val resultBadges = processor.processCommand("show badges", null)
        assertTrue(resultBadges is CommandResult.ShowNumbers)
    }

    @Test
    fun testProcessGlobalSystemGesturesSynonyms() = runBlocking {
        assertTrue(processor.processCommand("home", null) is CommandResult.GoHome)
        assertTrue(processor.processCommand("go home", null) is CommandResult.GoHome)
        assertTrue(processor.processCommand("recents", null) is CommandResult.OpenRecents)
        assertTrue(processor.processCommand("notifications", null) is CommandResult.OpenNotifications)
        assertTrue(processor.processCommand("screenshot", null) is CommandResult.TakeScreenshot)
        assertTrue(processor.processCommand("take screenshot", null) is CommandResult.TakeScreenshot)
        assertTrue(processor.processCommand("back", null) is CommandResult.GoBack)
    }
}
