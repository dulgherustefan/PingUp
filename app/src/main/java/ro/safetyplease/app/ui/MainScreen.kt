package ro.safetyplease.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.Role as AppRole
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.mesh.MeshState
import ro.safetyplease.app.protocol.AckStatus

/** Ce cerem la pornire: Bluetooth si notificari. Locatia se cere abia cand e nevoie de ea. */
fun startPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // sub Android 12 scanarea BLE nu merge fara permisiunea de locatie
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
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

/** Ce lipseste ca reteaua sa mearga si actiunile care rezolva. */
class RadioGate(
    val hasAccess: Boolean,
    val bluetoothOn: Boolean,
    val locationOff: Boolean,
    val requestAccess: () -> Unit,
    val enableBluetooth: () -> Unit,
)

@Composable
fun rememberRadioGate(vm: AppViewModel, mesh: MeshState, onStartMesh: () -> Unit): RadioGate {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        onStartMesh()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        onStartMesh()
    }
    // tick si starea radioului forteaza reevaluarea dupa ce utilizatorul se intoarce din dialoguri
    val hasAccess = remember(tick, mesh.radio) { vm.c.radio.hasPermissions() }
    val bluetoothOn = remember(tick, mesh.radio) { bluetoothEnabled(context) }
    val locationOff = remember(tick, mesh.radio) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.getSystemService(LocationManager::class.java)?.let { !LocationManagerCompat.isLocationEnabled(it) } == true
    }
    return RadioGate(
        hasAccess, bluetoothOn, locationOff,
        requestAccess = { permissionLauncher.launch(startPermissions()) },
        enableBluetooth = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
    )
}

/** Cere locatia abia cand utilizatorul face ceva care are nevoie de ea. */
@Composable
fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit = {}): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        if (granted) vm.c.location.start()
        onResult(granted)
    }
    return {
        if (vm.c.location.hasPermission()) {
            vm.c.location.start()
            onResult(true)
        } else {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.back() }

    FullScreen {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (!settings.onboarded) OnboardingScreen(vm, onStartMesh)
        else AnimatedContent(
            targetState = vm.stack.lastOrNull(),
            transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90)) },
            label = "screen",
        ) { dest ->
            CompositionLocalProvider(LocalBottomClearance provides navBottom) {
                when (dest) {
                    is Dest.Conversation -> ConversationScreen(vm, dest.id)
                    is Dest.Profile -> ProfileScreen(vm, dest.conversation)
                    Dest.AddFriend -> AddFriendScreen(vm)
                    Dest.NewGroup -> NewGroupScreen(vm)
                    is Dest.ReportSent -> ReportSentScreen(vm, dest.incidentId)
                    Dest.MyReports -> MyReportsScreen(vm)
                    is Dest.Incident -> IncidentDetailScreen(vm, dest.id)
                    Dest.Me -> MeScreen(vm, onStartMesh, onBack = { vm.back() })
                    Dest.Demo -> Demo.Screen(vm)
                    is Dest.Pin -> PinScreen(vm, dest)
                    null -> if (settings.role == AppRole.ANCHOR) AnchorScreen(vm) else MainTabs(vm, settings.role == AppRole.STAFF, onStartMesh)
                }
            }
        }
    }
}

private class TabItem(val tab: Tab, val icon: ImageVector, val label: Int, val badge: Int = 0)

private val BarHeight = 68.dp

@Composable
private fun MainTabs(vm: AppViewModel, staff: Boolean, onStartMesh: () -> Unit) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    if (!staff && vm.tab == Tab.INCIDENTS) vm.tab = Tab.MESSAGES
    val unread = chat.messages.count { !it.read }
    val openIncidents = incidents.staff.count { it.status < AckStatus.ACKNOWLEDGED }
    val items = buildList {
        add(TabItem(Tab.MESSAGES, AppIcons.Chat, R.string.tab_messages, unread))
        add(TabItem(Tab.REPORT, AppIcons.Warning, R.string.tab_report))
        add(TabItem(Tab.MAP, AppIcons.Map, R.string.tab_map))
        if (staff) add(TabItem(Tab.INCIDENTS, AppIcons.Shield, R.string.tab_incidents, openIncidents))
        add(TabItem(Tab.ME, AppIcons.Person, R.string.tab_me))
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomClearance provides navBottom + BarHeight + 24.dp) {
            AnimatedContent(
                targetState = vm.tab,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90)) },
                label = "tab",
            ) { tab ->
                when (tab) {
                    Tab.MESSAGES -> MessagesScreen(vm, onStartMesh)
                    Tab.REPORT -> ReportScreen(vm)
                    Tab.MAP -> MapScreen(vm)
                    Tab.ME -> MeScreen(vm, onStartMesh, onBack = null)
                    Tab.INCIDENTS -> IncidentsScreen(vm)
                }
            }
        }
        BottomBar(
            items, vm.tab, onSelect = { vm.tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/** Bara plutitoare: aceeasi pastila inchisa pe ambele teme, cu tabul ales intr-o pastila salvie care aluneca intre taburi. */
@Composable
private fun BottomBar(items: List<TabItem>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val index = items.indexOfFirst { it.tab == current }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(BarHeight)
            .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(CircleShape).background(Brand.Bar).border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape).padding(6.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val indicatorX by animateDpAsState(itemWidth * index, tween(Motion.STANDARD, easing = Motion.Standard), label = "tabIndicator")
        Box(Modifier.offset { IntOffset(indicatorX.roundToPx(), 0) }.width(itemWidth).fillMaxHeight().clip(CircleShape).background(Brand.Sage))
        Row(Modifier.fillMaxSize()) {
            for (item in items) {
                val selected = item.tab == current
                val content by animateColorAsState(if (selected) Brand.OnSage else Brand.BarMuted, tween(Motion.QUICK), label = "tabContent")
                val source = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).fillMaxHeight().pressScale(source, pressed = 0.94f).clip(CircleShape)
                        .selectable(selected = selected, interactionSource = source, indication = null, role = Role.Tab) { onSelect(item.tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(item.icon, null, tint = content, modifier = Modifier.size(22.dp))
                        if (item.badge > 0) {
                            CountBadge(
                                item.badge,
                                container = if (selected) Brand.OnSage else Brand.Sage,
                                content = if (selected) Brand.Sage else Brand.OnSage,
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    // cu textul marit din setari si cinci taburi, eticheta se micsoreaza cat sa incapa, nu se taie
                    Text(
                        stringResource(item.label), style = MaterialTheme.typography.labelSmall, color = content,
                        maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 2.dp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

@Composable
fun CountBadge(count: Int, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape).background(container).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(), color = content, fontSize = 11.sp, lineHeight = 12.sp,
            style = MaterialTheme.typography.labelSmall, maxLines = 1,
        )
    }
}
