package com.m57.hermescontrol.ui.chat.fullbleed

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.TerminalBackground
import com.m57.hermescontrol.theme.TerminalForeground
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.DisplayKind
import kotlinx.coroutines.launch

/**
 * Friendly label resource for a `display_kind` timeline marker (issue #904).
 * Returns `null` for kinds we don't have a label for — the caller falls back
 * to the generic system-event rendering (raw content) instead of inventing
 * copy for a marker we don't understand.
 */
internal fun timelineMarkerLabelRes(kind: String?): Int? =
    when (kind) {
        DisplayKind.MODEL_SWITCH -> R.string.timeline_marker_model_switch
        DisplayKind.PERSONALITY_SWITCH -> R.string.timeline_marker_personality_switch
        DisplayKind.AUTO_CONTINUE -> R.string.timeline_marker_auto_continue
        DisplayKind.ASYNC_DELEGATION_COMPLETE -> R.string.timeline_marker_delegation_complete
        DisplayKind.SKILL_INVOCATION -> R.string.timeline_marker_skill_invocation
        DisplayKind.INTERNAL_NOTIFICATION -> R.string.timeline_marker_internal_notification
        DisplayKind.MAX_ITERATIONS_REACHED -> R.string.timeline_marker_max_iterations
        else -> null
    }

/**
 * Model name parsed from a model-switch marker's content, e.g.
 * `[System: The active model for this chat has changed to gpt-5 via provider
 * openai. ...]` → `gpt-5`. The marker text is model-facing scaffolding — the
 * chip shows the human-meaningful name instead of the whole block. Interior
 * dots are kept (`gpt-4.5`); a sentence-ending dot is stripped.
 */
internal fun markerModelFromContent(content: String): String? {
    val raw = MARKER_MODEL_REGEX.find(content)?.groupValues?.getOrNull(1) ?: return null
    val model = raw.removeSuffix(".").trim()
    return model.takeIf { it.isNotBlank() }
}

private val MARKER_MODEL_REGEX = Regex("""changed to ([A-Za-z0-9_.\-]+)""")

/**
 * Detail to show under a marker's friendly label. Only `internal_notification`
 * carries human-meaningful detail in [ChatMessage.content]; the other marker
 * contents are model-facing scaffolding that the chip intentionally replaces
 * with a short label. Returns `null` when there is no detail to render.
 */
internal fun timelineMarkerBody(message: ChatMessage): String? {
    if (message.displayKind != DisplayKind.INTERNAL_NOTIFICATION) return null
    return message.content.trim().takeIf { it.isNotBlank() }
}

internal const val NOTIFICATION_COLLAPSED_LINES = 4

/** Short preview for a collapsed notification; returns [body] unchanged when it already fits. */
internal fun collapsedNotificationPreview(body: String): String {
    val lines = body.lines()
    if (lines.size <= NOTIFICATION_COLLAPSED_LINES) return body
    return lines.take(NOTIFICATION_COLLAPSED_LINES).joinToString("\n") + "\n…"
}

/**
 * Centered timeline chip for a `display_kind` marker (issue #904): model
 * switches, personality switches and auto-continues render as a small
 * divider-style chip instead of a fake user bubble. Known kinds get friendly
 * copy; the model-switch chip names the model when the marker content
 * carries it. System notifications keep their detail under the label in a
 * compact, collapsible CMD-style panel (black background, amber monospace
 * text) that looks identical in light and dark themes.
 */
@Composable
internal fun TimelineMarkerChip(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val labelRes = timelineMarkerLabelRes(message.displayKind) ?: return
    val text =
        if (message.displayKind == DisplayKind.MODEL_SWITCH) {
            val model = markerModelFromContent(message.content)
            if (model != null) {
                stringResource(R.string.timeline_marker_model_switch_to, model)
            } else {
                stringResource(R.string.timeline_marker_model_switch)
            }
        } else {
            stringResource(labelRes)
        }
    val body = timelineMarkerBody(message)
    val labelStyle =
        if (body != null) {
            MaterialTheme.typography.labelMedium
        } else {
            MaterialTheme.typography.labelSmall
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.testTag("timeline_marker"),
        ) {
            Text(
                text = text,
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (body != null) {
            NotificationTerminalPanel(body = body, messageId = message.id)
        }
    }
}

@Composable
private fun NotificationTerminalPanel(
    body: String,
    messageId: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable("$messageId-expanded") { mutableStateOf(false) }
    var copied by rememberSaveable("$messageId-copied") { mutableStateOf(false) }
    val preview = collapsedNotificationPreview(body)
    val collapsible = preview != body
    val shownText = if (expanded || !collapsible) body else preview
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = TerminalBackground,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp)
                .clickable(enabled = collapsible) { expanded = !expanded }
                .testTag("timeline_marker_panel"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = shownText,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                color = TerminalForeground,
                textAlign = TextAlign.Start,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("timeline_marker_body"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (collapsible) {
                    Row(
                        modifier =
                            Modifier
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("timeline_marker_toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = TerminalForeground,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text =
                                stringResource(
                                    if (expanded) {
                                        R.string.chat_tool_group_collapse
                                    } else {
                                        R.string.chat_tool_group_expand
                                    },
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = TerminalForeground,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, body)))
                        }
                        copied = true
                    },
                    modifier =
                        Modifier
                            .size(28.dp)
                            .testTag("timeline_marker_copy"),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy),
                        tint = TerminalForeground,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
