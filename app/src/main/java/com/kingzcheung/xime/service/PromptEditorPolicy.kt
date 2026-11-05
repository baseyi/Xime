package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo

internal fun promptCanSend(options: Int): Boolean =
    options and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND &&
        options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0

internal fun promptDeleteRange(text: String, selectionStart: Int, selectionEnd: Int): Pair<Int, Int> {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
    if (start != end || start == 0) return start to end
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    return iterator.preceding(start).coerceAtLeast(0) to end
}
