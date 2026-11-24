package memore.app.utils

object JsonCleaner {
    fun clean(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("\uFEFF")) cleaned = cleaned.substring(1)
        cleaned = cleaned
            .replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^```\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .replace(Regex("^[^{\\[]*", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("[^}\\]\\]]*$", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex(",\\s*([}\\]])"), "$1")
            .trim()
        return cleaned
    }
}
