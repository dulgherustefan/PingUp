package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate

/** What keeps the radio from running, with the buttons that fix it, as each phone asks for it. */
@Composable
expect fun rememberRadioGate(vm: AppViewModel, radio: RadioStatus, onStartMesh: () -> Unit): RadioGate

/**
 * Asks for location only when the person does something that needs it. Only precise location counts: an approximate
 * one can't pick a zone. [onResult] says whether the position can be followed now.
 */
@Composable
expect fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit = {}): () -> Unit

/** What onboarding asks for once it ends: Bluetooth and notifications; then [onDone], whatever the answers. */
@Composable
expect fun rememberStartPermissions(onDone: () -> Unit): () -> Unit

/** Bluetooth needs the location permission too: Android below 12. */
expect val RadioNeedsLocation: Boolean

/** The control console keeps the screen in landscape while it's shown. */
@Composable
expect fun KeepLandscape()
