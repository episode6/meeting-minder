package com.episode6.meetingminder.ui.util

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test

// Copied from podcast-hacker alongside BasicMarkdown.kt.
class BasicMarkdownTest {

    @Test
    fun headings_carryTheirLevel() {
        val blocks = basicMarkdownToBlocks("# Title\n\n## Section\n\nbody")

        assertThat(blocks).hasSize(3)
        val h1 = blocks[0] as MarkdownBlock.Heading
        assertThat(h1.level).isEqualTo(1)
        assertThat(h1.text.text).isEqualTo("Title")
        val h2 = blocks[1] as MarkdownBlock.Heading
        assertThat(h2.level).isEqualTo(2)
        assertThat(h2.text.text).isEqualTo("Section")
        assertThat(blocks[2]).isInstanceOf<MarkdownBlock.Paragraph>()
    }

    @Test
    fun softWrappedParagraphLines_joinWithSpaces() {
        val blocks = basicMarkdownToBlocks("one\ntwo\nthree\n\nnext paragraph")

        assertThat(blocks).hasSize(2)
        assertThat(blocks[0].text.text).isEqualTo("one two three")
        assertThat(blocks[1].text.text).isEqualTo("next paragraph")
    }

    @Test
    fun bullets_includeIndentedContinuationLines() {
        val blocks = basicMarkdownToBlocks("- first item\n  wraps here\n- second item")

        assertThat(blocks).hasSize(2)
        val first = blocks[0] as MarkdownBlock.Bullet
        assertThat(first.text.text).isEqualTo("first item wraps here")
        val second = blocks[1] as MarkdownBlock.Bullet
        assertThat(second.text.text).isEqualTo("second item")
    }

    @Test
    fun boldAndCode_produceSpans() {
        val blocks = basicMarkdownToBlocks("plain **bold** and `mono` text")

        val text = blocks.single().text
        assertThat(text.text).isEqualTo("plain bold and mono text")
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertThat(text.text.substring(bold.start, bold.end)).isEqualTo("bold")
        val mono = text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertThat(text.text.substring(mono.start, mono.end)).isEqualTo("mono")
    }

    @Test
    fun links_becomeUrlAnnotations() {
        val blocks = basicMarkdownToBlocks("see [the docs](https://example.com/docs) please")

        val text = blocks.single().text
        assertThat(text.text).isEqualTo("see the docs please")
        val link = text.getLinkAnnotations(0, text.text.length).single()
        assertThat((link.item as LinkAnnotation.Url).url).isEqualTo("https://example.com/docs")
        assertThat(text.text.substring(link.start, link.end)).isEqualTo("the docs")
    }

    @Test
    fun autolinks_showTheBareUrl() {
        val blocks = basicMarkdownToBlocks("home: <https://example.com>")

        val text = blocks.single().text
        assertThat(text.text).isEqualTo("home: https://example.com")
        val link = text.getLinkAnnotations(0, text.text.length).single()
        assertThat((link.item as LinkAnnotation.Url).url).isEqualTo("https://example.com")
    }

    @Test
    fun unmatchedMarkers_passThroughAsLiterals() {
        val blocks = basicMarkdownToBlocks("a * lone star, an [orphan bracket, 1 < 2 and a `tick")

        assertThat(blocks.single().text.text)
            .isEqualTo("a * lone star, an [orphan bracket, 1 < 2 and a `tick")
    }

    @Test
    fun emptyInput_producesNoBlocks() {
        assertThat(basicMarkdownToBlocks("")).hasSize(0)
        assertThat(basicMarkdownToBlocks("\n\n\n")).hasSize(0)
    }
}
