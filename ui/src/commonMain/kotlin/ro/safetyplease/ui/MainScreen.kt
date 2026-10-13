package ro.safetyplease.ui

import ro.safetyplease.ui.common.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.control.ControlScreen
import ro.safetyplease.ui.report.MyReportsScreen
import ro.safetyplease.ui.report.ReportScreen
import ro.safetyplease.ui.report.ReportSentScreen
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.NoticeReach
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.ConsoleWindow
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.Tab
import ro.safetyplease.ui.chat.ConversationScreen
import ro.safetyplease.ui.chat.MeetupRequests
import ro.safetyplease.ui.chat.MessagesScreen
import ro.safetyplease.ui.common.ExerciseBanner
import ro.safetyplease.ui.common.RadioGate
import ro.safetyplease.ui.common.SharingBar
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Backdrop
import ro.safetyplease.ui.designsystem.FullScreen
import ro.safetyplease.ui.designsystem.LocalBottomClearance
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.LocalStatusBarTop
import ro.safetyplease.ui.designsystem.LocalTextOnly
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextFont
import ro.safetyplease.ui.designsystem.backdropSource
import ro.safetyplease.ui.designsystem.glass
import ro.safetyplease.ui.designsystem.pressScale
import ro.safetyplease.ui.designsystem.rememberBackdrop
import ro.safetyplease.ui.firstaid.FirstAidScreen
import ro.safetyplease.ui.firstaid.FirstAidSheetScreen
import ro.safetyplease.ui.incidents.IncidentDetailScreen
import ro.safetyplease.ui.incidents.IncidentsScreen
import ro.safetyplease.ui.info.PageScreen
import ro.safetyplease.ui.info.PagesScreen
import ro.safetyplease.ui.map.MapScreen
import ro.safetyplease.ui.map.PinScreen
import ro.safetyplease.ui.map.RouteScreen
import ro.safetyplease.ui.map.ProgramScreen
import ro.safetyplease.ui.me.AnchorScreen
import ro.safetyplease.ui.me.EventScreen
import ro.safetyplease.ui.me.MeScreen
import ro.safetyplease.ui.me.PrivacyScreen
import ro.safetyplease.ui.notices.ControlMapLayer
import ro.safetyplease.ui.notices.EvacuationBanner
import ro.safetyplease.ui.notices.EvacuationMode
import ro.safetyplease.ui.notices.EvacuationUi
import ro.safetyplease.ui.notices.NoticeAlert
import ro.safetyplease.ui.notices.NoticeBands
import ro.safetyplease.ui.notices.myEvacuation
import ro.safetyplease.ui.notices.rememberWhereabouts
import ro.safetyplease.ui.notices.screenAlert
import ro.safetyplease.ui.notices.shownNotices
import ro.safetyplease.ui.onboarding.OnboardingScreen
import ro.safetyplease.ui.people.AddFriendScreen
import ro.safetyplease.ui.people.BlockedScreen
import ro.safetyplease.ui.people.NewGroupScreen
import ro.safetyplease.ui.people.ProfileScreen
import ro.safetyplease.ui.people.RegasireRequests
import ro.safetyplease.ui.people.ShareChooseScreen
import ro.safetyplease.ui.people.ShareIntroScreen
import ro.safetyplease.ui.people.SharingScreen
import ro.safetyplease.ui.push
import ro.safetyplease.ui.rememberLocationRequest
import ro.safetyplease.ui.rememberRadioGate
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.location_precise_needed
import ro.safetyplease.ui.resources.map_title
import ro.safetyplease.ui.resources.tab_incidents
import ro.safetyplease.ui.resources.tab_messages
import ro.safetyplease.ui.resources.tab_report
import ro.safetyplease.ui.resources.tab_unread
import ro.safetyplease.ui.safety.DiscreetScreen
import ro.safetyplease.ui.safety.SafetyScreenHost
import ro.safetyplease.ui.staff.StaffChainLayer
import ro.safetyplease.ui.staffcodes.PointsScreen
import ro.safetyplease.ui.staffcodes.StaffCodesScreen
import ro.safetyplease.ui.text.rememberNow
import ro.safetyplease.core.data.Role as AppRole

/** Screens that slide up as sheets; the rest push in from the right. */
private fun Dest?.isSheet() = this == Dest.Me || this == Dest.AddFriend || this == Dest.NewGroup

/** Every screen of the app; [demo] is the screen of the test tools, in a build that has them. */
@Composable
fun AppRoot(vm: AppViewModel, onStartMesh: () -> Unit, demo: @Composable () -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val battery by vm.c.battery.state.collectAsStateWithLifecycle()
    val textOnly = PowerPolicy.textOnly(battery.percent, battery.charging)
    val reduce = LocalReduceMotion.current || textOnly
    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.back() }

    // each back-stack entry keeps its state (drafts, filters, scroll) while it's on the stack
    val holder = rememberSaveableStateHolder()
    val entries = vm.stack.mapIndexed { index, dest -> entryKey(index + 1, dest) }
    var kept by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(entries) {
        // once popped, a screen starts fresh when reopened; its state isn't saved while it animates out
        (kept - entries.toSet()).forEach(holder::removeState)
        kept = entries
    }

    if (settings.role == AppRole.CONTROL) ConsoleWindow()
    val board by vm.c.notices.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    val showsNotices = vm.c.showsNotices(settings)
    // an evacuation for this phone's zone shows whatever the person chose for the other notices
    val showsEvacuation = vm.c.showsEvacuation(settings)
    val active = board.active(now / 1000)
    val shown = shownNotices(vm, if (showsNotices) active else emptyList())
    val here = rememberWhereabouts(vm, now)
    val evacuations = if (showsEvacuation && !showsNotices) shownNotices(vm, active.filter { NoticeReach.isEvacuation(it.second) }) else shown
    val evacuation = myEvacuation(evacuations, vm.venue, here)
    val mapState by vm.c.mapState.collectAsStateWithLifecycle()
    val hereZone = Evacuations.zoneFor(mapState, here)
    val evacuationUi = remember { EvacuationUi() }
    val bands = shown.filter { !it.held.dismissed && it !== evacuation }
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    // the foreground service may hold the location type only while asked from a visible screen
    LifecycleResumeEffect(sharing != null) {
        vm.phone.sharingLocation(sharing != null)
        onPauseOrDispose { }
    }
    val alert = screenAlert(shown, vm.venue, here)
    ControlMapLayer(vm, hereZone)
    CompositionLocalProvider(LocalTextOnly provides textOnly, LocalReduceMotion provides reduce) {
        FullScreen {
            // the bands sit above every screen and take the status bar, so they cover no header
            val topTaken = vm.c.exercise || bands.isNotEmpty() || sharing != null || evacuation != null
            val barFree = !vm.c.exercise && evacuation == null
            Column(Modifier.fillMaxSize()) {
                if (vm.c.exercise) ExerciseBanner()
                evacuation?.let { EvacuationBanner(vm, it, hereZone, statusBar = !vm.c.exercise) { evacuationUi.reopened = true } }
                sharing?.let { SharingBar(it, statusBar = barFree) { vm.open(Dest.Sharing) } }
                NoticeBands(vm, bands, statusBar = barFree && sharing == null)
                Box(Modifier.weight(1f).then(if (topTaken) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                    CompositionLocalProvider(LocalStatusBarTop provides if (topTaken) 0.dp else null) {
                        Screens(vm, settings.onboarded, settings.role, onStartMesh, holder, reduce, demo)
                    }
                }
            }
            alert?.let { NoticeAlert(it, vm.c.exercise) { vm.c.notices.alerted(it.notice.topic, it.notice.seq) } }
            MeetupRequests(vm)
            StaffChainLayer(vm)
            RegasireRequests(vm)
            // above a friend's torch: nothing covers the way out
            evacuation?.let { EvacuationMode(vm, it, hereZone, evacuationUi) }
        }
    }
}

@Composable
private fun Screens(
    vm: AppViewModel,
    onboarded: Boolean,
    role: AppRole,
    onStartMesh: () -> Unit,
    holder: SaveableStateHolder,
    reduce: Boolean,
    demo: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (!onboarded) OnboardingScreen(vm, onStartMesh)
        else AnimatedContent(
            targetState = vm.stack.size to vm.stack.lastOrNull(),
            transitionSpec = {
                val forward = targetState.first >= initialState.first
                screenTransition(forward, if (forward) targetState.second else initialState.second, reduce)
            },
            label = "screen",
        ) { (size, dest) ->
            holder.SaveableStateProvider(entryKey(size, dest)) {
                CompositionLocalProvider(LocalBottomClearance provides navBottom) {
                    Screen(vm, dest, role, onStartMesh, demo)
                }
            }
        }
    }
}

/** Saved-state key of a back-stack entry: its position and its screen. */
private fun entryKey(size: Int, dest: Dest?): String = if (dest == null) "root" else "$size:$dest"

@Composable
private fun Screen(vm: AppViewModel, dest: Dest?, role: AppRole, onStartMesh: () -> Unit, demo: @Composable () -> Unit) {
    when (dest) {
        is Dest.Conversation -> ConversationScreen(vm, dest.id)
        is Dest.Profile -> ProfileScreen(vm, dest.conversation)
        Dest.AddFriend -> AddFriendScreen(vm)
        Dest.NewGroup -> NewGroupScreen(vm)
        is Dest.ReportSent -> ReportSentScreen(vm, dest.incidentId, onStartMesh)
        Dest.MyReports -> MyReportsScreen(vm)
        Dest.Discreet -> DiscreetScreen(vm)
        is Dest.Safety -> SafetyScreenHost(vm, dest.screen)
        Dest.StaffCodes -> StaffCodesScreen(vm)
        Dest.Points -> PointsScreen(vm)
        Dest.Blocked -> BlockedScreen(vm)
        is Dest.Incident -> IncidentDetailScreen(vm, dest.id)
        Dest.Me -> MeScreen(vm, onStartMesh)
        Dest.Privacy -> PrivacyScreen(vm)
        Dest.Event -> EventScreen(vm)
        Dest.Demo -> demo()
        is Dest.Pin -> PinScreen(vm, dest)
        is Dest.Map -> MapScreen(vm, dest)
        is Dest.Walk -> RouteScreen(vm, dest.target)
        Dest.FirstAid -> FirstAidScreen(vm)
        is Dest.FirstAidSheet -> FirstAidSheetScreen(vm, dest.id)
        Dest.Pages -> PagesScreen(vm)
        is Dest.Page -> PageScreen(vm, dest.id)
        Dest.ShareIntro -> ShareIntroScreen(vm)
        Dest.ShareChoose -> ShareChooseScreen(vm)
        Dest.Sharing -> SharingScreen(vm)
        Dest.Program -> ProgramScreen(vm)
        null -> when (role) {
            AppRole.ANCHOR -> AnchorScreen(vm)
            AppRole.CONTROL -> ControlScreen(vm, onStartMesh)
            else -> MainTabs(vm, role == AppRole.STAFF, onStartMesh)
        }
    }
}

/**
 * New screens push in from the right while the old one shifts slightly left; sheets slide up.
 * Back reverses the same path.
 */
private fun screenTransition(forward: Boolean, moving: Dest?, reduce: Boolean): ContentTransform {
    if (reduce) return fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))
    val spec = Motion.Push
    // the screen underneath stays put while a sheet slides
    val stay = tween<Float>(Motion.PUSH_SETTLE)
    return if (moving.isSheet()) {
        if (forward) slideInVertically(spec) { it } togetherWith fadeOut(stay, targetAlpha = 0.99f)
        else (fadeIn(stay, initialAlpha = 0.99f) togetherWith slideOutVertically(spec) { it }).apply { targetContentZIndex = -1f }
    } else {
        if (forward) slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 }
        else (slideInHorizontally(spec) { -it / 3 } togetherWith slideOutHorizontally(spec) { it }).apply { targetContentZIndex = -1f }
    }
}

private class TabItem(val tab: Tab, val label: StringResource, val icon: ImageVector, val badge: Int = 0)

private val TabWidth = 84.dp
private val TabBarHeight = 62.dp
private const val MAX_TAB_FONT_SCALE = 1.3f

@Composable
private fun MainTabs(vm: AppViewModel, staff: Boolean, onStartMesh: () -> Unit) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val current = if (!staff && vm.tab == Tab.INCIDENTS) Tab.MESSAGES else vm.tab
    val tabs = rememberSaveableStateHolder()
    // badge counts conversations with unread messages, not messages
    val unreadChats = chat.messages.filter { !it.read }.map { it.conversation }.distinct().size
    val openIncidents = incidents.staff.count { !AckStatus.isTaken(it.status) && !AckStatus.isClosed(it.status) && !Priority.isInformational(it.category) }
    val items = buildList {
        add(TabItem(Tab.MESSAGES, Res.string.tab_messages, Sym.ChatFill, unreadChats))
        add(TabItem(Tab.REPORT, Res.string.tab_report, Sym.ReportFill))
        add(TabItem(Tab.MAP, Res.string.map_title, Sym.MapFill))
        if (staff) add(TabItem(Tab.INCIDENTS, Res.string.tab_incidents, Sym.BellFill, openIncidents))
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barBottom = navBottom + 10.dp
    val backdrop = rememberBackdrop()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).background(AppTheme.colors.background)) {
            CompositionLocalProvider(LocalBottomClearance provides barBottom + TabBarHeight + 8.dp) {
                // tab switches are a very short fade
                AnimatedContent(current, transitionSpec = { fadeIn(tween(180, easing = Motion.Enter)) togetherWith fadeOut(tween(110)) }, label = "tab") { tab ->
                    tabs.SaveableStateProvider(tab.name) {
                        when (tab) {
                            Tab.MESSAGES -> MessagesScreen(vm, onStartMesh)
                            Tab.REPORT -> ReportScreen(vm, onStartMesh)
                            Tab.MAP -> MapScreen(vm, Dest.Map(), asTab = true)
                            Tab.INCIDENTS -> IncidentsScreen(vm)
                        }
                    }
                }
            }
        }
        GlassTabBar(items, current, { vm.tab = it }, backdrop, Modifier.align(Alignment.BottomCenter).padding(bottom = barBottom))
    }
}

/** Floating tab bar sized to its tabs. The selected tab sits on a translucent pill; unread counts are a badge on the icon. */
@Composable
private fun GlassTabBar(items: List<TabItem>, current: Tab, onSelect: (Tab) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val index = items.indexOfFirst { it.tab == current }.coerceAtLeast(0)
    // the pill slides to the new tab and settles with a short spring
    val x by animateDpAsState(TabWidth * index, spring(dampingRatio = 0.72f, stiffness = 380f), label = "tabPill")
    // Tab labels scale with the system font size up to 130%; beyond that they don't fit the capsule.
    // TalkBack still reads the full label.
    val fontScale = LocalDensity.current.fontScale
    val tabScale = fontScale.coerceAtMost(MAX_TAB_FONT_SCALE) / fontScale
    Box(modifier.height(TabBarHeight).width(TabWidth * items.size + 8.dp).glass(backdrop, CircleShape).padding(4.dp).selectableGroup()) {
        Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(TabWidth).fillMaxHeight().clip(CircleShape).background(colors.glassPill))
        Row(Modifier.fillMaxHeight()) {
            for (item in items) {
                val selected = item.tab == current
                val tint by animateColorAsState(if (selected) colors.accent else colors.label, tween(Motion.QUICK), label = "tabTint")
                val badgeText = if (item.badge > 0) pluralStringResource(Res.plurals.tab_unread, item.badge, item.badge) else null
                val press = remember { MutableInteractionSource() }
                Column(
                    Modifier.width(TabWidth).fillMaxHeight().clip(CircleShape)
                        .selectable(selected, interactionSource = press, indication = null, role = Role.Tab) { onSelect(item.tab) }
                        .then(if (badgeText != null) Modifier.semantics { stateDescription = badgeText } else Modifier)
                        .pressScale(press),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(item.icon, null, Modifier.size(25.dp), tint = tint)
                        if (item.badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-5).dp).defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                    .clip(CircleShape).background(colors.red).padding(horizontal = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (item.badge > 99) "99+" else item.badge.toString(), color = Color.White, fontSize = (11 * tabScale).sp,
                                    lineHeight = (13 * tabScale).sp, fontFamily = TextFont, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(item.label), color = tint, fontSize = (10 * tabScale).sp, lineHeight = (12 * tabScale).sp,
                        fontFamily = TextFont, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
