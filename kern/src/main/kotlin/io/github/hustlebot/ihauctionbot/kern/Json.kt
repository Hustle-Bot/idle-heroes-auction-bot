package io.github.hustlebot.ihauctionbot.kern

/** Small JSON helper: the reports write their few JSON files by hand, without a library. */
object Json {

    /** Text as a JSON string (with quotes and escape characters). */
    fun text(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
