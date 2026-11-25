package com.magichandy.mobile.voicelab

import java.util.Locale

/** How one word of a transcript compares with what was read out. */
enum class Mark {
    Right,

    /** A word in place of the one read out. */
    Wrong,

    /** A word that wasn't read out. */
    Extra,

    /** A word read out that the transcript lacks. */
    Missing,
}

/** One word or character to show, with its mark. */
data class MarkedWord(val text: String, val mark: Mark)

/**
 * A transcript scored against what was read out: [errors] out of [words], and the transcript's
 * words with the missing ones put back in place, for display.
 */
data class WordScore(val errors: Int, val words: Int, val marks: List<MarkedWord>)

/**
 * Scores [transcript] against [reference], the words read out. Case and punctuation don't count.
 * Japanese and Chinese have no spaces between words, so they're scored per character.
 */
fun scoreTranscript(reference: String, transcript: String): WordScore {
    val perCharacter = reference.any(::isCjk) || transcript.any(::isCjk)
    val said = tokens(reference, perCharacter)
    val heard = tokens(transcript, perCharacter)
    val keysSaid = said.map(::key)
    val keysHeard = heard.map(::key)
    // Edit distance over words, then walk back to mark each one.
    val rows = keysSaid.size + 1
    val columns = keysHeard.size + 1
    val cost = Array(rows) { IntArray(columns) }
    for (i in 0 until rows) cost[i][0] = i
    for (j in 0 until columns) cost[0][j] = j
    for (i in 1 until rows) {
        for (j in 1 until columns) {
            val substitute = cost[i - 1][j - 1] + if (keysSaid[i - 1] == keysHeard[j - 1]) 0 else 1
            cost[i][j] = minOf(substitute, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
        }
    }
    val marks = ArrayDeque<MarkedWord>()
    var i = rows - 1
    var j = columns - 1
    // Among equally short alignments, keep matching words matched: a missing word and an extra one
    // read better than a run of wrong words shifted by one.
    while (i > 0 || j > 0) {
        val same = i > 0 && j > 0 && keysSaid[i - 1] == keysHeard[j - 1]
        when {
            same && cost[i][j] == cost[i - 1][j - 1] -> {
                marks.addFirst(MarkedWord(heard[j - 1], Mark.Right))
                i--
                j--
            }
            j > 0 && cost[i][j] == cost[i][j - 1] + 1 -> {
                marks.addFirst(MarkedWord(heard[j - 1], Mark.Extra))
                j--
            }
            i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> {
                marks.addFirst(MarkedWord(said[i - 1], Mark.Missing))
                i--
            }
            else -> {
                marks.addFirst(MarkedWord(heard[j - 1], Mark.Wrong))
                i--
                j--
            }
        }
    }
    return WordScore(cost[rows - 1][columns - 1], said.size, marks.toList())
}

private fun tokens(text: String, perCharacter: Boolean): List<String> =
    if (perCharacter) {
        text
            .codePoints()
            .toArray()
            .map { String(Character.toChars(it)) }
            .filter {
                key(it).isNotEmpty()
            }
    } else {
        text.split(Regex("\\s+")).filter { key(it).isNotEmpty() }
    }

/** What counts when comparing: lower case, curly apostrophes made straight, no punctuation. */
private fun key(word: String): String =
    word
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .filter { it.isLetterOrDigit() || it == '\'' }
        .trim('\'')

private val cjkBlocks =
    setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
    )

private fun isCjk(char: Char): Boolean = Character.UnicodeBlock.of(char) in cjkBlocks
