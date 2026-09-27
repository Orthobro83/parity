package app.parity.core.csv

/** RFC 4180 CSV: CRLF line endings, fields quoted only when needed (design §15). */
object Csv {
    const val BOM = "﻿"

    fun write(header: List<String>, rows: List<List<String?>>, withBom: Boolean = true): String {
        val sb = StringBuilder()
        if (withBom) sb.append(BOM)
        appendRow(sb, header)
        rows.forEach { appendRow(sb, it) }
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, row: List<String?>) {
        row.forEachIndexed { i, field ->
            if (i > 0) sb.append(',')
            sb.append(escape(field ?: ""))
        }
        sb.append("\r\n")
    }

    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || field.startsWith(' ') || field.endsWith(' ')) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field

    /** Parses CSV text into rows of fields. Accepts CRLF or LF and an optional BOM. */
    fun read(text: String): List<List<String>> {
        val input = text.removePrefix(BOM)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var rowHasContent = false
        while (i < input.length) {
            val c = input[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; rowHasContent = true }
                    ',' -> { row += field.toString(); field.clear(); rowHasContent = true }
                    '\r' -> Unit
                    '\n' -> {
                        if (rowHasContent || field.isNotEmpty()) {
                            row += field.toString(); rows += row
                        }
                        row = mutableListOf(); field.clear(); rowHasContent = false
                    }
                    else -> { field.append(c); rowHasContent = true }
                }
            }
            i++
        }
        if (rowHasContent || field.isNotEmpty()) {
            row += field.toString(); rows += row
        }
        return rows
    }

    /** Rows as maps keyed by the header row. */
    fun readTable(text: String): List<Map<String, String>> {
        val rows = read(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).map { r -> header.mapIndexed { i, name -> name to (r.getOrNull(i) ?: "") }.toMap() }
    }
}
