package com.vstokke.memos.ui

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.SourceLines
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockParser
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.commonmark.node.DefinitionMap
import org.commonmark.node.SourceSpan
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import java.net.URI
import java.util.LinkedHashMap

/** The UTF-16 source range of one GFM task marker. */
data class TaskRef(
    val markerStart: Int,
    val markerEnd: Int,
    val checked: Boolean,
    val line: Int,
)

enum class MarkdownLinkSafety {
    SAFE_HTTP,
    SAFE_HTTPS,
    UNSAFE,
}

/** Only web URLs are handed to an external browser by the renderer. */
fun classifyMarkdownLink(destination: String): MarkdownLinkSafety {
    if (destination.isEmpty() || destination.any { it.isISOControl() || it.isWhitespace() }) {
        return MarkdownLinkSafety.UNSAFE
    }
    val uri = runCatching { URI(destination) }.getOrNull() ?: return MarkdownLinkSafety.UNSAFE
    val scheme = uri.scheme?.lowercase() ?: return MarkdownLinkSafety.UNSAFE
    if (uri.rawAuthority.isNullOrBlank()) return MarkdownLinkSafety.UNSAFE
    return when (scheme) {
        "http" -> MarkdownLinkSafety.SAFE_HTTP
        "https" -> MarkdownLinkSafety.SAFE_HTTPS
        else -> MarkdownLinkSafety.UNSAFE
    }
}

fun isSafeMarkdownLink(destination: String): Boolean =
    classifyMarkdownLink(destination) != MarkdownLinkSafety.UNSAFE

sealed interface MarkdownBlock {
    val sourceStart: Int
    val sourceEnd: Int
}

data class MarkdownParagraph(
    val inlines: List<MarkdownInline>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownHeading(
    val level: Int,
    val inlines: List<MarkdownInline>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownBlockQuote(
    val blocks: List<MarkdownBlock>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownList(
    val ordered: Boolean,
    val startNumber: Int,
    val tight: Boolean,
    val items: List<MarkdownListItem>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownListItem(
    val blocks: List<MarkdownBlock>,
    val task: TaskRef?,
    val sourceStart: Int,
    val sourceEnd: Int,
) {
    init {
        require(sourceStart <= sourceEnd) { "A list item source range must be ordered." }
    }
}

data class MarkdownCodeBlock(
    val literal: String,
    val info: String?,
    val fenced: Boolean,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownThematicBreak(
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownTable(
    val header: List<MarkdownTableCell>,
    val rows: List<List<MarkdownTableCell>>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

data class MarkdownTableCell(
    val inlines: List<MarkdownInline>,
    val alignment: MarkdownAlignment,
    val header: Boolean,
)

data class MarkdownRawBlock(
    val literal: String,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownBlock

enum class MarkdownAlignment {
    NONE,
    LEFT,
    CENTER,
    RIGHT,
}

sealed interface MarkdownInline {
    val sourceStart: Int
    val sourceEnd: Int
}

data class MarkdownInlineText(
    val literal: String,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownEmphasis(
    val children: List<MarkdownInline>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownStrong(
    val children: List<MarkdownInline>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownStrikethrough(
    val children: List<MarkdownInline>,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownInlineCode(
    val literal: String,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownLink(
    val label: List<MarkdownInline>,
    val destination: String,
    val title: String?,
    val safety: MarkdownLinkSafety,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownImage(
    val alt: List<MarkdownInline>,
    val destination: String,
    val title: String?,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownSoftBreak(
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownHardBreak(
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

/** Raw HTML is represented as text by the Android renderer; it is never executed. */
data class MarkdownRawInline(
    val literal: String,
    override val sourceStart: Int,
    override val sourceEnd: Int,
) : MarkdownInline

data class MarkdownDocument(
    val source: String,
    val blocks: List<MarkdownBlock>,
    val tasks: List<TaskRef>,
)

private val markdownExtensions: List<Extension> = listOf(
    AutolinkExtension.create(),
    StrikethroughExtension.create(),
    TablesExtension.create(),
    TaskListItemsExtension.create(),
)

/**
 * CommonMark's setext handling lives in the core heading factory and otherwise consumes the
 * underline before the render model sees it. This factory replaces only that active paragraph
 * with a source-preserving paragraph parser, so ATX headings, standalone thematic breaks, and
 * container nesting continue to use the normal parser.
 */
private val disableSetextHeadingFactory = object : AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
        if (state.indent >= 4) return BlockStart.none()
        if (matched.getParagraphLines().isEmpty()) return BlockStart.none()
        if (matched.getMatchedBlockParser().getBlock() !is Paragraph) return BlockStart.none()
        val line = state.getLine().content
        val start = state.nextNonSpaceIndex
        if (isThematicBreak(line, start)) {
            return BlockStart.of(
                org.commonmark.internal.ThematicBreakParser(
                    line.subSequence(state.index, line.length).toString(),
                ),
            ).atIndex(line.length)
        }
        if (setextHeadingLevel(line, start) == 0) return BlockStart.none()
        return BlockStart.of(SourcePreservingParagraphParser(matched.getParagraphLines()))
            .atIndex(start)
            .replaceActiveBlockParser()
    }
}

private class SourcePreservingParagraphParser(initial: SourceLines) : BlockParser {
    private val delegate = org.commonmark.internal.ParagraphParser()

    init {
        initial.lines.forEach(delegate::addLine)
        initial.sourceSpans.forEach(delegate::addSourceSpan)
    }

    override fun isContainer(): Boolean = delegate.isContainer()
    override fun canHaveLazyContinuationLines(): Boolean = delegate.canHaveLazyContinuationLines()
    override fun canContain(block: Block): Boolean = delegate.canContain(block)
    override fun getBlock(): Block = delegate.block
    override fun tryContinue(state: ParserState) = delegate.tryContinue(state)
    override fun addLine(line: SourceLine) = delegate.addLine(line)
    override fun addSourceSpan(sourceSpan: SourceSpan) = delegate.addSourceSpan(sourceSpan)
    override fun getDefinitions(): List<DefinitionMap<*>> = delegate.definitions
    override fun closeBlock() = delegate.closeBlock()
    override fun parseInlines(inlineParser: org.commonmark.parser.InlineParser) = delegate.parseInlines(inlineParser)
}

private fun isThematicBreak(content: CharSequence, start: Int): Boolean {
    var hyphens = 0
    var underscores = 0
    var stars = 0
    for (index in start until content.length) {
        when (content[index]) {
            '-', '_' , '*' -> when (content[index]) {
                '-' -> hyphens++
                '_' -> underscores++
                '*' -> stars++
            }
            ' ', '\t' -> Unit
            else -> return false
        }
    }
    return (hyphens >= 3 && underscores == 0 && stars == 0) ||
        (underscores >= 3 && hyphens == 0 && stars == 0) ||
        (stars >= 3 && hyphens == 0 && underscores == 0)
}

private fun setextHeadingLevel(content: CharSequence, start: Int): Int {
    if (start >= content.length) return 0
    val marker = content[start]
    if (marker != '=' && marker != '-') return 0
    var index = start + 1
    while (index < content.length && content[index] == marker) index++
    while (index < content.length && (content[index] == ' ' || content[index] == '\t')) index++
    return if (index == content.length) if (marker == '=') 1 else 2 else 0
}

private val markdownParser: Parser = Parser.builder()
    .customBlockParserFactory(disableSetextHeadingFactory)
    .extensions(markdownExtensions)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

private const val DOCUMENT_CACHE_SIZE = 32
private val documentCache = object : LinkedHashMap<String, MarkdownDocument>(DOCUMENT_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownDocument>?): Boolean =
        size > DOCUMENT_CACHE_SIZE
}

/**
 * Parses the unmodified memo source into a small immutable model for Compose.
 * Equal source strings share a bounded cached model; source text is never normalized.
 */
fun parseMarkdown(source: String): MarkdownDocument = synchronized(documentCache) {
    documentCache[source]?.let { return@synchronized it }
    val root = markdownParser.parse(source)
    val tasks = collectTaskRefs(root, source)
    val taskByMarker = tasks.associateBy { it.markerStart }
    val document = MarkdownDocument(
        source = source,
        blocks = root.children().mapNotNull { it.toBlock(source, taskByMarker) },
        tasks = tasks,
    )
    documentCache[source] = document
    document
}

/** Finds only task-list markers recognized by the parsed GFM AST. */
fun findTasks(source: String): List<TaskRef> = parseMarkdown(source).tasks

/**
 * Replaces exactly one UTF-16 marker code unit after re-validating the parsed task identity.
 * A stale reference, changed source marker, or malformed range returns null.
 */
fun replaceTaskMarker(source: String, ref: TaskRef, checked: Boolean): String? {
    if (ref.markerEnd != ref.markerStart + 1 || ref.markerStart < 0 || ref.markerEnd > source.length) {
        return null
    }
    val current = source[ref.markerStart]
    val currentChecked = current == 'x' || current == 'X'
    if (currentChecked != ref.checked || (!currentChecked && current != ' ')) return null
    val parsedRef = findTasks(source).firstOrNull {
        it.markerStart == ref.markerStart && it.markerEnd == ref.markerEnd && it.checked == ref.checked && it.line == ref.line
    } ?: return null
    if (parsedRef != ref) return null
    if (checked == ref.checked) return source
    val replacement = if (checked) 'x' else ' '
    return source.substring(0, ref.markerStart) + replacement + source.substring(ref.markerEnd)
}

private fun collectTaskRefs(root: Node, source: String): List<TaskRef> {
    val refs = mutableListOf<TaskRef>()
    fun visit(node: Node) {
        if (node is ListItem) {
            val marker = node.children().filterIsInstance<TaskListItemMarker>().firstOrNull()
            val ref = marker?.let { taskRefFor(node, it, source) } ?: fallbackTaskRefFor(node, source)
            ref?.let(refs::add)
        }
        node.children().forEach(::visit)
    }
    visit(root)
    return refs.toList()
}

private val taskMarkerAfterListMarker = Regex(
    "^[ \\t]*(?:[-+*]|[0-9]{1,9}[.)])[\\t ]+\\[([ xX])\\](?:[\\t ]+|$)",
)

private fun taskRefFor(item: ListItem, marker: TaskListItemMarker, source: String): TaskRef? {
    val span = item.sourceSpans.minByOrNull { it.inputIndex } ?: return null
    if (span.inputIndex !in source.indices) return null
    val lineEnd = source.indexOf('\n', span.inputIndex).let { if (it < 0) source.length else it }
    val itemSource = source.substring(span.inputIndex, lineEnd).removeSuffix("\r")
    val match = taskMarkerAfterListMarker.find(itemSource) ?: return null
    if (match.range.first != 0 || match.range.last + 1 > itemSource.length) return null
    val markerStart = span.inputIndex + match.groups[1]!!.range.first
    val markerEnd = markerStart + 1
    val checked = marker.isChecked
    val sourceChecked = match.groupValues[1] == "x" || match.groupValues[1] == "X"
    if (checked != sourceChecked || source.substring(markerStart, markerEnd).length != 1) return null
    return TaskRef(markerStart, markerEnd, checked, span.lineIndex)
}

/** Some CommonMark versions omit TaskListItemMarker for ordered lists starting above 1. */
private fun fallbackTaskRefFor(item: ListItem, source: String): TaskRef? {
    val span = item.sourceSpans.minByOrNull { it.inputIndex } ?: return null
    if (span.inputIndex !in source.indices) return null
    val lineEnd = source.indexOf('\n', span.inputIndex).let { if (it < 0) source.length else it }
    val itemSource = source.substring(span.inputIndex, lineEnd).removeSuffix("\r")
    val match = taskMarkerAfterListMarker.find(itemSource) ?: return null
    val markerStart = span.inputIndex + match.groups[1]!!.range.first
    val markerEnd = markerStart + 1
    val marker = source.substring(markerStart, markerEnd)
    val checked = marker == "x" || marker == "X"
    if (checked != (match.groupValues[1] == "x" || match.groupValues[1] == "X")) return null
    return TaskRef(markerStart, markerEnd, checked, span.lineIndex)
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) {
        add(child)
        child = child.next
    }
}

private fun Node.sourceRange(): IntRange {
    val spans = sourceSpans
    if (spans.isEmpty()) return 0..0
    val start = spans.minOf { it.inputIndex }
    val end = spans.maxOf { it.inputIndex + it.length }
    return start until end
}

private fun Node.toBlock(source: String, taskByMarker: Map<Int, TaskRef>): MarkdownBlock? {
    val range = sourceRange()
    return when (this) {
        is Heading -> {
            if (isSetextHeading(source)) {
                MarkdownParagraph(children().flatMap { it.toInlines(source) }, range.first, range.last + 1)
            } else {
                MarkdownHeading(level, children().flatMap { it.toInlines(source) }, range.first, range.last + 1)
            }
        }
        is Paragraph -> MarkdownParagraph(children().flatMap { it.toInlines(source) }, range.first, range.last + 1)
        is BlockQuote -> MarkdownBlockQuote(
            blocks = children().flatMap { child -> child.toBlock(source, taskByMarker)?.let(::listOf).orEmpty() },
            sourceStart = range.first,
            sourceEnd = range.last + 1,
        )
        is BulletList -> MarkdownList(
            ordered = false,
            startNumber = 1,
            tight = isTight,
            items = children().filterIsInstance<ListItem>().map { it.toListItem(source, taskByMarker) },
            sourceStart = range.first,
            sourceEnd = range.last + 1,
        )
        is OrderedList -> MarkdownList(
            ordered = true,
            startNumber = markerStartNumber ?: 1,
            tight = isTight,
            items = children().filterIsInstance<ListItem>().map { it.toListItem(source, taskByMarker) },
            sourceStart = range.first,
            sourceEnd = range.last + 1,
        )
        is FencedCodeBlock -> MarkdownCodeBlock(literal, info.ifBlank { null }, true, range.first, range.last + 1)
        is IndentedCodeBlock -> MarkdownCodeBlock(literal, null, false, range.first, range.last + 1)
        is ThematicBreak -> MarkdownThematicBreak(range.first, range.last + 1)
        is TableBlock -> toTable(source, range)
        is HtmlBlock -> MarkdownRawBlock(literal, range.first, range.last + 1)
        is LinkReferenceDefinition -> null
        is Block -> MarkdownRawBlock(source.substringSafe(range), range.first, range.last + 1)
        else -> null
    }
}

private fun ListItem.toListItem(source: String, taskByMarker: Map<Int, TaskRef>): MarkdownListItem {
    val range = sourceRange()
    val marker = children().filterIsInstance<TaskListItemMarker>().firstOrNull()
    val parsedTask = marker?.let { taskRefFor(this, it, source) }
    val fallbackTask = if (parsedTask == null) fallbackTaskRefFor(this, source) else null
    val task = (parsedTask ?: fallbackTask)?.let { taskByMarker[it.markerStart] ?: it }
    val blocks = children().mapNotNull { child ->
        if (child is TaskListItemMarker) {
            null
        } else {
            child.toBlock(source, taskByMarker)?.let { block ->
                if (fallbackTask == null) block else block.stripFallbackTaskMarker(source, fallbackTask)
            }
        }
    }
    return MarkdownListItem(blocks, task, range.first, range.last + 1)
}

private fun MarkdownBlock.stripFallbackTaskMarker(source: String, task: TaskRef): MarkdownBlock = when (this) {
    is MarkdownParagraph -> copy(inlines = inlines.mapNotNull { it.stripFallbackTaskMarker(source, task) })
    else -> this
}

private fun MarkdownInline.stripFallbackTaskMarker(source: String, task: TaskRef): MarkdownInline? {
    val tokenStart = task.markerStart - 1
    val tokenEnd = task.markerEnd + 1
    if (tokenStart < 0 || tokenEnd > source.length) return this
    return when (this) {
        is MarkdownInlineText -> {
            val localStart = tokenStart - sourceStart
            val token = source.substring(tokenStart, tokenEnd)
            if (localStart >= 0 && localStart + token.length <= literal.length &&
                literal.substring(localStart, localStart + token.length) == token
            ) {
                literal.removeRange(localStart, localStart + token.length).takeIf { it.isNotEmpty() }
                    ?.let { copy(literal = it) }
            } else this
        }
        is MarkdownEmphasis -> copy(children = children.mapNotNull { it.stripFallbackTaskMarker(source, task) })
        is MarkdownStrong -> copy(children = children.mapNotNull { it.stripFallbackTaskMarker(source, task) })
        is MarkdownStrikethrough -> copy(children = children.mapNotNull { it.stripFallbackTaskMarker(source, task) })
        is MarkdownLink -> copy(label = label.mapNotNull { it.stripFallbackTaskMarker(source, task) })
        is MarkdownImage -> copy(alt = alt.mapNotNull { it.stripFallbackTaskMarker(source, task) })
        else -> this
    }
}

private fun TableBlock.toTable(source: String, range: IntRange): MarkdownTable {
    val head = children().filterIsInstance<TableHead>().firstOrNull()
        ?.let { it.children().filterIsInstance<TableRow>().firstOrNull() }
    val body = children().filterIsInstance<TableBody>().flatMap { bodyNode ->
        bodyNode.children().filterIsInstance<TableRow>()
    }
    return MarkdownTable(
        header = head?.let { row -> row.children().filterIsInstance<TableCell>().map { it.toTableCell(source) } }.orEmpty(),
        rows = body.map { row -> row.children().filterIsInstance<TableCell>().map { it.toTableCell(source) } },
        sourceStart = range.first,
        sourceEnd = range.last + 1,
    )
}

private fun TableCell.toTableCell(source: String): MarkdownTableCell = MarkdownTableCell(
    inlines = children().flatMap { it.toInlines(source) },
    alignment = when (alignment) {
        TableCell.Alignment.LEFT -> MarkdownAlignment.LEFT
        TableCell.Alignment.CENTER -> MarkdownAlignment.CENTER
        TableCell.Alignment.RIGHT -> MarkdownAlignment.RIGHT
        null -> MarkdownAlignment.NONE
    },
    header = isHeader,
)

private fun Node.toInlines(source: String): List<MarkdownInline> {
    val range = sourceRange()
    return when (this) {
        is Text -> listOf(MarkdownInlineText(literal, range.first, range.last + 1))
        is Emphasis -> listOf(MarkdownEmphasis(children().flatMap { it.toInlines(source) }, range.first, range.last + 1))
        is StrongEmphasis -> listOf(MarkdownStrong(children().flatMap { it.toInlines(source) }, range.first, range.last + 1))
        is org.commonmark.ext.gfm.strikethrough.Strikethrough -> listOf(
            MarkdownStrikethrough(children().flatMap { it.toInlines(source) }, range.first, range.last + 1),
        )
        is Code -> listOf(MarkdownInlineCode(literal, range.first, range.last + 1))
        is Link -> listOf(
            MarkdownLink(
                label = children().flatMap { it.toInlines(source) },
                destination = destination,
                title = title,
                safety = classifyMarkdownLink(destination),
                sourceStart = range.first,
                sourceEnd = range.last + 1,
            ),
        )
        is Image -> listOf(
            MarkdownImage(
                alt = children().flatMap { it.toInlines(source) },
                destination = destination,
                title = title,
                sourceStart = range.first,
                sourceEnd = range.last + 1,
            ),
        )
        is SoftLineBreak -> listOf(MarkdownSoftBreak(range.first, range.last + 1))
        is HardLineBreak -> listOf(MarkdownHardBreak(range.first, range.last + 1))
        is HtmlInline -> listOf(MarkdownRawInline(literal, range.first, range.last + 1))
        else -> listOf(MarkdownRawInline(source.substringSafe(range), range.first, range.last + 1))
    }
}

private fun Node.isSetextHeading(source: String): Boolean {
    if (this !is Heading || sourceSpans.size < 2) return false
    val underlineSpan = sourceSpans.maxByOrNull { it.lineIndex } ?: return false
    val lineStart = source.lastIndexOf('\n', underlineSpan.inputIndex - 1) + 1
    val lineEnd = source.indexOf('\n', underlineSpan.inputIndex).let { if (it < 0) source.length else it }
    val underline = source.substring(lineStart, lineEnd).removeSuffix("\r")
    return underline.matches(Regex("^[ \\t]{0,3}(?:=+|-+)[ \\t]*$"))
}

private fun String.substringSafe(range: IntRange): String {
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(start, length)
    return substring(start, end)
}