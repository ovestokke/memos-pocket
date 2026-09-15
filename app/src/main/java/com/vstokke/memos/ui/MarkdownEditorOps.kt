package com.vstokke.memos.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Pure, selection-aware task operations shared by the full editor and inline composer. */
object MarkdownEditorOps {
    /**
     * Adds a task prefix to the current line, or to every selected nonblank line. Existing task
     * lines and parsed code blocks are left alone so the action cannot manufacture a checklist in
     * a code sample. Selection endpoints continue to point at the same original characters.
     */
    fun insertTask(value: TextFieldValue): TextFieldValue {
        val source = value.text
        val selectionStart = minOf(value.selection.start, value.selection.end).coerceIn(0, source.length)
        val selectionEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, source.length)
        val collapsed = selectionStart == selectionEnd
        val firstLineStart = lineStart(source, selectionStart)
        val lastLineStart = lineStart(source, if (collapsed) selectionStart else (selectionEnd - 1).coerceAtLeast(0))
        val positions = buildList {
            var lineStart = firstLineStart
            while (true) {
                val lineEnd = lineContentEnd(source, lineStart)
                val line = source.substring(lineStart, lineEnd)
                val inCode = isCodePosition(source, lineStart)
                val shouldPrefix = if (collapsed) {
                    true
                } else {
                    line.isNotBlank() && lineStart < selectionEnd && lineEnd > selectionStart
                }
                if (shouldPrefix && !inCode && !taskPrefix.matches(line)) add(lineStart)
                if (lineStart == lastLineStart || lineEnd >= source.length) break
                lineStart = lineEnd + 1
            }
        }
        if (positions.isEmpty()) return value

        val inserted = buildString(source.length + positions.size * TASK_PREFIX.length) {
            var cursor = 0
            positions.forEach { position ->
                append(source, cursor, position)
                append(TASK_PREFIX)
                cursor = position
            }
            append(source, cursor, source.length)
        }
        fun mapOffset(offset: Int): Int = offset + positions.count { it <= offset } * TASK_PREFIX.length
        val mappedStart = mapOffset(value.selection.start.coerceIn(0, source.length))
        val mappedEnd = mapOffset(value.selection.end.coerceIn(0, source.length))
        return TextFieldValue(
            text = inserted,
            selection = TextRange(mappedStart, mappedEnd),
            composition = null,
        )
    }

    /** Replaces a validated task marker in local editor state without changing selection offsets. */
    fun replaceTask(value: TextFieldValue, ref: TaskRef, checked: Boolean): TextFieldValue? {
        val replaced = replaceTaskMarker(value.text, ref, checked) ?: return null
        return value.copy(text = replaced, composition = null)
    }

    /**
     * Continues a parsed task only for a committed single newline inserted at a collapsed caret.
     * A checked item always produces a fresh unchecked item. An empty item exits the list by
     * removing its old marker while retaining the committed newline.
     */
    fun continueTask(previous: TextFieldValue, updated: TextFieldValue): TextFieldValue {
        if (previous.composition != null || updated.composition != null) return updated
        if (!previous.selection.collapsed || !updated.selection.collapsed) return updated
        val insertion = singleNewlineInsertion(previous.text, updated.text, previous.selection.start) ?: return updated
        if (updated.selection.start != insertion.end) return updated

        val source = previous.text
        val lineStart = lineStart(source, insertion.start)
        val lineEnd = lineContentEnd(source, lineStart)
        if (insertion.start < lineStart || insertion.start > lineEnd) return updated
        val line = source.substring(lineStart, lineEnd)
        val match = taskPrefix.matchEntire(line) ?: return updated
        if (isCodePosition(source, lineStart)) return updated
        val ref = findTasks(source).firstOrNull { it.markerStart >= lineStart && it.markerStart < lineEnd } ?: return updated
        if (insertion.start < ref.markerEnd) return updated

        val contentStart = match.groups["content"]!!.range.first + lineStart
        val itemIsEmpty = source.substring(contentStart, lineEnd).isBlank()
        if (itemIsEmpty) {
            val prefixEnd = lineStart + match.value.length
            val text = updated.text.removeRange(lineStart, prefixEnd)
            val caret = (insertion.end - (prefixEnd - lineStart)).coerceIn(0, text.length)
            return TextFieldValue(text = text, selection = TextRange(caret), composition = null)
        }

        val continuation = buildString {
            append(match.groups["indent"]!!.value)
            append(match.groups["marker"]!!.value)
            append(match.groups["spacing"]!!.value)
            append("[ ]")
            append(match.groups["after"]!!.value)
        }
        val insertionPoint = insertion.end
        val text = updated.text.substring(0, insertionPoint) + continuation + updated.text.substring(insertionPoint)
        val caret = insertionPoint + continuation.length
        return TextFieldValue(text = text, selection = TextRange(caret), composition = null)
    }

    private data class NewlineInsertion(val start: Int, val end: Int)

    private fun singleNewlineInsertion(before: String, after: String, anchor: Int): NewlineInsertion? {
        if (anchor !in 0..before.length) return null
        for (newline in listOf("\n", "\r\n")) {
            if (after.length != before.length + newline.length) continue
            if (after.substring(0, anchor) != before.substring(0, anchor)) continue
            if (after.substring(anchor, anchor + newline.length) != newline) continue
            if (after.substring(anchor + newline.length) != before.substring(anchor)) continue
            return NewlineInsertion(anchor, anchor + newline.length)
        }
        return null
    }

    private fun lineStart(source: String, offset: Int): Int {
        val searchFrom = if (offset <= 0) -1 else offset - 1
        return source.lastIndexOf('\n', searchFrom) + 1
    }

    private fun lineContentEnd(source: String, start: Int): Int {
        val newline = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        return if (newline > start && source[newline - 1] == '\r') newline - 1 else newline
    }

    private fun isCodePosition(source: String, offset: Int): Boolean {
        fun blocksContain(blocks: List<MarkdownBlock>): Boolean = blocks.any { block ->
            when (block) {
                is MarkdownCodeBlock -> offset in block.sourceStart until block.sourceEnd
                is MarkdownBlockQuote -> blocksContain(block.blocks)
                is MarkdownList -> block.items.any { blocksContain(it.blocks) }
                else -> false
            }
        }
        return blocksContain(parseMarkdown(source).blocks)
    }

    private const val TASK_PREFIX = "- [ ] "
    private val taskPrefix = Regex(
        "^(?<indent>[ \\t]*)(?<marker>(?:[-+*]|[0-9]{1,9}[.)]))(?<spacing>[ \\t]+)\\[(?<checked>[ xX])\\](?<after>[ \\t]*)(?<content>.*)$",
    )
}
