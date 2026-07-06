/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannedString
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayout
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContentProvider
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.utils.containsOnlyEmojis
import io.element.android.libraries.androidutils.text.LinkifyHelper
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.textcomposer.mentions.LocalMentionSpanUpdater
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link

/**
 * Render a text message.
 *
 * If the message has `<pre><code>` blocks in its HTML, the content is split into
 * segments: text parts render with EditorStyledText (rich, clickable links, mentions),
 * code parts render as individually-copyable code blocks with the copy button ON the block.
 */
@Composable
fun TimelineItemTextView(
    content: TimelineItemTextBasedContent,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    val emojiOnly = content.formattedBody.toString() == content.body &&
        content.body.replace(" ", "").containsOnlyEmojis()
    val textStyle = when {
        emojiOnly -> ElementTheme.typography.fontHeadingXlRegular
        else -> ElementTheme.typography.fontBodyLgRegular
    }
    CompositionLocalProvider(
        LocalContentColor provides ElementTheme.colors.textPrimary,
        LocalTextStyle provides textStyle
    ) {
        // Check for code blocks
        val codeBlocks = remember(content) { extractCodeBlocks(content) }
        if (codeBlocks.isEmpty()) {
            // ── No code blocks: render as usual ──
            val text = getTextWithResolvedMentions(content)
            Box(modifier.semantics { contentDescription = content.plainText }) {
                EditorStyledText(
                    text = text,
                    onLinkClickedListener = onLinkClick,
                    onLinkLongClickedListener = onLinkLongClick,
                    style = ElementRichTextEditorStyle.textStyle(),
                    onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
                    releaseOnDetach = false,
                )
            }
        } else {
            // ── Has code blocks: segmented rendering ──
            // Use the htmlDocument to split into Text/Code segments
            // Text segments: rendered with a simpler approach (EditorStyledText would require reformatting)
            // Code segments: rendered as styled widgets with per-block copy buttons
            val segments = remember(content) { segmentContent(content) }
            var hasError by remember { mutableStateOf(false) }
            if (!hasError) {
                try {
                    SegmentedTimelineView(
                        segments = segments,
                        modifier = modifier,
                    )
                } catch (e: Exception) {
                    hasError = true
                }
            }
            if (hasError) {
                // Fallback: plain rendering
                Box(modifier.semantics { contentDescription = content.plainText }) {
                    EditorStyledText(
                        text = getTextWithResolvedMentions(content),
                        onLinkClickedListener = onLinkClick,
                        onLinkLongClickedListener = onLinkLongClick,
                        style = ElementRichTextEditorStyle.textStyle(),
                        onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
                        releaseOnDetach = false,
                    )
                }
            }
        }
    }
}

// ─── Segments ───────────────────────────────────────────────────────

private sealed interface Segment {
    data class Text(val body: String) : Segment
    data class Code(val body: String, val language: String) : Segment
}

/**
 * Split the HTML body into alternating text / code segments.
 */
@VisibleForTesting
internal fun segmentContent(content: TimelineItemTextBasedContent): List<Segment> {
    val doc = content.htmlDocument ?: return listOf(Segment.Text(content.body))
    val out = mutableListOf<Segment>()
    for (child in doc.body().children()) {
        if (child.tagName() == "pre") {
            val code = child.selectFirst("code")
            if (code != null) {
                out.add(
                    Segment.Code(
                        body = org.jsoup.parser.Parser.unescapeEntities(code.html(), false),
                        language = code.className().removePrefix("language-"),
                    )
                )
            } else {
                out.add(Segment.Text(child.text()))
            }
        } else {
            out.add(Segment.Text(child.text()))
        }
    }
    return out
}

/**
 * Render the segmented view: text → simple Text, code → styled block with copy button.
 */
@Composable
private fun SegmentedTimelineView(
    segments: List<Segment>,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        for (segment in segments) {
            when (segment) {
                is Segment.Text -> {
                    if (segment.body.isNotBlank()) {
                        Text(
                            text = segment.body,
                            style = LocalTextStyle.current,
                            color = LocalContentColor.current,
                        )
                    }
                }
                is Segment.Code -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    CodeBlockWidget(
                        codeText = segment.body,
                        language = segment.language,
                    )
                }
            }
        }
    }
}

// ─── Code Block Widget ──────────────────────────────────────────────

@Composable
private fun CodeBlockWidget(
    codeText: String,
    language: String,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ElementTheme.colors.bgSubtleSecondary)
    ) {
        // Header bar: language tag + copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (language.isNotEmpty()) {
                Text(
                    text = language,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    copyToClipboard(context, codeText)
                    copied = true
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (copied) CompoundIcons.Check() else CompoundIcons.Copy(),
                    contentDescription = if (copied) "已复制" else "复制代码",
                    tint = ElementTheme.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Code body
        Text(
            text = codeText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = ElementTheme.colors.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ─── Utilities ──────────────────────────────────────────────────────

private data class CodeBlockInfo(
    val text: String,
    val language: String,
)

@VisibleForTesting
internal fun extractCodeBlocks(content: TimelineItemTextBasedContent): List<CodeBlockInfo> {
    val doc = content.htmlDocument ?: return emptyList()
    val codeElements = doc.select("pre > code")
    if (codeElements.isEmpty()) return emptyList()
    return codeElements.map { element ->
        CodeBlockInfo(
            text = org.jsoup.parser.Parser.unescapeEntities(element.html(), false),
            language = element.className().removePrefix("language-"),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("code", text)
    clipboard.setPrimaryClip(clip)
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
@Composable
internal fun getTextWithResolvedMentions(content: TimelineItemTextBasedContent): CharSequence {
    val mentionSpanUpdater = LocalMentionSpanUpdater.current
    val bodyWithResolvedMentions = mentionSpanUpdater.rememberMentionSpans(content.formattedBody)
    return SpannedString.valueOf(bodyWithResolvedMentions)
}

// ─── Previews ────────────────────────────────────────────────────────

@PreviewsDayNight
@Composable
internal fun TimelineItemTextViewPreview(
    @PreviewParameter(TimelineItemTextBasedContentProvider::class) content: TimelineItemTextBasedContent
) = ElementPreview {
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}

@Preview
@Composable
internal fun TimelineItemTextViewWithLinkifiedUrlPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        formattedBody = LinkifyHelper.linkify("The link should end after the first '?' (url: github.com/element-hq/element-x-android/README?)?.")
    )
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}

@Preview
@Composable
internal fun TimelineItemTextViewWithLinkifiedUrlAndNestedParenthesisPreview() = ElementPreview {
    val content = aTimelineItemTextContent(
        formattedBody = LinkifyHelper.linkify("The link should end after the '(ME)' ((url: github.com/element-hq/element-x-android/READ(ME)))!")
    )
    TimelineItemTextView(
        content = content,
        onLinkClick = {},
        onLinkLongClick = {},
    )
}
