package com.vstokke.memos.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(markdown: String) {
    val linkColor = MaterialTheme.colorScheme.secondary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val text = buildAnnotatedString {
        markdown.lineSequence().forEachIndexed { index, sourceLine ->
            if (index > 0) append('\n')
            val trimmed = sourceLine.trimStart()
            val headingLevel = trimmed.takeWhile { it == '#' }.length.takeIf { it in 1..6 }
            val line = when {
                headingLevel != null && trimmed.getOrNull(headingLevel) == ' ' -> trimmed.drop(headingLevel + 1)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> "•  ${trimmed.drop(2)}"
                trimmed.startsWith("> ") -> "│  ${trimmed.drop(2)}"
                else -> sourceLine
            }
            val lineStyle = when {
                headingLevel == 1 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
                headingLevel in 2..3 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                headingLevel != null -> SpanStyle(fontWeight = FontWeight.Bold)
                trimmed.startsWith("> ") -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> SpanStyle()
            }
            pushStyle(lineStyle)
            appendInlineMarkdown(line, linkColor, codeBackground)
            pop()
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private val inlineToken = Regex("(`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|(?<!\\*)\\*[^*\\n]+\\*(?!\\*)|\\[[^]\\n]+]\\([^)]*\\))")

private fun AnnotatedString.Builder.appendInlineMarkdown(
    line: String,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
) {
    var position = 0
    inlineToken.findAll(line).forEach { match ->
        append(line.substring(position, match.range.first))
        val token = match.value
        when {
            token.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) { append(token.drop(1).dropLast(1)) }

            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.drop(2).dropLast(2))
            }

            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.drop(1).dropLast(1))
            }

            token.startsWith("[") -> withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ) { append(token.substringAfter('[').substringBefore(']')) }
        }
        position = match.range.last + 1
    }
    append(line.substring(position))
}
