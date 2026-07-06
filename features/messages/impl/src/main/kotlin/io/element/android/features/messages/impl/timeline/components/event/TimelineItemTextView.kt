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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
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
            // Copy button for messages containing code blocks
            val codeTexts = remember(content) { extractCodeBlockTexts(content) }
            if (codeTexts.isNotEmpty()) {
                CodeBlockCopyButton(codeTexts)
            }
        }
    }
}

/**
 * Extracts code block text from the HTML document, preserving whitespace and line breaks.
 * Returns a list of (text, language) pairs.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun extractCodeBlockTexts(content: TimelineItemTextBasedContent): List<Pair<String, String>> {
    val doc = content.htmlDocument ?: return emptyList()
    val codeElements = doc.select("pre > code")
    if (codeElements.isEmpty()) return emptyList()
    return codeElements.map { element ->
        val text = org.jsoup.parser.Parser.unescapeEntities(element.html(), false)
        val lang = element.className().removePrefix("language-")
        text to lang
    }
}

/**
 * A copy button shown on messages with code blocks.
 * If one block, copies directly. If multiple, shows a menu to pick.
 */
@Composable
private fun CodeBlockCopyButton(codeTexts: List<Pair<String, String>>) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val singleBlock = codeTexts.size == 1

    IconButton(
        onClick = {
            if (singleBlock) {
                copyToClipboard(context, codeTexts.first().first)
                copied = true
            } else {
                menuExpanded = true
            }
        },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(2.dp)
            .size(32.dp),
    ) {
        Icon(
            imageVector = CompoundIcons.Copy(),
            contentDescription = if (copied) "Copied" else "Copy code",
            tint = ElementTheme.colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }

    if (codeTexts.size > 1) {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            codeTexts.forEachIndexed { index, (text, lang) ->
                val label = if (lang.isNotEmpty()) "复制 $lang" else "复制代码块 ${index + 1}"
                DropdownMenuItem(
                    text = { Text(label, style = ElementTheme.typography.fontBodyMdRegular) },
                    onClick = {
                        copyToClipboard(context, text)
                        copied = true
                        menuExpanded = false
                    },
                )
            }
            // Option to copy all
            if (codeTexts.size > 1) {
                DropdownMenuItem(
                    text = { Text("复制全部", style = ElementTheme.typography.fontBodyMdRegular) },
                    onClick = {
                        val all = codeTexts.joinToString("\n\n") { it.first }
                        copyToClipboard(context, all)
                        copied = true
                        menuExpanded = false
                    },
                )
            }
        }
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
