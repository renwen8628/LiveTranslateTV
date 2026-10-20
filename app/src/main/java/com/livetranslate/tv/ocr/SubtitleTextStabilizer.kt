package com.livetranslate.tv.ocr

/**
 * Turns noisy per-frame OCR readings into subtitle events.
 *
 * OCR on video flickers: the same subtitle reads slightly differently from frame to frame,
 * some frames miss it, and some games type text out letter by letter. This class:
 *  - shows a line only after it was read (about) the same way [stableSamples] times in a row,
 *  - treats readings as the same line when they are [similarityThreshold]-similar, or when one
 *    is the start of the other (typewriter effect),
 *  - updates the current line instead of starting a new one when it only got longer or got
 *    read more completely,
 *  - clears it after [clearSamples] readings with no subtitle.
 *
 * Pure logic, no Android dependencies, so it is unit-tested.
 */
class SubtitleTextStabilizer(
    private val stableSamples: Int = 2,
    private val clearSamples: Int = 2,
    private val similarityThreshold: Double = 0.8,
    private val minLength: Int = 3,
) {
    sealed class Event {
        /** A new subtitle line. [id] increases for each new line. */
        data class Show(val id: Long, val text: String) : Event()
        /** The current line [id] was read more completely (or typed out further). */
        data class Update(val id: Long, val text: String) : Event()
        /** The subtitle went away. */
        data class Clear(val id: Long) : Event()
    }

    private var candidate: String? = null
    private var candidateCount = 0
    private var shown: String? = null
    private var shownId = 0L
    private var emptyCount = 0
    /** Normalized text of the last few lines, to ignore fragments read during fades. */
    private val recent = ArrayDeque<String>()

    /** Feed one OCR reading (null or blank = no subtitle visible). */
    fun onSample(raw: String?): Event? {
        val text = clean(raw)
        if (text == null) {
            candidate = null
            candidateCount = 0
            emptyCount++
            if (shown != null && emptyCount >= clearSamples) {
                shown = null
                return Event.Clear(shownId)
            }
            return null
        }
        emptyCount = 0

        val previous = candidate
        if (previous != null && sameLine(previous, text)) {
            candidateCount++
        } else {
            candidateCount = 1
        }
        candidate = text
        if (candidateCount < stableSamples) return null

        val current = shown
        return when {
            current == null -> if (isFragmentOfRecent(text)) null else newLine(text)
            sameLine(current, text) -> {
                // Same line: only report it if it clearly grew (typewriter effect or a more
                // complete read), not when a misread just adds a character ("m" -> "rn").
                val nNew = normalize(text)
                val nOld = normalize(current)
                val grew = nNew.length > nOld.length &&
                    (nNew.startsWith(nOld) || nNew.length >= nOld.length * 1.15)
                if (grew) {
                    shown = text
                    Event.Update(shownId, text)
                } else {
                    null
                }
            }
            isFragmentOfRecent(text) -> null
            else -> newLine(text)
        }
    }

    /** "ean is excele" read while "Oh, your Korean is excellent." fades out. */
    private fun isFragmentOfRecent(text: String): Boolean {
        val n = normalize(text)
        return recent.any { it.length > n.length && it.contains(n) }
    }

    fun reset() {
        candidate = null
        candidateCount = 0
        shown = null
        emptyCount = 0
        recent.clear()
    }

    private fun newLine(text: String): Event {
        shown = text
        shownId++
        recent.addLast(normalize(text))
        if (recent.size > RECENT_LINES) recent.removeFirst()
        return Event.Show(shownId, text)
    }

    private fun clean(raw: String?): String? {
        val text = raw?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        return text.takeIf { normalize(it).length >= minLength }
    }

    private fun sameLine(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        // Typewriter effect: one reading is the beginning of the other.
        if (na.length >= minLength && nb.length >= minLength && (na.startsWith(nb) || nb.startsWith(na))) return true
        return similarity(na, nb) >= similarityThreshold
    }

    companion object {
        private const val RECENT_LINES = 3

        /** Lowercase letters and digits only, so punctuation/spacing OCR noise doesn't matter. */
        fun normalize(s: String): String = buildString(s.length) {
            for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }

        /** 1.0 = identical; based on Levenshtein distance. */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val longest = maxOf(a.length, b.length)
            if (longest == 0) return 1.0
            return 1.0 - levenshtein(a, b).toDouble() / longest
        }

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = curr; curr = tmp
            }
            return prev[b.length]
        }
    }
}
