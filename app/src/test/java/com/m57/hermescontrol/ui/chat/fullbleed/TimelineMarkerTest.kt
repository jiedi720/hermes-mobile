package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.DisplayKind
import com.m57.hermescontrol.ui.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineMarkerTest {
    @Test
    fun knownKindsMapToLabels() {
        assertEquals(R.string.timeline_marker_model_switch, timelineMarkerLabelRes("model_switch"))
        assertEquals(
            R.string.timeline_marker_personality_switch,
            timelineMarkerLabelRes("personality_switch"),
        )
        assertEquals(R.string.timeline_marker_auto_continue, timelineMarkerLabelRes("auto_continue"))
        assertEquals(
            R.string.timeline_marker_delegation_complete,
            timelineMarkerLabelRes("async_delegation_complete"),
        )
        assertEquals(
            R.string.timeline_marker_skill_invocation,
            timelineMarkerLabelRes("skill_invocation"),
        )
        assertEquals(
            R.string.timeline_marker_internal_notification,
            timelineMarkerLabelRes("internal_notification"),
        )
        assertEquals(
            R.string.timeline_marker_max_iterations,
            timelineMarkerLabelRes("max_iterations_reached"),
        )
    }

    @Test
    fun unknownAndNullKindsHaveNoLabel() {
        assertNull(timelineMarkerLabelRes("hidden"))
        assertNull(timelineMarkerLabelRes("some_future_kind"))
        assertNull(timelineMarkerLabelRes(null))
        assertNull(timelineMarkerLabelRes(""))
    }

    @Test
    fun modelParsedFromMarkerContent() {
        assertEquals(
            "gpt-5",
            markerModelFromContent(
                "[System: The active model for this chat has changed to gpt-5 via provider openai. ...]",
            ),
        )
        assertEquals(
            "deepseek-v4",
            markerModelFromContent(
                "The active model for this chat has changed to deepseek-v4. From this point forward...",
            ),
        )
        assertEquals(
            "gpt-4.5",
            markerModelFromContent(
                "The active model for this chat has changed to gpt-4.5 via provider openai.",
            ),
        )
    }

    @Test
    fun modelParseHandlesMissingOrOddContent() {
        assertNull(markerModelFromContent(""))
        assertNull(markerModelFromContent("[System: personality switched]"))
        assertNull(markerModelFromContent("changed to"))
    }

    @Test
    fun internalNotificationKeepsItsDetail() {
        val message =
            ChatMessage(
                role = MessageRole.USER,
                content = "  Gateway runtime restarted; the session was reconnected.  ",
                displayKind = DisplayKind.INTERNAL_NOTIFICATION,
            )
        assertEquals(
            "Gateway runtime restarted; the session was reconnected.",
            timelineMarkerBody(message),
        )
    }

    @Test
    fun otherMarkersDoNotExposeScaffoldingAsDetail() {
        assertNull(
            timelineMarkerBody(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "[System: The active model for this chat has changed to gpt-5.]",
                    displayKind = DisplayKind.MODEL_SWITCH,
                ),
            ),
        )
        assertNull(
            timelineMarkerBody(
                ChatMessage(role = MessageRole.USER, content = "plain user message"),
            ),
        )
    }

    @Test
    fun blankNotificationDetailIsNotRendered() {
        assertNull(
            timelineMarkerBody(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "   \n  ",
                    displayKind = DisplayKind.INTERNAL_NOTIFICATION,
                ),
            ),
        )
    }

    @Test
    fun notificationsWithinTheLineLimitAreNotCollapsed() {
        val short = "one\ntwo"
        val exactlyAtLimit = (1..NOTIFICATION_COLLAPSED_LINES).joinToString("\n") { "line-$it" }
        assertEquals(short, collapsedNotificationPreview(short))
        assertEquals(exactlyAtLimit, collapsedNotificationPreview(exactlyAtLimit))
    }

    @Test
    fun longNotificationPreviewKeepsFirstLinesAndAMarker() {
        val body = (1..9).joinToString("\n") { "line-$it" }
        assertEquals(
            "line-1\nline-2\nline-3\nline-4\n…",
            collapsedNotificationPreview(body),
        )
    }
}
