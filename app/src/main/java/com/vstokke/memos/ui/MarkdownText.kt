package com.vstokke.memos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private const val LINK_ANNOTATION = "memos-pocket-link"

/**
 * Renders the parsed Markdown model without executing HTML or loading remote content.
 * The optional task callback is deliberately separate from the parser model so read-only
 * cards can still show checked state without exposing a write action.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onTaskToggle: ((TaskRef, Boolean) -> Unit)? = null,
    taskEnabled: Boolean = false,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val document = remember(markdown) { parseMarkdown(markdown) }
    val context = LocalContext.current
    val openLink = remember(context, onOpenLink) {
        onOpenLink ?: { destination -> openMarkdownLink(context, destination) }
    }
    val colors = MarkdownColors(
        text = MaterialTheme.colorScheme.onSurface,
        secondary = MaterialTheme.colorScheme.onSurfaceVariant,
        link = MaterialTheme.colorScheme.secondary,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        quote = MaterialTheme.colorScheme.primary,
        rule = MaterialTheme.colorScheme.outlineVariant,
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        document.blocks.forEach { block ->
            MarkdownBlockContent(block, colors, onTaskToggle, taskEnabled, openLink)
        }
    }
}

private data class MarkdownColors(
    val text: Color,
    val secondary: Color,
    val link: Color,
    val codeBackground: Color,
    val quote: Color,
    val rule: Color,
)

@Composable
private fun MarkdownBlockContent(
    block: MarkdownBlock,
    colors: MarkdownColors,
    onTaskToggle: ((TaskRef, Boolean) -> Unit)?,
    taskEnabled: Boolean,
    onOpenLink: (String) -> Unit,
) {
    when (block) {
        is MarkdownParagraph -> MarkdownInlineContent(block.inlines, colors, onOpenLink)
        is MarkdownHeading -> MarkdownInlineContent(
            inlines = block.inlines,
            colors = colors,
            onOpenLink = onOpenLink,
            style = headingStyle(block.level),
        )
        is MarkdownBlockQuote -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(3.dp).height(28.dp).background(colors.quote))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.blocks.forEach { child ->
                    MarkdownBlockContent(child, colors, onTaskToggle, taskEnabled, onOpenLink)
                }
            }
        }
        is MarkdownList -> MarkdownListContent(block, colors, onTaskToggle, taskEnabled, onOpenLink)
        is MarkdownCodeBlock -> CodeBlockContent(block, colors)
        is MarkdownThematicBreak -> HorizontalDivider(color = colors.rule)
        is MarkdownTable -> MarkdownTableContent(block, colors, onOpenLink)
        is MarkdownRawBlock -> Surface(
            color = colors.codeBackground,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                block.literal,
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        4, 5, 6 -> typography.titleSmall
        else -> typography.titleSmall
    }
}

@Composable
private fun MarkdownListContent(
    list: MarkdownList,
    colors: MarkdownColors,
    onTaskToggle: ((TaskRef, Boolean) -> Unit)?,
    taskEnabled: Boolean,
    onOpenLink: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (list.tight) 4.dp else 8.dp),
    ) {
        list.items.forEachIndexed { index, item ->
            val marker = if (list.ordered) "${list.startNumber + index}." else "•"
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (item.task == null) {
                    Text(
                        marker,
                        color = colors.secondary,
                        modifier = Modifier.width(if (list.ordered) 28.dp else 20.dp),
                    )
                } else {
                    val canToggleTask = taskEnabled && onTaskToggle != null
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(48.dp)
                            .padding(end = 4.dp)
                            .toggleable(
                                value = item.task.checked,
                                enabled = canToggleTask,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    onTaskToggle?.invoke(item.task, checked)
                                },
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Checkbox(
                            checked = item.task.checked,
                            onCheckedChange = null,
                            enabled = canToggleTask,
                        )
                    }
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.blocks.forEach { child ->
                        MarkdownBlockContent(child, colors, onTaskToggle, taskEnabled, onOpenLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockContent(block: MarkdownCodeBlock, colors: MarkdownColors) {
    Surface(
        color = colors.codeBackground,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.info?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.secondary)
            }
            Text(
                block.literal,
                color = colors.text,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MarkdownTableContent(
    table: MarkdownTable,
    colors: MarkdownColors,
    onOpenLink: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.widthIn(min = 240.dp)) {
            if (table.header.isNotEmpty()) {
                TableRowContent(table.header, colors, onOpenLink, header = true)
                HorizontalDivider(color = colors.rule)
            }
            table.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = colors.rule)
                TableRowContent(row, colors, onOpenLink, header = false)
            }
        }
    }
}

@Composable
private fun TableRowContent(
    cells: List<MarkdownTableCell>,
    colors: MarkdownColors,
    onOpenLink: (String) -> Unit,
    header: Boolean,
) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Box(
                Modifier.widthIn(min = 120.dp).weight(1f, fill = false).padding(8.dp),
                contentAlignment = when (cell.alignment) {
                    MarkdownAlignment.RIGHT -> Alignment.CenterEnd
                    MarkdownAlignment.CENTER -> Alignment.Center
                    MarkdownAlignment.LEFT, MarkdownAlignment.NONE -> Alignment.CenterStart
                },
            ) {
                MarkdownInlineContent(
                    inlines = cell.inlines,
                    colors = colors,
                    onOpenLink = onOpenLink,
                    style = if (header) TextStyle(fontWeight = FontWeight.SemiBold) else TextStyle(),
                )
            }
        }
    }
}

@Composable
private fun MarkdownInlineContent(
    inlines: List<MarkdownInline>,
    colors: MarkdownColors,
    onOpenLink: (String) -> Unit,
    style: TextStyle = TextStyle(),
) {
    val annotated = remember(inlines, colors, style) {
        buildAnnotatedString {
            inlines.forEach { appendInline(it, colors) }
        }
    }
    val contentStyle = MaterialTheme.typography.bodyLarge.merge(style)
    val hasLinks = annotated.getStringAnnotations(LINK_ANNOTATION, 0, annotated.length).isNotEmpty()
    if (hasLinks) {
        ClickableText(
            text = annotated,
            style = contentStyle.copy(color = colors.text),
            onClick = { offset ->
                annotated.getStringAnnotations(LINK_ANNOTATION, offset, offset)
                    .firstOrNull()?.item
                    ?.let { destination -> runCatching { onOpenLink(destination) } }
            },
        )
    } else {
        Text(text = annotated, style = contentStyle.copy(color = colors.text))
    }
}

private fun AnnotatedString.Builder.appendInline(inline: MarkdownInline, colors: MarkdownColors) {
    when (inline) {
        is MarkdownInlineText -> append(inline.literal)
        is MarkdownEmphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            inline.children.forEach { appendInline(it, colors) }
        }
        is MarkdownStrong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            inline.children.forEach { appendInline(it, colors) }
        }
        is MarkdownStrikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            inline.children.forEach { appendInline(it, colors) }
        }
        is MarkdownInlineCode -> withStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground),
        ) { append(inline.literal) }
        is MarkdownLink -> {
            val start = length
            withStyle(
                SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline),
            ) { inline.label.forEach { appendInline(it, colors) } }
            if (inline.safety != MarkdownLinkSafety.UNSAFE && start < length) {
                addStringAnnotation(LINK_ANNOTATION, inline.destination, start, length)
            }
        }
        is MarkdownImage -> {
            append("[Image: ")
            append(inline.alt.joinToString(separator = "") { it.plainText() }.ifBlank { "untitled" })
            append("]")
            if (inline.destination.isNotBlank()) append(" (${inline.destination})")
        }
        is MarkdownSoftBreak, is MarkdownHardBreak -> append('\n')
        is MarkdownRawInline -> append(inline.literal)
    }
}

private fun MarkdownInline.plainText(): String = when (this) {
    is MarkdownInlineText -> literal
    is MarkdownEmphasis -> children.joinToString(separator = "") { it.plainText() }
    is MarkdownStrong -> children.joinToString(separator = "") { it.plainText() }
    is MarkdownStrikethrough -> children.joinToString(separator = "") { it.plainText() }
    is MarkdownInlineCode -> literal
    is MarkdownLink -> label.joinToString(separator = "") { it.plainText() }
    is MarkdownImage -> alt.joinToString(separator = "") { it.plainText() }
    is MarkdownSoftBreak, is MarkdownHardBreak -> "\n"
    is MarkdownRawInline -> literal
}

private fun openMarkdownLink(context: Context, destination: String) {
    if (!isSafeMarkdownLink(destination)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(destination)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser is available for this link.", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "The link could not be opened.", Toast.LENGTH_SHORT).show()
    }
}
