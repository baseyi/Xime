package com.kingzcheung.xime.service

import org.junit.Assert.*
import org.junit.Test

class ConfirmedPrefixCommitTest {
    @Test fun unmatchedEnglishTailCommitsConfirmedChinese() {
        assertEquals("我", confirmedPrefixWithoutUnmatchedTail("我ime", "我", emptyList()))
        assertEquals("我", confirmedPrefixWithoutUnmatchedTail("我ime", "我", listOf("ime")))
    }
    @Test fun validRemainingChineseKeepsComposition() {
        assertNull(confirmedPrefixWithoutUnmatchedTail("我jin tian", "我", listOf("今天")))
    }
    @Test fun unconfirmedInputIsNeverGuessed() {
        assertNull(confirmedPrefixWithoutUnmatchedTail("woime", "我", emptyList()))
        assertNull(confirmedPrefixWithoutUnmatchedTail("我ime", "你", emptyList()))
        assertNull(confirmedPrefixWithoutUnmatchedTail("Helloime", "Hello", emptyList()))
    }
}
