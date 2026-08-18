package net.sp00nz.octoquill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one thing in this app that could silently destroy work: editing a section of a long
 * document and splicing it back. Everything outside the edited slice must survive exactly.
 */
class SectionsTest {

    private val doc = """
        Front matter, before any heading.

        # Part One
        Opening lines.

        ## The ferry
        It left at six.

        ## The dock
        Fog, then a roofline.

        # Part Two
        Later.
    """.trimIndent()

    @Test
    fun `finds every heading and the preamble`() {
        val s = parseSections(doc)
        assertEquals(
            listOf("(before the first heading)", "Part One", "The ferry", "The dock", "Part Two"),
            s.map { it.title },
        )
        assertEquals(listOf(1, 1, 2, 2, 1), s.map { it.level })
    }

    @Test
    fun `sections tile the document with no gaps or overlaps`() {
        val s = parseSections(doc)
        assertEquals(0, s.first().start)
        assertEquals(doc.length, s.last().end)
        s.zipWithNext { a, b -> assertEquals(a.end, b.start, "gap between ${a.title} and ${b.title}") }
        assertEquals(doc, s.joinToString("") { doc.substring(it.start, it.end) })
    }

    @Test
    fun `splicing a section back unchanged is a no-op`() {
        for (sec in parseSections(doc)) {
            assertEquals(doc, splice(doc, sec, doc.substring(sec.start, sec.end)), "on ${sec.title}")
        }
    }

    @Test
    fun `editing one section leaves the rest byte for byte identical`() {
        val s = parseSections(doc).first { it.title == "The ferry" }
        val edited = splice(doc, s, doc.substring(s.start, s.end).replace("six", "seven"))

        assertTrue(edited.contains("It left at seven."))
        assertEquals(doc.length + 2, edited.length)
        // untouched neighbours survive verbatim
        assertTrue(edited.contains("Front matter, before any heading."))
        assertTrue(edited.contains("Fog, then a roofline."))
        assertEquals(doc.lines().size, edited.lines().size)
        assertEquals(
            doc.lines().count { it.startsWith("#") },
            edited.lines().count { it.startsWith("#") },
        )
    }

    @Test
    fun `a hash inside a fenced code block is not a heading`() {
        val withFence = """
            # Real heading

            ```sh
            # not a heading, just a shell comment
            echo hi
            ```

            ## Also real
        """.trimIndent()
        assertEquals(listOf("Real heading", "Also real"), parseSections(withFence).map { it.title })
    }

    @Test
    fun `a document with no headings has no sections`() {
        assertTrue(parseSections("just prose\n\nmore prose").isEmpty())
    }

    @Test
    fun `splice with a null section replaces the whole file`() {
        assertEquals("brand new", splice(doc, null, "brand new"))
    }

    @Test
    fun `word count ignores runs of whitespace`() {
        assertEquals(0, wordCount("   \n\t "))
        assertEquals(3, wordCount("  one   two\n\nthree  "))
    }
}
