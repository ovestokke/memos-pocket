package com.vstokke.memos.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownEditorOpsTest {
    @Test
    fun insertsTaskAtCaretAndMapsCaretAtStartMiddleAndEnd() {
        val start = MarkdownEditorOps.insertTask(TextFieldValue("alpha", selection = TextRange(0)))
        assertEquals("- [ ] alpha", start.text)
        assertEquals(6, start.selection.start)

        val middle = MarkdownEditorOps.insertTask(TextFieldValue("alpha", selection = TextRange(2)))
        assertEquals("- [ ] alpha", middle.text)
        assertEquals(8, middle.selection.start)

        val end = MarkdownEditorOps.insertTask(TextFieldValue("alpha", selection = TextRange(5)))
        assertEquals("- [ ] alpha", end.text)
        assertEquals(11, end.selection.start)
    }

    @Test
    fun prefixesSelectedNonblankLinesAndPreservesSelection() {
        val value = TextFieldValue("one\n\ntwo", selection = TextRange(1, 7))
        val result = MarkdownEditorOps.insertTask(value)

        assertEquals("- [ ] one\n\n- [ ] two", result.text)
        assertEquals(TextRange(7, 19), result.selection)
    }

    @Test
    fun previewTaskReplacementChangesOnlyLocalEditorText() {
        val source = "- [ ] local draft"
        val ref = findTasks(source).single()
        val value = TextFieldValue(source, selection = TextRange(source.length))

        val updated = MarkdownEditorOps.replaceTask(value, ref, checked = true)

        assertEquals("- [x] local draft", updated?.text)
        assertEquals(value.selection, updated?.selection)
        assertEquals(source, value.text)
    }

    @Test
    fun ordinaryTypingPasteDeletionReplacementAndSelectionMovementNeverContinueTasks() {
        val source = "- [ ] a"
        val previous = TextFieldValue(source, selection = TextRange(source.length))
        listOf("b", "ab", "😀", " ", "pasted text").forEach { inserted ->
            val updated = TextFieldValue(
                source + inserted,
                selection = TextRange(source.length + inserted.length),
            )
            assertEquals(updated, MarkdownEditorOps.continueTask(previous, updated))
        }

        val deletion = TextFieldValue(source.dropLast(1), selection = TextRange(source.length - 1))
        assertEquals(deletion, MarkdownEditorOps.continueTask(previous, deletion))
        val replacement = TextFieldValue("- [ ] b", selection = TextRange(source.length))
        assertEquals(replacement, MarkdownEditorOps.continueTask(previous, replacement))
        val movedSelection = TextFieldValue(source, selection = TextRange(0))
        assertEquals(movedSelection, MarkdownEditorOps.continueTask(previous, movedSelection))
    }

    @Test
    fun continuesTaskAtCaretBeforeExistingLfAndCrLfLines() {
        val lf = "- [ ] first\n- [ ] second"
        val lfCaret = lf.indexOf('\n')
        val lfPrevious = TextFieldValue(lf, selection = TextRange(lfCaret))
        val lfTyped = TextFieldValue(lf.substring(0, lfCaret) + "\n" + lf.substring(lfCaret), selection = TextRange(lfCaret + 1))
        assertEquals(
            "- [ ] first\n- [ ] \n- [ ] second",
            MarkdownEditorOps.continueTask(lfPrevious, lfTyped).text,
        )

        val crlf = "- [ ] first\r\n- [ ] second"
        val crlfCaret = crlf.indexOf("\r\n")
        val crlfPrevious = TextFieldValue(crlf, selection = TextRange(crlfCaret))
        val crlfTyped = TextFieldValue(
            crlf.substring(0, crlfCaret) + "\r\n" + crlf.substring(crlfCaret),
            selection = TextRange(crlfCaret + 2),
        )
        assertEquals(
            "- [ ] first\r\n- [ ] \r\n- [ ] second",
            MarkdownEditorOps.continueTask(crlfPrevious, crlfTyped).text,
        )
    }

    @Test
    fun continuesCheckedTaskAsUncheckedAndPreservesListSyntax() {
        val source = "  4. [x] done"
        val previous = TextFieldValue(source, selection = TextRange(source.length))
        val typed = TextFieldValue("$source\n", selection = TextRange(source.length + 1))

        val result = MarkdownEditorOps.continueTask(previous, typed)

        assertEquals("  4. [x] done\n  4. [ ] ", result.text)
        assertEquals(result.text.length, result.selection.start)
    }

    @Test
    fun emptyTaskExitsListInsteadOfCreatingAnotherTask() {
        val source = "- [ ] "
        val previous = TextFieldValue(source, selection = TextRange(source.length))
        val typed = TextFieldValue("$source\n", selection = TextRange(source.length + 1))

        val result = MarkdownEditorOps.continueTask(previous, typed)

        assertEquals("\n", result.text)
        assertEquals(1, result.selection.start)

        val noTrailingSpace = "- [ ]"
        val noSpaceResult = MarkdownEditorOps.continueTask(
            TextFieldValue(noTrailingSpace, selection = TextRange(noTrailingSpace.length)),
            TextFieldValue("$noTrailingSpace\n", selection = TextRange(noTrailingSpace.length + 1)),
        )
        assertEquals("\n", noSpaceResult.text)
    }

    @Test
    fun compositionPasteAndCodeLookalikesAreNotContinued() {
        val source = "- [ ] task"
        val previous = TextFieldValue(source, selection = TextRange(source.length))
        val typed = TextFieldValue("$source\n", selection = TextRange(source.length + 1))
        assertEquals(typed.copy(composition = TextRange(0, 1)), MarkdownEditorOps.continueTask(previous, typed.copy(composition = TextRange(0, 1))))
        assertEquals("$source\nPASTED", MarkdownEditorOps.continueTask(previous, TextFieldValue("$source\nPASTED", selection = TextRange((source + "\nPASTED").length))).text)

        val code = "```\n- [ ] code\n```"
        val codePrevious = TextFieldValue(code, selection = TextRange(code.indexOf("code")))
        val codeTyped = TextFieldValue(code.replace("code", "\nc\ncode"), selection = TextRange(code.indexOf("code") + 3))
        assertEquals(codeTyped, MarkdownEditorOps.continueTask(codePrevious, codeTyped))
    }

    @Test
    fun preservesCrLfAndUnicodeWhenInsertingAndContinuing() {
        val source = "😀\r\n- [ ] café"
        val previous = TextFieldValue(source, selection = TextRange(source.length))
        val typed = TextFieldValue("$source\r\n", selection = TextRange(source.length + 2))
        val continued = MarkdownEditorOps.continueTask(previous, typed)
        assertEquals("😀\r\n- [ ] café\r\n- [ ] ", continued.text)
        assertEquals(continued.text.length, continued.selection.start)

        val inserted = MarkdownEditorOps.insertTask(TextFieldValue("😀\r\ntext", selection = TextRange(7)))
        assertEquals("😀\r\n- [ ] text", inserted.text)
    }

    @Test
    fun doesNothingForExistingTaskOrBlankSelectedLines() {
        val existing = TextFieldValue("- [ ] task", selection = TextRange(4))
        assertSame(existing, MarkdownEditorOps.insertTask(existing))
        val blank = TextFieldValue("\n", selection = TextRange(0, 1))
        assertSame(blank, MarkdownEditorOps.insertTask(blank))
    }
}
