package com.leqi.experiment.phonel

/** Small first phrase, then bounded natural phrases. Never drop text or cut a surrogate pair. */
internal object SpeechChunks {
    fun split(text: String): List<String> {
        require(text.isNotBlank() && text.length <= 500)
        val points = text.codePoints().toArray()
        val result = mutableListOf<String>()
        var start = 0
        while (start < points.size) {
            val limit = if (result.isEmpty()) 16 else 40
            var end = minOf(points.size, start + limit)
            if (end < points.size) {
                val punctuation = (end - 1 downTo start + 5).firstOrNull { index ->
                    points[index] in "，。！？；、,!?;\n".map(Char::code)
                }
                if (punctuation != null) end = punctuation + 1
                // Keep dates, decimal numbers and English words together where possible.
                else {
                    fun asciiWord(value: Int) = value in 48..57 || value in 65..90 || value in 97..122 || value == 46
                    if (asciiWord(points[end - 1]) && asciiWord(points[end])) {
                        var before = end
                        while (before > start && asciiWord(points[before - 1])) before--
                        if (before > start) end = before
                        else while (end < points.size && asciiWord(points[end])) end++
                    }
                }
            }
            result.add(String(points, start, end - start))
            start = end
        }
        // Punctuation-only tails must not ask a model to generate silent placeholder audio.
        return result.fold(mutableListOf<String>()) { merged, part ->
            if (part.none(Char::isLetterOrDigit) && merged.isNotEmpty())
                merged[merged.lastIndex] += part
            else merged.add(part)
            merged
        }
    }
}
