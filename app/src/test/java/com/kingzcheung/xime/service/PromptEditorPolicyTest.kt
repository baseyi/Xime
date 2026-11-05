package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class PromptEditorPolicyTest {
    @Test fun `only explicit send action enables sending`() {
        assertTrue(promptCanSend(EditorInfo.IME_ACTION_SEND))
        for (action in listOf(EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NONE)) {
            assertFalse(promptCanSend(action))
        }
        assertFalse(promptCanSend(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
    }

    @Test fun `deletion preserves surrogate pairs`() {
        assertEquals(1 to 3, promptDeleteRange("中😊文", 3, 3))
    }

    @Test fun `deletion preserves combining characters`() {
        assertEquals(1 to 3, promptDeleteRange("Ae\u0301B", 3, 3))
    }

    @Test fun `reverse selection deletes the selected text`() {
        assertEquals(1 to 4, promptDeleteRange("abcde", 4, 1))
    }

    @Test fun `start of draft and empty draft do not delete`() {
        assertEquals(0 to 0, promptDeleteRange("", 0, 0))
        assertEquals(0 to 0, promptDeleteRange("text", 0, 0))
    }

    @Test fun `multiline long draft deletes only final newline`() {
        val text = "中文 Prompt 😊\n".repeat(1000)
        assertEquals(text.length - 1 to text.length, promptDeleteRange(text, text.length, text.length))
    }
}
