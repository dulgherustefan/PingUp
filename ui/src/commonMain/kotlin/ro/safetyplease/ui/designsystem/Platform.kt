package ro.safetyplease.ui.designsystem

import androidx.compose.runtime.Composable

/** Blurring what's behind a bar: Android 12 and later, and iOS; without it glass is nearly opaque. */
expect val CanBlur: Boolean

/** The phone asks for less motion: "Remove animations" on Android, "Reduce Motion" on iOS. Transitions become fades. */
@Composable
expect fun rememberReduceMotion(): Boolean

/**
 * Lists bounce at their ends on the iPhone, as in every iPhone app; without it they feel stuck. On Android they keep
 * stopping at their ends, without the stretch.
 */
expect val EdgeBounce: Boolean
