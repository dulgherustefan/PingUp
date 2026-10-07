package ro.safetyplease.app.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.ui.chat.ConversationScreen
import ro.safetyplease.app.ui.chat.MessagesScreen
import ro.safetyplease.app.ui.common.RadioGate
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Backdrop
import ro.safetyplease.app.ui.designsystem.FullScreen
import ro.safetyplease.app.ui.designsystem.LocalBottomClearance
import ro.safetyplease.app.ui.designsystem.LocalReduceMotion
import ro.safetyplease.app.ui.designsystem.Motion
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TextFont
import ro.safetyplease.app.ui.designsystem.backdropSource
import ro.safetyplease.app.ui.designsystem.glass
import ro.safetyplease.app.ui.designsystem.pressScale
import ro.safetyplease.app.ui.designsystem.rememberBackdrop
import ro.safetyplease.app.ui.incidents.IncidentDetailScreen
import ro.safetyplease.app.ui.incidents.IncidentsScreen
import ro.safetyplease.app.ui.map.MapScreen
import ro.safetyplease.app.ui.map.PinScreen
import ro.safetyplease.app.ui.me.AnchorScreen
import ro.safetyplease.app.ui.me.MeScreen
import ro.safetyplease.app.ui.onboarding.OnboardingScreen
import ro.safetyplease.app.ui.people.AddFriendScreen
import ro.safetyplease.app.ui.people.NewGroupScreen
import ro.safetyplease.app.ui.people.ProfileScreen
import ro.safetyplease.app.ui.report.MyReportsScreen
import ro.safetyplease.app.ui.report.ReportScreen
import ro.safetyplease.app.ui.report.ReportSentScreen
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.data.Role as AppRole

/** Fara acestea reteaua nu porneste. */
fun radioPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // sub Android 12 scanarea BLE nu merge fara permisiunea de locatie
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

/** Ce cerem la pornire: Bluetooth si notificari. Locatia se cere abia cand e nevoie de ea. */
fun startPermissions(): Array<String> = buildList {
    addAll(radioPermissions())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun bluetoothEnabled(context: Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** Producatorii care opresc aplicatiile din fundal, cat timp aplicatia nu e scoasa din optimizarea bateriei. */
fun needsBatteryHint(context: Context): Boolean {
    if (Build.MANUFACTURER.lowercase() !in setOf("samsung", "xiaomi", "redmi", "poco")) return false
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Dupa doua refuzuri Android nu mai arata dialogul de permisiune. Se poate sti abia dupa raspunsul la o cerere:
 * inainte de prima cerere, lipsa explicatiei inseamna doar ca nu am intrebat inca.
 */
fun deniedForGood(activity: Activity?, denied: Collection<String>): Boolean =
    activity != null && denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

fun openAppSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
}

@Composable
fun rememberRadioGate(vm: AppViewModel, radio: RadioStatus, onStartMesh: () -> Unit): RadioGate {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // fara notificari reteaua merge; conteaza doar ce cere radioul
        val denied = result.filter { !it.value && it.key in radioPermissions() }.keys
        vm.radioAccessBlocked = deniedForGood(activity, denied)
        tick++
        onStartMesh()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        onStartMesh()
    }
    // tick si starea radioului forteaza reevaluarea dupa ce utilizatorul se intoarce din dialoguri
    val hasAccess = remember(tick, radio) { vm.c.radio.hasPermissions() }
    val bluetoothOn = remember(tick, radio) { bluetoothEnabled(context) }
    val locationOff = remember(tick, radio) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.getSystemService(LocationManager::class.java)?.let { !LocationManagerCompat.isLocationEnabled(it) } == true
    }
    val blocked = vm.radioAccessBlocked && !hasAccess
    return RadioGate(
        hasAccess, bluetoothOn, locationOff, blocked,
        requestAccess = { if (blocked) openAppSettings(context) else permissionLauncher.launch(startPermissions()) },
        enableBluetooth = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
    )
}

/**
 * Cere locatia abia cand utilizatorul face ceva care are nevoie de ea. Conteaza doar cea precisa:
 * GPS_PROVIDER nu merge cu cea aproximativa, care oricum nu poate alege o zona.
 */
@Composable
fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            vm.c.location.start()
        } else {
            vm.locationBlocked = deniedForGood(activity, listOf(Manifest.permission.ACCESS_FINE_LOCATION))
            if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                Toast.makeText(context, R.string.location_precise_needed, Toast.LENGTH_LONG).show()
            }
        }
        onResult(granted)
    }
    return {
        when {
            vm.c.location.hasPermission() -> {
                vm.c.location.start()
                onResult(true)
            }
            vm.locationBlocked -> openAppSettings(context)
            else -> launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

/** Ecranele care urca de jos, ca foile din iOS; restul intra din dreapta. */
private fun Dest?.isSheet() = this == Dest.Me || this == Dest.AddFriend || this == Dest.NewGroup

@Composable
fun AppRoot(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.back() }

    // fiecare intrare din stiva isi pastreaza starea (ciorne, filtre, derulare) cat timp e in stiva
    val holder = rememberSaveableStateHolder()
    val entries = vm.stack.mapIndexed { index, dest -> entryKey(index + 1, dest) }
    var kept by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(entries) {
        // scos din stiva, un ecran porneste curat cand e redeschis; cat inca iese din ecran, starea lui nu mai e salvata
        (kept - entries.toSet()).forEach(holder::removeState)
        kept = entries
    }

    FullScreen {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (!settings.onboarded) OnboardingScreen(vm, onStartMesh)
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
                    Screen(vm, dest, settings.role, onStartMesh)
                }
            }
        }
    }
}

/** Cheia starii salvate a unei intrari din stiva: locul in stiva si ecranul. */
private fun entryKey(size: Int, dest: Dest?): String = if (dest == null) "root" else "$size:$dest"

@Composable
private fun Screen(vm: AppViewModel, dest: Dest?, role: AppRole, onStartMesh: () -> Unit) {
    when (dest) {
        is Dest.Conversation -> ConversationScreen(vm, dest.id)
        is Dest.Profile -> ProfileScreen(vm, dest.conversation)
        Dest.AddFriend -> AddFriendScreen(vm)
        Dest.NewGroup -> NewGroupScreen(vm)
        is Dest.ReportSent -> ReportSentScreen(vm, dest.incidentId)
        Dest.MyReports -> MyReportsScreen(vm)
        is Dest.Incident -> IncidentDetailScreen(vm, dest.id)
        Dest.Me -> MeScreen(vm, onStartMesh)
        Dest.Demo -> Demo.Screen(vm)
        is Dest.Pin -> PinScreen(vm, dest)
        is Dest.Map -> MapScreen(vm, dest)
        null -> if (role == AppRole.ANCHOR) AnchorScreen(vm) else MainTabs(vm, role == AppRole.STAFF, onStartMesh)
    }
}

/**
 * Trecerile din iOS: ecranul nou intra din dreapta peste cel vechi, care se da putin la stanga;
 * foile urca de jos. Inapoi, totul se intoarce pe acelasi drum.
 */
private fun screenTransition(forward: Boolean, moving: Dest?, reduce: Boolean): ContentTransform {
    if (reduce) return fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))
    val spec = Motion.Push
    // ecranul de dedesubt sta pe loc cat urca sau coboara foaia
    val stay = tween<Float>(Motion.PUSH_SETTLE)
    return if (moving.isSheet()) {
        if (forward) slideInVertically(spec) { it } togetherWith fadeOut(stay, targetAlpha = 0.99f)
        else (fadeIn(stay, initialAlpha = 0.99f) togetherWith slideOutVertically(spec) { it }).apply { targetContentZIndex = -1f }
    } else {
        if (forward) slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 }
        else (slideInHorizontally(spec) { -it / 3 } togetherWith slideOutHorizontally(spec) { it }).apply { targetContentZIndex = -1f }
    }
}

private class TabItem(val tab: Tab, val label: Int, val icon: ImageVector, val badge: Int = 0)

private val TabWidth = 84.dp
private val TabBarHeight = 62.dp
private const val MAX_TAB_FONT_SCALE = 1.3f

@Composable
private fun MainTabs(vm: AppViewModel, staff: Boolean, onStartMesh: () -> Unit) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val current = if (!staff && vm.tab == Tab.INCIDENTS) Tab.MESSAGES else vm.tab
    val tabs = rememberSaveableStateHolder()
    // pe bara: cate conversatii au ceva necitit, nu cate mesaje
    val unreadChats = chat.messages.filter { !it.read }.map { it.conversation }.distinct().size
    val openIncidents = incidents.staff.count { it.status < AckStatus.ACKNOWLEDGED }
    val items = buildList {
        add(TabItem(Tab.MESSAGES, R.string.tab_messages, Sym.ChatFill, unreadChats))
        add(TabItem(Tab.REPORT, R.string.tab_report, Sym.ReportFill))
        // regasirea e motivul pentru care omul are aplicatia la festival, deci harta sta in bara, nu in setari
        add(TabItem(Tab.MAP, R.string.map_title, Sym.MapFill))
        if (staff) add(TabItem(Tab.INCIDENTS, R.string.tab_incidents, Sym.BellFill, openIncidents))
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // putin deasupra barei de gesturi, ca in Signal
    val barBottom = navBottom + 10.dp
    val backdrop = rememberBackdrop()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).background(AppTheme.colors.background)) {
            CompositionLocalProvider(LocalBottomClearance provides barBottom + TabBarHeight + 8.dp) {
                // pe iPhone tabul se schimba pe loc; aici doar o estompare foarte scurta
                AnimatedContent(current, transitionSpec = { fadeIn(tween(180, easing = Motion.Enter)) togetherWith fadeOut(tween(110)) }, label = "tab") { tab ->
                    tabs.SaveableStateProvider(tab.name) {
                        when (tab) {
                            Tab.MESSAGES -> MessagesScreen(vm, onStartMesh)
                            Tab.REPORT -> ReportScreen(vm)
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

/**
 * Bara de taburi din iOS 26: o capsula de sticla care pluteste deasupra listei, cat de lata cer taburile ei.
 * Tabul ales se face verde, ca pinul din logo, si sta pe o pastila verde translucida; celelalte raman
 * in culoarea textului, iar necititele sunt o insigna rosie pe coltul iconitei.
 */
@Composable
private fun GlassTabBar(items: List<TabItem>, current: Tab, onSelect: (Tab) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val index = items.indexOfFirst { it.tab == current }.coerceAtLeast(0)
    // pastila aluneca spre tabul nou si se aseaza cu un arc scurt
    val x by animateDpAsState(TabWidth * index, spring(dampingRatio = 0.72f, stiffness = 380f), label = "tabPill")
    // textul barei creste cu marimea textului din setari, dar doar pana la 130%: peste atat n-ar mai incapea in capsula.
    // Eticheta ramane oricum citita intreaga de TalkBack.
    val fontScale = LocalDensity.current.fontScale
    val tabScale = fontScale.coerceAtMost(MAX_TAB_FONT_SCALE) / fontScale
    Box(modifier.height(TabBarHeight).width(TabWidth * items.size + 8.dp).glass(backdrop, CircleShape).padding(4.dp).selectableGroup()) {
        Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(TabWidth).fillMaxHeight().clip(CircleShape).background(colors.glassPill))
        Row(Modifier.fillMaxHeight()) {
            for (item in items) {
                val selected = item.tab == current
                val tint by animateColorAsState(if (selected) colors.accent else colors.label, tween(Motion.QUICK), label = "tabTint")
                val badgeText = if (item.badge > 0) pluralStringResource(R.plurals.tab_unread, item.badge, item.badge) else null
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
