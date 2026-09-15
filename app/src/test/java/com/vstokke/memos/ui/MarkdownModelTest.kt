package com.vstokke.memos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownModelTest {
    @Test
    fun parsesCoreBlocksAndInlineFormatting() {
        val document = parseMarkdown(
            """# H1
## H2
### H3
#### H4
##### H5
###### H6

plain **strong** *emphasis* ~~removed~~ and `code`.

> quoted **text**

---
""",
        )

        assertEquals(listOf(1, 2, 3, 4, 5, 6), document.blocks.filterIsInstance<MarkdownHeading>().map { it.level })
        val paragraph = document.blocks.filterIsInstance<MarkdownParagraph>().first()
        assertTrue(paragraph.inlines.any { it is MarkdownStrong })
        assertTrue(paragraph.inlines.any { it is MarkdownEmphasis })
        assertTrue(paragraph.inlines.any { it is MarkdownStrikethrough })
        assertTrue(paragraph.inlines.any { it is MarkdownInlineCode })
        assertTrue(document.blocks.any { it is MarkdownBlockQuote })
        assertTrue(document.blocks.any { it is MarkdownThematicBreak })
    }

    @Test
    fun setextSyntaxStaysVisibleInParagraphsAndContainersWhileAtxAndRulesRemain() {
        val source = "title\r\n---\r\nnext"
        val document = parseMarkdown(source)

        assertEquals(source, document.source)
        assertEquals(3, document.blocks.size)
        assertEquals("title", inlineText((document.blocks[0] as MarkdownParagraph).inlines))
        assertTrue(document.blocks[1] is MarkdownThematicBreak)
        assertEquals("next", inlineText((document.blocks[2] as MarkdownParagraph).inlines))
        assertFalse(document.blocks.any { it is MarkdownHeading })

        val quote = parseMarkdown("> title\n> ---").blocks.single() as MarkdownBlockQuote
        assertEquals(2, quote.blocks.size)
        assertEquals("title", inlineText((quote.blocks[0] as MarkdownParagraph).inlines))
        assertTrue(quote.blocks[1] is MarkdownThematicBreak)

        val list = parseMarkdown("- title\n  ---").blocks.single() as MarkdownList
        val listBlocks = list.items.single().blocks
        assertEquals(2, listBlocks.size)
        assertEquals("title", inlineText((listBlocks[0] as MarkdownParagraph).inlines))
        assertTrue(listBlocks[1] is MarkdownThematicBreak)

        val equalsSource = "title\n===\nnext"
        val equalsParagraph = parseMarkdown(equalsSource).blocks.single() as MarkdownParagraph
        assertEquals("title\n===\nnext", inlineText(equalsParagraph.inlines))

        assertTrue(parseMarkdown("# ATX\n\n---").blocks[0] is MarkdownHeading)
        assertTrue(parseMarkdown("---").blocks.single() is MarkdownThematicBreak)
    }

    @Test
    fun parsesNestedOrderedAndUnorderedListsAndTasks() {
        val document = parseMarkdown(
            """- [ ] outer
  1. [X] nested ordered
     - ordinary child
- ordinary
""",
        )

        val outer = document.blocks.filterIsInstance<MarkdownList>().single()
        assertFalse(outer.ordered)
        assertEquals(2, outer.items.size)
        assertEquals(false, outer.items[0].task?.checked)
        val nested = outer.items[0].blocks.filterIsInstance<MarkdownList>().single()
        assertTrue(nested.ordered)
        assertEquals(1, nested.startNumber)
        assertEquals(true, nested.items[0].task?.checked)
        assertNull(outer.items[1].task)
        assertEquals(2, document.tasks.size)
    }

    @Test
    fun orderedTasksStartingAboveOneAndEmptyItemsRemainInteractive() {
        val source = "4. [ ] first\n5. [x]\n"

        val tasks = findTasks(source)

        assertEquals(2, tasks.size)
        assertEquals(' ', source[tasks[0].markerStart])
        assertEquals('x', source[tasks[1].markerStart])
        assertEquals("4. [x] first\n5. [x]\n", replaceTaskMarker(source, tasks[0], checked = true))
    }

    @Test
    fun codeBlocksAndInlineCodeAreNotTasks() {
        val source = """`- [ ] inline`

    - [ ] indented code

```markdown
- [ ] fenced code
```

- [ ] actual task
"""

        val document = parseMarkdown(source)

        assertEquals(1, document.tasks.size)
        val actual = document.tasks.single()
        assertEquals(source.lastIndexOf("[ ]") + 1, actual.markerStart)
        assertEquals(" ", source.substring(actual.markerStart, actual.markerEnd))
        assertTrue(document.blocks.filterIsInstance<MarkdownCodeBlock>().any { !it.fenced })
        assertTrue(document.blocks.filterIsInstance<MarkdownCodeBlock>().any { it.fenced })
    }

    @Test
    fun parsesTablesWithAlignmentAndNestedInlineContent() {
        val document = parseMarkdown(
            """| Name | Count | Note |
| :--- | ---: | :---: |
| **one** | 2 | [more](https://example.com) |
""",
        )

        val table = document.blocks.single() as MarkdownTable
        assertEquals(3, table.header.size)
        assertEquals(
            listOf(MarkdownAlignment.LEFT, MarkdownAlignment.RIGHT, MarkdownAlignment.CENTER),
            table.header.map { it.alignment },
        )
        assertTrue(table.rows.single().first().inlines.single() is MarkdownStrong)
        assertTrue(table.rows.single()[2].inlines.single() is MarkdownLink)
    }

    @Test
    fun classifiesOnlyHttpAndHttpsLinksAsSafe() {
        assertEquals(MarkdownLinkSafety.SAFE_HTTP, classifyMarkdownLink("http://example.com/a?q=1"))
        assertEquals(MarkdownLinkSafety.SAFE_HTTPS, classifyMarkdownLink("HTTPS://example.com"))
        assertEquals(MarkdownLinkSafety.UNSAFE, classifyMarkdownLink("javascript:alert(1)"))
        assertEquals(MarkdownLinkSafety.UNSAFE, classifyMarkdownLink("mailto:test@example.com"))
        assertEquals(MarkdownLinkSafety.UNSAFE, classifyMarkdownLink("https://example.com/a b"))

        val links = parseMarkdown("[safe](https://example.com) [unsafe](javascript:alert(1)) <https://example.org>")
            .blocks.single()
            .let { it as MarkdownParagraph }
            .inlines.filterIsInstance<MarkdownLink>()
        assertEquals(listOf(MarkdownLinkSafety.SAFE_HTTPS, MarkdownLinkSafety.UNSAFE, MarkdownLinkSafety.SAFE_HTTPS), links.map { it.safety })
    }

    @Test
    fun htmlAndImagesAreRepresentedAsNonExecutingFallbackNodes() {
        val document = parseMarkdown("<script>alert(1)</script>\n\n![alt](https://example.com/a.png)")

        assertTrue(document.blocks.first() is MarkdownRawBlock)
        val image = (document.blocks[1] as MarkdownParagraph).inlines.single() as MarkdownImage
        assertEquals("https://example.com/a.png", image.destination)
    }

    @Test
    fun taskReferencesPreserveUtf16OffsetsCrLfUnicodeAndDuplicateTasks() {
        val source = "intro 😀\r\n- [ ] first\r\n- [x] second 😀\r\n- [ ] third\r\n"

        val tasks = findTasks(source)

        assertEquals(3, tasks.size)
        assertEquals(1, tasks[0].line)
        assertEquals(2, tasks[1].line)
        assertEquals(3, tasks[2].line)
        assertEquals(" ", source.substring(tasks[0].markerStart, tasks[0].markerEnd))
        assertEquals("x", source.substring(tasks[1].markerStart, tasks[1].markerEnd))
        assertEquals("- [ ] first", source.substring(source.indexOf("- [ ]"), source.indexOf("\r\n", source.indexOf("- [ ]"))))
        assertEquals(source, parseMarkdown(source).source)
    }

    @Test
    fun malformedAndEmptyTaskRowsPreserveSourceWithoutDuplicateRenderedMarkers() {
        val malformed = parseMarkdown("- [ ]foo")
        val malformedList = malformed.blocks.single() as MarkdownList
        assertNull(malformed.tasks.singleOrNull())
        assertNull(malformedList.items.single().task)
        assertEquals("[ ]foo", inlineText((malformedList.items.single().blocks.single() as MarkdownParagraph).inlines))
        assertEquals("- [ ]foo", malformed.source)

        val emptySource = "- [ ]"
        val empty = parseMarkdown(emptySource)
        val emptyItem = (empty.blocks.single() as MarkdownList).items.single()
        assertNotNull(emptyItem.task)
        assertEquals("", emptyItem.blocks.filterIsInstance<MarkdownParagraph>().singleOrNull()?.let { inlineText(it.inlines) }.orEmpty())
        assertEquals(emptySource, empty.source)
    }

    @Test
    fun malformedTaskTextOutsideAListIsNotInteractive() {
        val source = "text [ ] not a task\n- [ ] actual"

        val task = findTasks(source).single()
        assertEquals(source.lastIndexOf("[ ]") + 1, task.markerStart)
    }

    @Test
    fun replacementChangesExactlyOneMarkerAndLeavesCrLfAndOtherContentUntouched() {
        val source = "😀\r\n- [ ] first\r\n  continuation\r\n- [X] second\r\n"
        val tasks = findTasks(source)
        val first = tasks[0]
        val second = tasks[1]

        val checked = replaceTaskMarker(source, first, checked = true)
        assertNotNull(checked)
        assertEquals("😀\r\n- [x] first\r\n  continuation\r\n- [X] second\r\n", checked)
        assertEquals(source.length, checked!!.length)
        assertEquals("\r\n", checked.substring(2, 4))
        assertEquals("X", replaceTaskMarker(source, second, checked = true)!!.substring(second.markerStart, second.markerEnd))

        val unchecked = replaceTaskMarker(checked, first.copy(checked = true), checked = false)
        assertEquals(source, unchecked)
    }

    @Test
    fun replacementRejectsStaleOrInvalidReferencesAndFalsePositives() {
        val source = "- [ ] first\n- [x] second\n"
        val first = findTasks(source).first()

        assertNull(replaceTaskMarker(source, first.copy(checked = true), checked = true))
        assertNull(replaceTaskMarker(source, first.copy(markerStart = first.markerStart + 1), checked = true))
        assertNull(replaceTaskMarker(source, first.copy(markerEnd = first.markerEnd + 2), checked = true))
        assertEquals(
            "- [x] changed\n- [x] second\n",
            replaceTaskMarker(source.replace("first", "changed"), first, checked = true),
        )
        assertEquals("- [x] first\n- [x] second\n", replaceTaskMarker(source, first, checked = true))
    }

    @Test
    fun parseResultIsMemoizedForEqualUnchangedSource() {
        val source = "- [ ] cached"

        assertSame(parseMarkdown(source), parseMarkdown(source))
    }

    private fun inlineText(inlines: List<MarkdownInline>): String = inlines.joinToString(separator = "") { inline ->
        when (inline) {
            is MarkdownInlineText -> inline.literal
            is MarkdownEmphasis -> inlineText(inline.children)
            is MarkdownStrong -> inlineText(inline.children)
            is MarkdownStrikethrough -> inlineText(inline.children)
            is MarkdownInlineCode -> inline.literal
            is MarkdownLink -> inlineText(inline.label)
            is MarkdownImage -> inlineText(inline.alt)
            is MarkdownSoftBreak, is MarkdownHardBreak -> "\n"
            is MarkdownRawInline -> inline.literal
        }
    }
}
