package ro.safetyplease.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.NotificationsReason
import ro.safetyplease.ui.PermissionsSkippable
import ro.safetyplease.ui.RadioNeedsLocation
import ro.safetyplease.ui.common.BackHandler
import ro.safetyplease.ui.common.MAX_NAME
import ro.safetyplease.ui.common.NetworkDot
import ro.safetyplease.ui.common.RadioProblemBanner
import ro.safetyplease.ui.common.networkText
import ro.safetyplease.ui.common.problem
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GlassSize
import ro.safetyplease.ui.designsystem.IconCircle
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.LocalTextOnly
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.NavHeight
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.avatarIndex
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.initials
import ro.safetyplease.ui.designsystem.largeFont
import ro.safetyplease.ui.designsystem.largeTitle
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.rememberRadioGate
import ro.safetyplease.ui.rememberStartPermissions
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.messages_empty_action
import ro.safetyplease.ui.resources.name_hint
import ro.safetyplease.ui.resources.name_placeholder
import ro.safetyplease.ui.resources.name_reserved
import ro.safetyplease.ui.resources.next
import ro.safetyplease.ui.resources.onboarding_begin
import ro.safetyplease.ui.resources.onboarding_intro
import ro.safetyplease.ui.resources.onboarding_later
import ro.safetyplease.ui.resources.onboarding_later_camera
import ro.safetyplease.ui.resources.onboarding_name_text
import ro.safetyplease.ui.resources.onboarding_name_title
import ro.safetyplease.ui.resources.onboarding_not_112
import ro.safetyplease.ui.resources.onboarding_not_now
import ro.safetyplease.ui.resources.onboarding_permissions
import ro.safetyplease.ui.resources.onboarding_permissions_text
import ro.safetyplease.ui.resources.onboarding_progress
import ro.safetyplease.ui.resources.onboarding_ready_alone
import ro.safetyplease.ui.resources.onboarding_ready_later
import ro.safetyplease.ui.resources.onboarding_ready_text
import ro.safetyplease.ui.resources.onboarding_ready_title
import ro.safetyplease.ui.resources.onboarding_welcome
import ro.safetyplease.ui.resources.perm_bluetooth_text
import ro.safetyplease.ui.resources.perm_bluetooth_title
import ro.safetyplease.ui.resources.perm_location_text
import ro.safetyplease.ui.resources.perm_location_title
import ro.safetyplease.ui.resources.perm_notifications_title

/** The onboarding's pages, in order. */
enum class OnboardingStep {
    WELCOME, NAME, PERMISSIONS, READY;

    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)
    val previous: OnboardingStep? get() = entries.getOrNull(ordinal - 1)

    /** Its place among the pages that set the app up, which the dots on top count; the welcome isn't one of them. */
    val progress: Int? get() = if (this == WELCOME) null else ordinal - 1

    companion object {
        val COUNTED: Int get() = entries.size - 1
    }
}

/** Onboarding page margin, wider than list margins. */
private val PageGutter = 24.dp

/**
 * Onboarding: what the app is, shown as a message hopping from phone to phone; the person's name, its avatar drawn as
 * they type; Bluetooth and notifications, each explained before the system asks; then the network found around, and
 * the first friend to add. Camera and location are asked when they're needed.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var current by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[current]
    // a name typed before the app was closed comes back
    var name by rememberSaveable { mutableStateOf(settings.nickname) }
    val reduce = LocalReduceMotion.current
    val focus = LocalFocusManager.current
    val go = { to: OnboardingStep ->
        // the keyboard goes down while the next page slides in, not after
        focus.clearFocus()
        current = to.ordinal
    }
    val finish = {
        vm.finishOnboarding(name)
        // already running unless the app was closed on the last page; starting it again only retries the radio
        onStartMesh()
    }
    // the network starts once the person answered: the last page shows the phones it finds
    val askPermissions = rememberStartPermissions(onStartMesh) { go(OnboardingStep.READY) }
    BackHandler(enabled = step.previous != null) { step.previous?.let(go) }

    AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val push = Motion.Push
            when {
                reduce -> fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))
                // the next page slides in from the right while the one it covers shifts a third to the left
                targetState > initialState -> slideInHorizontally(push) { it } togetherWith slideOutHorizontally(push) { -it / 3 }
                else -> (slideInHorizontally(push) { -it / 3 } togetherWith slideOutHorizontally(push) { it }).apply { targetContentZIndex = -1f }
            }
        },
        label = "onboarding",
    ) { page ->
        when (page) {
            OnboardingStep.WELCOME -> WelcomePage { go(OnboardingStep.NAME) }
            OnboardingStep.NAME -> NamePage(
                vm, name, { name = it.take(MAX_NAME) }, onBack = { go(OnboardingStep.WELCOME) },
                onNext = {
                    // saved now, so the friend code and the network already carry it
                    vm.setNickname(name)
                    go(OnboardingStep.PERMISSIONS)
                },
            )
            OnboardingStep.PERMISSIONS -> PermissionsPage(
                onBack = { go(OnboardingStep.NAME) }, onContinue = askPermissions, onSkip = { go(OnboardingStep.READY) },
            )
            OnboardingStep.READY -> ReadyPage(
                vm, name, onStartMesh, onBack = { go(OnboardingStep.PERMISSIONS) },
                onAddFriend = {
                    finish()
                    vm.open(Dest.AddFriend)
                },
                onLater = finish,
            )
        }
    }
}

@Composable
private fun WelcomePage(onBegin: () -> Unit) {
    val colors = AppTheme.colors
    val title = stringResource(Res.string.onboarding_welcome)
    StepPage(
        step = OnboardingStep.WELCOME, title = title, onBack = null,
        bottom = { AppButton(stringResource(Res.string.onboarding_begin), onBegin, Modifier.fillMaxWidth()) },
        hero = {
            if (!LocalTextOnly.current) {
                // edge to edge: the phones reach the sides of the screen
                Box(Modifier.fillMaxWidth().aspectRatio(if (largeFont()) 2f else 1.35f).heightIn(max = 320.dp)) {
                    MeshPicture(Modifier.fillMaxSize().padding(top = 36.dp, bottom = 8.dp))
                    NoSignalPill(Modifier.align(Alignment.TopCenter))
                }
            }
        },
    ) {
        PageTitle(title, Modifier.padding(top = 20.dp))
        PageText(stringResource(Res.string.onboarding_intro), Modifier.padding(top = 12.dp))
        Row(
            Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fill)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Sym.Info, null, Modifier.padding(top = 1.dp).size(18.dp), tint = colors.secondaryLabel)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(Res.string.onboarding_not_112), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
        }
    }
}

@Composable
private fun NamePage(vm: AppViewModel, name: String, onName: (String) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val title = stringResource(Res.string.onboarding_name_title)
    val allowed = vm.nameAllowed(name)
    val ready = name.isNotBlank() && allowed
    val field = remember { FocusRequester() }
    // the keyboard comes up once the page has slid in, so the two don't move at the same time
    LaunchedEffect(Unit) {
        delay(Motion.PUSH_SETTLE.toLong())
        runCatching { field.requestFocus() }
    }
    StepPage(
        step = OnboardingStep.NAME, title = title, onBack = onBack,
        bottom = { AppButton(stringResource(Res.string.next), onNext, Modifier.fillMaxWidth(), enabled = ready) },
        centered = false,
    ) {
        LiveAvatar(name, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
        PageTitle(title, Modifier.padding(top = 24.dp))
        PageText(stringResource(Res.string.onboarding_name_text), Modifier.padding(top = 10.dp))
        InputField(
            name, onName, stringResource(Res.string.name_placeholder), Modifier.fillMaxWidth().padding(top = 28.dp).focusRequester(field),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ready) onNext() }),
            supporting = stringResource(if (allowed) Res.string.name_hint else Res.string.name_reserved),
        )
    }
}

/** The avatar friends will see, redrawn as the name is typed: a new letter pops in, the same one stays put. */
@Composable
private fun LiveAvatar(name: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val size = if (largeFont()) 80.dp else 104.dp
    val reduce = LocalReduceMotion.current
    val tones = colors.avatars.size
    AnimatedContent(
        targetState = name.trim(),
        modifier = modifier,
        // only what the avatar shows changes it: its letters or its color, not the rest of the name
        contentKey = { if (it.isEmpty()) "" else initials(it) + avatarIndex(it, tones) },
        transitionSpec = {
            when {
                reduce -> fadeIn(snap()) togetherWith fadeOut(snap())
                // new letters pop in; the same letters in a new color fade over the old one
                initials(initialState) != initials(targetState) ->
                    (scaleIn(spring(dampingRatio = 0.55f, stiffness = 520f), initialScale = 0.82f) + fadeIn(tween(Motion.QUICK))) togetherWith
                        fadeOut(tween(90))
                else -> fadeIn(tween(Motion.STANDARD)) togetherWith fadeOut(tween(Motion.STANDARD))
            }
        },
        label = "avatar",
    ) { typed ->
        if (typed.isEmpty()) {
            Box(Modifier.size(size).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) {
                Icon(Sym.Person, null, Modifier.size(size * 0.46f), tint = colors.secondaryLabel)
            }
        } else {
            Avatar(typed, size)
        }
    }
}

@Composable
private fun PermissionsPage(onBack: () -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    val title = stringResource(Res.string.onboarding_permissions)
    StepPage(
        step = OnboardingStep.PERMISSIONS, title = title, onBack = onBack,
        bottom = {
            // the button leads to the system's questions, which is where the person says yes or no
            AppButton(stringResource(Res.string.next), onContinue, Modifier.fillMaxWidth())
            if (PermissionsSkippable) {
                Spacer(Modifier.height(4.dp))
                TextLink(stringResource(Res.string.onboarding_not_now), onSkip)
            }
        },
    ) {
        PageTitle(title)
        PageText(stringResource(Res.string.onboarding_permissions_text), Modifier.padding(top = 12.dp, bottom = 28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Reason(Sym.Bluetooth, stringResource(Res.string.perm_bluetooth_title), stringResource(Res.string.perm_bluetooth_text))
            Reason(Sym.Bell, stringResource(Res.string.perm_notifications_title), stringResource(NotificationsReason))
            if (RadioNeedsLocation) {
                Reason(Sym.Place, stringResource(Res.string.perm_location_title), stringResource(Res.string.perm_location_text))
            }
        }
        // where location is asked here already, only the camera waits for its moment
        Text(
            stringResource(if (RadioNeedsLocation) Res.string.onboarding_later_camera else Res.string.onboarding_later),
            style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        )
    }
}

/** One permission: its icon in a green circle, what it is and why we ask. */
@Composable
private fun Reason(icon: ImageVector, title: String, text: String) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        IconCircle(icon, colors.accent, colors.onAccent, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headline, color = colors.label)
            Text(text, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ReadyPage(
    vm: AppViewModel,
    name: String,
    onStartMesh: () -> Unit,
    onBack: () -> Unit,
    onAddFriend: () -> Unit,
    onLater: () -> Unit,
) {
    val colors = AppTheme.colors
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, nearby.radio, onStartMesh)
    val title = stringResource(Res.string.onboarding_ready_title, name.trim())
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) { haptics.confirm() }
    StepPage(
        step = OnboardingStep.READY, title = title, onBack = onBack,
        bottom = {
            AppButton(stringResource(Res.string.messages_empty_action), onAddFriend, Modifier.fillMaxWidth(), icon = Sym.QrScan)
            Spacer(Modifier.height(4.dp))
            TextLink(stringResource(Res.string.onboarding_ready_later), onLater)
        },
    ) {
        if (!LocalTextOnly.current) {
            RadarPicture(
                name, nearby.readyLinks, searching = gate.problem() == null,
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 300.dp).fillMaxWidth(if (largeFont()) 0.6f else 0.82f).aspectRatio(1f),
            )
        }
        // what keeps the network from working, with the button that fixes it; otherwise, how many phones it found
        if (gate.problem() != null) {
            RadioProblemBanner(gate, Modifier.padding(top = 12.dp))
        } else {
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetworkDot(nearby.readyLinks, gate)
                Text(networkText(nearby.readyLinks, gate), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
            }
            // alone, the network isn't broken, just empty
            if (nearby.readyLinks == 0) {
                Text(
                    stringResource(Res.string.onboarding_ready_alone), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        PageTitle(title, Modifier.padding(top = 20.dp))
        PageText(stringResource(Res.string.onboarding_ready_text), Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.largeTitle, color = AppTheme.colors.label, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

@Composable
private fun PageText(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.body, color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * An onboarding page, opaque so it covers the page underneath while sliding: the bar on top with back and the dots
 * of the set-up steps, then the scrolling content, [centered] in the screen when it's shorter, with an optional [hero]
 * from edge to edge, and the buttons pinned above the keyboard. Its [title] is announced when the page changes.
 */
@Composable
private fun StepPage(
    step: OnboardingStep,
    title: String,
    onBack: (() -> Unit)?,
    bottom: @Composable ColumnScope.() -> Unit,
    centered: Boolean = true,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(AppTheme.colors.background).safeDrawingPadding().imePadding().semantics { paneTitle = title },
    ) {
        if (onBack != null || step.progress != null) {
            // the back circle lines up with the page's text, inside its 48dp touch box
            Box(Modifier.fillMaxWidth().height(NavHeight).padding(horizontal = PageGutter - (TouchTarget - GlassSize) / 2)) {
                if (onBack != null) GlassIconButton(Sym.Back, stringResource(Res.string.back), onBack, null, Modifier.align(Alignment.CenterStart))
                step.progress?.let { StepDots(it, OnboardingStep.COUNTED, Modifier.align(Alignment.Center)) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // a page shorter than the screen sits in its middle, unless a keyboard comes up under it
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
            ) {
                hero?.invoke()
                Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp), content = content)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = bottom,
        )
    }
}

/** Where the person is in the set-up: the current step a longer green capsule, the steps done green dots, those ahead gray. */
@Composable
private fun StepDots(index: Int, count: Int, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val reduce = LocalReduceMotion.current
    val label = stringResource(Res.string.onboarding_progress, index + 1, count)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            val width by animateDpAsState(if (i == index) 22.dp else 7.dp, if (reduce) snap() else spring(dampingRatio = 0.8f, stiffness = 500f), label = "dot")
            Box(Modifier.size(width, 7.dp).clip(CircleShape).background(if (i <= index) colors.accent else colors.fill))
        }
    }
}
