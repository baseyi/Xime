package com.kingzcheung.xime.service

/** 仅处理选词后的中文确认段；剩余仍有中文候选时保留正常分段转换。 */
internal fun confirmedPrefixWithoutUnmatchedTail(
    preedit: String,
    selected: String?,
    remainingCandidates: List<String>,
): String? {
    if (selected.isNullOrEmpty() || selected.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
    if (remainingCandidates.any { text -> text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }) return null
    val match = Regex("^([\\p{IsHan}]+)\\s*([a-zA-Z][a-zA-Z' ]*)$").matchEntire(preedit) ?: return null
    val confirmed = match.groupValues[1]
    return confirmed.takeIf { it.endsWith(selected) }
}
