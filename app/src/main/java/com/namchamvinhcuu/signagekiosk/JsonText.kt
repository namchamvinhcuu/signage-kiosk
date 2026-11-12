package com.namchamvinhcuu.signagekiosk

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests. org.json is an Android framework class and
// is not available in the JVM unit tests, so the few flat JSON objects the app writes are built by hand.

internal object JsonText {
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** A flat object of Boolean / Int / Long / finite Double / String values; null values are skipped. */
    fun flatObject(fields: Map<String, Any?>): String =
        fields.entries.filter { it.value != null }.joinToString(",", "{", "}") { (k, v) ->
            val rendered = when (v) {
                is Boolean, is Int, is Long -> v.toString()
                is Double -> if (v.isFinite()) v.toString() else "null"
                else -> quote(v.toString())
            }
            "${quote(k)}:$rendered"
        }
}
