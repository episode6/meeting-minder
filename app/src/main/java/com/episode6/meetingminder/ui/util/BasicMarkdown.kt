package com.episode6.meetingminder.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

// Copied from headache-tracker (ui/util/BasicMarkdown.kt); keep the two in sync when fixing bugs.

/** A block-level element parsed from basic markdown; see [basicMarkdownToBlocks]. */
internal sealed interface MarkdownBlock {
    val text: AnnotatedString

    data class Heading(val level: Int, override val text: AnnotatedString) : MarkdownBlock
    data class Paragraph(override val text: AnnotatedString) : MarkdownBlock
    data class Bullet(override val text: AnnotatedString) : MarkdownBlock
}

/**
 * Tolerant conversion of basic markdown (the subset used by THIRD_PARTY_LICENSES.md) to
 * a list of [MarkdownBlock]s the ui can style individually. Supports `#`/`##`/… headings,
 * `-`/`*` bullets (with indented continuation lines), and paragraphs whose soft-wrapped
 * lines are joined; inline it handles **bold**, `code`, [text](url) links and <url>
 * autolinks. Anything unrecognized passes through as literal text; never throws.
 */
internal fun basicMarkdownToBlocks(markdown: String, linkColor: Color = Color.Unspecified): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    // accumulates the soft-wrapped lines of the paragraph/bullet currently being built
    var pending: StringBuilder? = null
    var pendingIsBullet = false

    fun flushPending() {
        val text = pending?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val inline = parseInline(text, linkColor)
            blocks += if (pendingIsBullet) MarkdownBlock.Bullet(inline) else MarkdownBlock.Paragraph(inline)
        }
        pending = null
        pendingIsBullet = false
    }

    for (rawLine in markdown.lines()) {
        val line = rawLine.trimEnd()
        val trimmed = line.trimStart()
        val headingLevel = line.takeWhile { it == '#' }.length
        when {
            line.isBlank() -> flushPending()
            headingLevel in 1..6 && line.getOrNull(headingLevel) == ' ' -> {
                flushPending()
                blocks += MarkdownBlock.Heading(headingLevel, parseInline(line.drop(headingLevel + 1).trim(), linkColor))
            }
            (trimmed.startsWith("- ") || trimmed.startsWith("* ")) -> {
                flushPending()
                pending = StringBuilder(trimmed.drop(2))
                pendingIsBullet = true
            }
            else -> {
                // continuation of the current paragraph/bullet (or the start of a paragraph)
                pending?.append(' ')?.append(trimmed) ?: run { pending = StringBuilder(trimmed) }
            }
        }
    }
    flushPending()
    return blocks
}

private val LINK_PATTERN = Regex("""^\[([^\]]*)]\(([^)\s]+)\)""")
private val AUTOLINK_PATTERN = Regex("""^<(https?://[^>\s]+)>""")

private fun parseInline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)

    fun appendLink(label: String, url: String) {
        pushLink(LinkAnnotation.Url(url))
        pushStyle(linkStyle)
        append(label)
        pop()
        pop()
    }

    var i = 0
    while (i < text.length) {
        val boldEnd = if (text.startsWith("**", i)) text.indexOf("**", startIndex = i + 2) else -1
        val codeEnd = if (text[i] == '`') text.indexOf('`', startIndex = i + 1) else -1
        val link = if (text[i] == '[') LINK_PATTERN.find(text.substring(i)) else null
        val autolink = if (text[i] == '<') AUTOLINK_PATTERN.find(text.substring(i)) else null
        when {
            boldEnd != -1 -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(text.substring(i + 2, boldEnd))
                pop()
                i = boldEnd + 2
            }
            codeEnd != -1 -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(text.substring(i + 1, codeEnd))
                pop()
                i = codeEnd + 1
            }
            link != null -> {
                appendLink(label = link.groupValues[1], url = link.groupValues[2])
                i += link.value.length
            }
            autolink != null -> {
                appendLink(label = autolink.groupValues[1], url = autolink.groupValues[1])
                i += autolink.value.length
            }
            else -> {
                // unmatched markers (a lone `*`, `[`, etc.) fall through as literal text
                append(text[i])
                i++
            }
        }
    }
}
