package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color

/**
 * Fixed CMD / terminal palette for `internal_notification` transcript rows.
 * Intentionally not derived from the Material color scheme: the notification
 * body is machine output and must stay black-on-amber in both light and dark
 * themes. This file lives in `theme/` so the hardcoded-color guard (issue #622)
 * permits the literals.
 */
internal val TerminalBackground = Color(0xFF000000)
internal val TerminalForeground = Color(0xFFFFCC00)
