package net.sp00nz.octoquill

import kotlin.test.Test
import kotlin.test.assertEquals

/** The conflict screen is where someone decides whose writing survives, so it must not lie. */
class DiffTest {

    private fun side(d: List<DiffLine>, drop: Char) =
        d.filter { it.kind != drop }.joinToString("\n") { it.text }

    @Test
    fun `both sides are reconstructable from the diff`() {
        val theirs = "a\nb\nc\nd\ne\nf"
        val mine = "a\nB\nc\nd\nx\ny\nf"
        val d = lineDiff(theirs, mine)
        assertEquals(theirs, side(d, '+'))
        assertEquals(mine, side(d, '-'))
    }

    @Test
    fun `a one line edit is one removal and one addition`() {
        val d = lineDiff("one\ntwo\nthree", "one\n2\nthree")
        assertEquals(listOf(' ', '-', '+', ' '), d.map { it.kind })
    }

    @Test
    fun `identical text has no changes and a new file is all additions`() {
        assertEquals(setOf(' '), lineDiff("same\ntext", "same\ntext").map { it.kind }.toSet())
        assertEquals(listOf('-', '+', '+'), lineDiff("", "new\nfile").map { it.kind })
    }

    @Test
    fun `context folds long unchanged runs`() {
        val theirs = (1..20).joinToString("\n")
        val mine = theirs.replace("\n10\n", "\nten\n")
        val kinds = folded(lineDiff(theirs, mine)).map { it.kind }
        assertEquals(listOf('~', ' ', ' ', '-', '+', ' ', ' ', '~'), kinds)
    }
}
