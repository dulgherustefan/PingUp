package ro.safetyplease.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.Role
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.mesh.MeshState

fun meshPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    // sub Android 12 scanarea BLE cere locatie; peste, locatia e folosita doar pentru zona
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun bluetoothEnabled(context: Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** Null daca Bluetooth e oprit si inca nu se poate sti. */
fun canAdvertise(context: Context): Boolean? {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    if (!adapter.isEnabled) return null
    return adapter.isMultipleAdvertisementSupported
}

@Composable
fun AppRoot(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.back() }

    if (!settings.onboarded) {
        OnboardingScreen(vm, onStartMesh)
        return
    }
    when (val top = vm.stack.lastOrNull()) {
        is Dest.Conversation -> ConversationScreen(vm, top.id)
        Dest.MyQr -> MyQrScreen(vm)
        Dest.Scan -> ScanScreen(vm)
        Dest.NewGroup -> NewGroupScreen(vm)
        is Dest.Incident -> IncidentDetailScreen(vm, top.id)
        Dest.Settings -> SettingsScreen(vm, onStartMesh)
        Dest.Demo -> Demo.Screen(vm)
        is Dest.Pin -> PinScreen(vm, top)
        null -> if (settings.role == Role.ANCHOR) AnchorScreen(vm) else MainTabs(vm, settings.role == Role.STAFF, onStartMesh)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(
                                subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(AppIcons.Back, stringResource(R.string.back)) }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
private fun MainTabs(vm: AppViewModel, staff: Boolean, onStartMesh: () -> Unit) {
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    if (!staff && vm.tab == Tab.INCIDENTS) vm.tab = Tab.REPORT
    val unread = chat.messages.count { !it.read }
    val openIncidents = incidents.staff.count { it.status < ro.safetyplease.app.protocol.AckStatus.ACKNOWLEDGED }

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            MeshChip(mesh)
            IconButton(onClick = { vm.open(Dest.Settings) }) { Icon(AppIcons.Settings, stringResource(R.string.settings)) }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TabItem(vm, Tab.CHAT, AppIcons.Chat, R.string.tab_chat, badge = unread)
                TabItem(vm, Tab.FRIENDS, AppIcons.People, R.string.tab_friends)
                TabItem(vm, Tab.REPORT, AppIcons.Warning, R.string.tab_report, highlight = true)
                TabItem(vm, Tab.MAP, AppIcons.Map, R.string.tab_map)
                if (staff) TabItem(vm, Tab.INCIDENTS, AppIcons.Shield, R.string.tab_incidents, badge = openIncidents)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            RadioBanner(vm, mesh, onStartMesh)
            Box(Modifier.weight(1f)) {
                when (vm.tab) {
                    Tab.CHAT -> ChatListScreen(vm)
                    Tab.FRIENDS -> FriendsScreen(vm)
                    Tab.REPORT -> ReportScreen(vm)
                    Tab.MAP -> MapScreen(vm)
                    Tab.INCIDENTS -> IncidentsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    vm: AppViewModel,
    tab: Tab,
    icon: ImageVector,
    label: Int,
    badge: Int = 0,
    highlight: Boolean = false,
) {
    val colors = if (highlight) {
        NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            indicatorColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
        )
    } else NavigationBarItemDefaults.colors()
    NavigationBarItem(
        selected = vm.tab == tab,
        onClick = { vm.tab = tab },
        icon = {
            if (badge > 0) BadgedBox(badge = { Badge { Text(badge.toString()) } }) { Icon(icon, null) }
            else Icon(icon, null)
        },
        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = colors,
    )
}

@Composable
fun MeshChip(mesh: MeshState) {
    val links = mesh.readyLinks
    val color = when {
        !mesh.radio.bluetoothOn -> Palette.Urgent
        links == 0 -> Palette.Medium
        else -> Palette.Mesh
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    !mesh.radio.bluetoothOn -> stringResource(R.string.mesh_off)
                    links == 0 -> stringResource(R.string.mesh_searching)
                    else -> pluralStringResource(R.plurals.mesh_links, links, links)
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Spune ce lipseste ca mesh-ul sa mearga si ofera butonul care rezolva. */
@Composable
fun RadioBanner(vm: AppViewModel, mesh: MeshState, onStartMesh: () -> Unit) {
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
    val hasPermissions = remember(tick, mesh.radio) { vm.c.radio.hasPermissions() }
    val bluetoothOn = remember(tick, mesh.radio) { bluetoothEnabled(context) }
    val locationNeeded = remember(tick, mesh.radio) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.getSystemService(LocationManager::class.java)?.let { !androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true
    }
    when {
        !hasPermissions -> Banner(stringResource(R.string.banner_permissions), stringResource(R.string.banner_grant), Palette.Urgent) {
            permissionLauncher.launch(meshPermissions())
        }
        !bluetoothOn -> Banner(stringResource(R.string.banner_bluetooth), stringResource(R.string.banner_enable), Palette.Urgent) {
            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        locationNeeded -> Banner(stringResource(R.string.banner_location), stringResource(R.string.banner_settings), Palette.Medium) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        mesh.radio.bluetoothOn && !mesh.radio.canAdvertise ->
            Banner(stringResource(R.string.banner_leaf), null, Palette.Medium) {}
    }
}

@Composable
private fun Banner(text: String, action: String?, color: Color, onClick: () -> Unit) {
    Surface(color = color.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (action != null) TextButton(onClick = onClick) { Text(action) }
        }
    }
}
