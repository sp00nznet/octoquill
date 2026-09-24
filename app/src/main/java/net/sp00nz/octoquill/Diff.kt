package net.sp00nz.octoquill

/** One line of a line diff. [kind] is ' ' same, '-' only theirs, '+' only yours, '~' elided. */
data class DiffLine(val kind: Char, val text: String)

/**
 * Line diff of [theirs] against [mine]: common prefix and suffix are trimmed, then an LCS
 * over what is left. A conflict is usually a paragraph or two, so the middle is small.
 *
 * ponytail: LCS is O(n*m) memory. Past [MAX_CELLS] the middle is shown as one removed block
 * and one added block - still correct, just coarse. Myers diff if that ever gets hit in practice.
 */
fun lineDiff(theirs: String, mine: String): List<DiffLine> {
    val a = theirs.lines()
    val b = mine.lines()
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++

    val x = a.subList(pre, a.size - suf)
    val y = b.subList(pre, b.size - suf)
    val out = a.subList(0, pre).mapTo(mutableListOf()) { DiffLine(' ', it) }

    if (x.size.toLong() * y.size > MAX_CELLS) {
        x.mapTo(out) { DiffLine('-', it) }
        y.mapTo(out) { DiffLine('+', it) }
    } else {
        // lcs[i][j] = length of the LCS of x[i..] and y[j..]
        val lcs = Array(x.size + 1) { IntArray(y.size + 1) }
        for (i in x.indices.reversed()) for (j in y.indices.reversed()) {
            lcs[i][j] = if (x[i] == y[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < x.size || j < y.size) when {
            i < x.size && j < y.size && x[i] == y[j] -> { out += DiffLine(' ', x[i]); i++; j++ }
            // removals before additions on a tie, so a replaced line reads old-then-new
            i < x.size && (j == y.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += DiffLine('-', x[i]); i++ }
            else -> { out += DiffLine('+', y[j]); j++ }
        }
    }

    a.subList(a.size - suf, a.size).mapTo(out) { DiffLine(' ', it) }
    return out
}

private const val MAX_CELLS = 4_000_000L

/** Keep [context] unchanged lines either side of each change; fold longer runs into one '~' line. */
fun folded(diff: List<DiffLine>, context: Int = 2): List<DiffLine> {
    val keep = BooleanArray(diff.size)
    diff.forEachIndexed { i, d ->
        if (d.kind != ' ') for (k in maxOf(0, i - context)..minOf(diff.lastIndex, i + context)) keep[k] = true
    }
    val out = mutableListOf<DiffLine>()
    var skipped = 0
    diff.forEachIndexed { i, d ->
        if (keep[i]) {
            if (skipped > 0) out += DiffLine('~', "$skipped unchanged lines")
            skipped = 0
            out += d
        } else skipped++
    }
    if (skipped > 0) out += DiffLine('~', "$skipped unchanged lines")
    return out
}
