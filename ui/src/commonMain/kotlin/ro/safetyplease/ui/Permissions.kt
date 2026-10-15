package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate
import org.jetbrains.compose.resources.StringResource

/** What keeps the radio from running, with the buttons that fix it, as each phone asks for it. */
@Composable
expect fun rememberRadioGate(vm: AppViewModel, radio: RadioStatus, onStartMesh: () -> Unit): RadioGate

/**
 * Asks for location only when the person does something that needs it. Only precise location counts: an approximate
 * one can't pick a zone. [onResult] says whether the position can be followed now.
 */
@Composable
expect fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit = {}): () -> Unit

/**
 * What onboarding asks for, one system question after the other: what the network needs first (Bluetooth, and location
 * on Android below 12), then notifications. The network starts once Bluetooth is answered, and [onDone] runs when every
 * question is, whatever the answers.
 */
@Composable
expect fun rememberStartPermissions(onStartMesh: () -> Unit, onDone: () -> Unit): () -> Unit

/** Whether the page that explains those questions also offers to skip them for now. */
expect val PermissionsSkippable: Boolean

/** Bluetooth needs the location permission too: Android below 12. */
expect val RadioNeedsLocation: Boolean

/**
 * A phone the others can't find is worth a word: on Android its advertising failed, while no iPhone can advertise what
 * the others need, and there it's simply how the network works.
 */
expect val UnfindableIsAProblem: Boolean

/**
 * What the network switch says when on. On an iPhone it also asks not to close the app in the app switcher: iOS
 * doesn't start an app closed that way again for Bluetooth.
 */
expect val MeshOnText: StringResource

/** Why the app asks for notifications; on Android one of them also stays while the mesh runs. */
expect val NotificationsReason: StringResource

/**
 * Whether the phone shows the app's notifications, asked again each time the app comes back to the front: turned off,
 * they no longer tell a reporter that staff answered, or to look for help elsewhere.
 */
@Composable
expect fun rememberNotificationsAllowed(): Boolean

/**
 * A console is used in landscape and stays on, as long as the phone is the console, Me sheet included. A rotation keeps
 * it, and large screens may ignore the orientation.
 */
@Composable
expect fun ConsoleWindow()
