package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.DisplayKind
import com.m57.hermescontrol.ui.chat.MessageRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        compose.onNodeWithTag("timeline_marker_body").assertIsDisplayed().assertTextEquals(detail)
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
        compose.onNodeWithTag("timeline_marker_body").assertDoesNotExist()
    }
}
