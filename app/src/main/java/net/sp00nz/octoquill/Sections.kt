package net.sp00nz.octoquill

/**
 * A slice of a file between two headings. Offsets are exact indices into the source, so
 * editing a slice and splicing it back is byte-for-byte lossless outside the slice.
 */
data class Section(val title: String, val level: Int, val start: Int, val end: Int) {
    val isWholeFile get() = level == 0
}

fun wholeFile(name: String, length: Int) = Section(name, 0, 0, length)

/**
 * Split markdown on ATX headings. Fence-aware, so a `#` comment inside a ``` block is not
 * mistaken for a heading. Returns empty if there is nothing to split on.
 */
fun parseSections(src: String): List<Section> {
    data class Head(val offset: Int, val level: Int, val title: String)

    val heads = mutableListOf<Head>()
    var fenced = false
    var offset = 0
    for (line in src.split("\n")) {
        val t = line.trimStart()
        if (t.startsWith("```") || t.startsWith("~~~")) {
            fenced = !fenced
        } else if (!fenced && t.startsWith("#")) {
            val level = t.takeWhile { it == '#' }.length
            if (level in 1..6 && t.length > level && t[level] == ' ') {
                heads += Head(offset + (line.length - t.length), level, t.drop(level).trim())
            }
        }
        offset += line.length + 1 // the \n we split on
    }
    if (heads.isEmpty()) return emptyList()

    val out = mutableListOf<Section>()
    if (src.take(heads.first().offset).isNotBlank()) {
        out += Section("(before the first heading)", 1, 0, heads.first().offset)
    }
    heads.forEachIndexed { i, h ->
        val end = if (i + 1 < heads.size) heads[i + 1].offset else src.length
        out += Section(h.title.ifBlank { "(untitled)" }, h.level, h.offset, end)
    }
    return out
}

/** Word count for prose. One pass. */
fun wordCount(s: String): Int {
    var n = 0
    var inWord = false
    for (c in s) {
        if (c.isWhitespace()) inWord = false
        else if (!inWord) {
            inWord = true; n++
        }
    }
    return n
}


/**
 * Replace one section's text inside the whole file. Everything outside [section] is
 * preserved byte for byte - this is the operation that must never mangle a manuscript.
 */
fun splice(full: String, section: Section?, newText: String): String =
    if (section == null) newText
    else full.substring(0, section.start) + newText + full.substring(section.end)
