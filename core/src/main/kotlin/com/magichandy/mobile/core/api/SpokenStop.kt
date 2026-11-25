package com.magichandy.mobile.core.api

/**
 * Recognizes a spoken Stop in a transcript from voice input, before chat sees it (design note
 * docs/voice-design.md §6). People repeat Stop and pad it with words when they say it, so this is
 * broader than the typed rule, [isChatStopMessage], which it includes:
 * - a clear phrase such as "stop", "stop it" or "turn it off" counts in any clause, repeated, and
 *   with fillers such as "okay", "no" or "please" around it;
 * - an ambiguous word such as "pause", "enough" or "para" counts only when the whole transcript is
 *   made of Stop words;
 * - a clause that is only "don't", "do not" or "never" cancels a Stop in the clause after it, even
 *   across the punctuation speech recognition adds, so "Don't. Stop." is not a Stop.
 *
 * English, Spanish and Portuguese are covered: the languages Parakeet hears that the app's typed
 * Stop also covers. A false Stop only stops; a missed one goes to chat, where the model can still
 * stop.
 */
public fun isSpokenStop(text: String): Boolean {
    if (isChatStopMessage(text)) return true
    val clauses = text.split(clauseBreaks).map { words(it) }.filter { it.isNotEmpty() }
    if (clauses.isEmpty()) return false
    // Every clause is Stop words, such as "Enough." or "Pause, pause."
    if (clauses.all { segments(it, allowAmbiguous = true) }) return true
    // Or a clear Stop stands in its own clause, and no lone negation comes just before it.
    return clauses.withIndex().any { (index, clause) ->
        segments(clause, allowAmbiguous = false) &&
            clauses.getOrNull(index - 1)?.joinToString(" ") !in negations
    }
}

/** The words of [clause], lower case, with apostrophes and other marks as word breaks. */
private fun words(clause: String): List<String> =
    clause.lowercase().split(wordBreaks).filter { it.isNotBlank() }

/**
 * True when [words] are only Stop phrases and fillers, with at least one Stop phrase, as in "stop
 * stop it", "okay stop now" or "no, stop". Ambiguous words count as Stop phrases when
 * [allowAmbiguous].
 */
private fun segments(words: List<String>, allowAmbiguous: Boolean): Boolean {
    val phrases = if (allowAmbiguous) clearPhrases + ambiguousWords else clearPhrases
    // reached[i]: the first i words split into phrases and fillers; found[i]: with a phrase.
    val reached = BooleanArray(words.size + 1).also { it[0] = true }
    val found = BooleanArray(words.size + 1)
    for (start in words.indices) {
        if (!reached[start]) continue
        if (words[start] in fillers) {
            reached[start + 1] = true
            found[start + 1] = found[start + 1] || found[start]
        }
        for (length in 1..minOf(longestPhrase, words.size - start)) {
            val end = start + length
            if (words.subList(start, end).joinToString(" ") in phrases) {
                reached[end] = true
                found[end] = true
            }
        }
    }
    return reached[words.size] && found[words.size]
}

private val clauseBreaks = Regex("[.,;:!?¡¿…—–\\n]+")
private val wordBreaks = Regex("[\\p{Z}\\p{P}\\p{S}\\s]+")

/** Phrases that mean Stop wherever they stand on their own. */
private val clearPhrases =
    setOf(
        // English
        "stop",
        "stop it",
        "stop that",
        "stop now",
        "stop motion",
        "stop the motion",
        "stop moving",
        "stop everything",
        "stop the device",
        "make it stop",
        "turn it off",
        "turn off",
        "turn motion off",
        "turn off motion",
        "turn it all off",
        "emergency stop",
        "halt",
        // Spanish
        "detente",
        "detén",
        "deten",
        "detener",
        "detenlo",
        "para ya",
        "para el movimiento",
        "detén el movimiento",
        "deten el movimiento",
        "detener el movimiento",
        "detener todo",
        "apágalo",
        "apagalo",
        "parada de emergencia",
        // Portuguese
        "pare",
        "parar",
        "pare agora",
        "para agora",
        "pare o movimento",
        "parar o movimento",
        "parar tudo",
        "desliga",
        "desligue",
        "parada de emergência",
    )

/** Words that mean Stop only when nothing else is said: "para" is also Spanish for "for". */
private val ambiguousWords =
    setOf("pause", "end", "enough", "para", "pausa", "basta", "chega", "alto", "ya basta")

/** Words around a Stop that don't change it. */
private val fillers =
    setOf(
        "okay",
        "ok",
        "oh",
        "no",
        "hey",
        "wait",
        "please",
        "now",
        "right",
        "just",
        "and",
        "so",
        "um",
        "uh",
        "vale",
        "oye",
        "espera",
        "por",
        "favor",
        "ya",
        "ahora",
        "ei",
        "agora",
        "já",
        "i",
        "said",
    )

/** A clause that is only one of these cancels a Stop in the clause after it. */
private val negations = setOf("don t", "dont", "do not", "never", "doesn t", "did not", "didn t")

private val longestPhrase =
    (clearPhrases + ambiguousWords).maxOf { phrase -> phrase.split(" ").size }
