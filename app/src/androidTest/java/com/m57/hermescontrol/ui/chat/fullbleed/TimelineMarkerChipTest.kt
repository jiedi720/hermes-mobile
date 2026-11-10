package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.DisplayKind
import com.m57.hermescontrol.ui.chat.MessageRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terminal panel is clickable, and `clickable` merges descendant semantics
 * into the panel node. Query the unmerged tree so the body / toggle / copy
 * nodes can be addressed individually.
 */
@RunWith(AndroidJUnit4::class)
class TimelineMarkerChipTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemNotificationShowsLabelAndDetail() {
        val detail = "Gateway runtime restarted; the session was reconnected."
        compose.setContent {
            HermesControlTheme {
                TimelineMarkerChip(
                    message =
                        ChatMessage(
                            role = MessageRole.USER,
                            content = detail,
                            displayKind = DisplayKind.INTERNAL_NOTIFICATION,
                        ),
                )
            }
        }
        compose.onNodeWithTag("timeline_marker").assertIsDisplayed()
        compose
            .onNodeWithTag("timeline_marker_body", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(detail)
    }

    @Test
    fun otherMarkersKeepTheChipAlone() {
        compose.setContent {
            HermesControlTheme {
                TimelineMarkerChip(
                    message =
                        ChatMessage(
                            role = MessageRole.USER,
                            content = "[System: The active model for this chat has changed to gpt-5.]",
                            displayKind = DisplayKind.MODEL_SWITCH,
                        ),
                )
            }
        }
        compose.onNodeWithTag("timeline_marker").assertIsDisplayed()
        compose.onNodeWithTag("timeline_marker_body", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("timeline_marker_panel", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("timeline_marker_copy", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun longNotificationIsCollapsedByDefaultAndExpandsOnClick() {
        val body = (1..9).joinToString("\n") { "line-$it" }
        val preview = "line-1 …"
        compose.setContent {
            HermesControlTheme {
                TimelineMarkerChip(
                    message =
                        ChatMessage(
                            id = "collapse-target",
                            role = MessageRole.USER,
                            content = body,
                            displayKind = DisplayKind.INTERNAL_NOTIFICATION,
                        ),
                )
            }
        }
        compose
            .onNodeWithTag("timeline_marker_body", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(preview)
        compose.onNodeWithTag("timeline_marker_toggle", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("timeline_marker_copy", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("timeline_marker_panel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("timeline_marker_body", useUnmergedTree = true).assertTextEquals(body)

        compose.onNodeWithTag("timeline_marker_toggle", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("timeline_marker_body", useUnmergedTree = true).assertTextEquals(preview)
    }

    @Test
    fun shortNotificationHasCopyButtonButNoExpandToggle() {
        compose.setContent {
            HermesControlTheme {
                TimelineMarkerChip(
                    message =
                        ChatMessage(
                            id = "short-target",
                            role = MessageRole.USER,
                            content = "one",
                            displayKind = DisplayKind.INTERNAL_NOTIFICATION,
                        ),
                )
            }
        }
        compose
            .onNodeWithTag("timeline_marker_body", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("one")
        compose.onNodeWithTag("timeline_marker_toggle", useUnmergedTree = true).assertDoesNotExist()
        compose
            .onNodeWithTag("timeline_marker_copy", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
    }
}
