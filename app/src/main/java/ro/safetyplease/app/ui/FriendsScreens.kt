package ro.safetyplease.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import ro.safetyplease.app.R
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.protocol.Limits
import java.util.concurrent.Executors

@Composable
fun FriendsScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Friend?>(null) }
    var leaving by remember { mutableStateOf<Group?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.open(Dest.MyQr) }, modifier = Modifier.weight(1f)) {
                    Icon(AppIcons.QrCode, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.friends_my_code), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { vm.open(Dest.Scan) }, modifier = Modifier.weight(1f)) {
                    Icon(AppIcons.Camera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.friends_scan), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (friends.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.friends_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(friends.sortedBy { it.nickname.lowercase() }, key = { it.nodeId }) { friend ->
            val inRange = vm.isInRange(friend, mesh)
            Row(
                Modifier.fillMaxWidth().clickable { vm.open(Dest.Conversation(Conversations.friend(friend.nodeId))) }
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Avatar(friend.nickname)
                    if (inRange) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background).padding(2.dp).clip(CircleShape).background(Palette.Mesh)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.nickname, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            inRange -> stringResource(R.string.friend_in_range)
                            friend.lastSeenAt > 0 -> stringResource(
                                R.string.friend_last_seen, Labels.ago(friend.lastSeenAt),
                                pluralStringResource(R.plurals.hops, friend.lastHops, friend.lastHops),
                            )
                            else -> stringResource(R.string.friend_never_seen)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (inRange) Palette.Mesh else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { removing = friend }) {
                    Icon(AppIcons.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.groups), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.open(Dest.NewGroup) }, enabled = friends.isNotEmpty()) {
                    Icon(AppIcons.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.group_new))
                }
            }
        }
        items(groups, key = { "g${it.id}" }) { group ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.open(Dest.Conversation(Conversations.group(group.id))) }
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(group.name, group = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        group.members.joinToString { it.nickname }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { leaving = group }) {
                    Icon(AppIcons.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    removing?.let { friend ->
        ConfirmDialog(stringResource(R.string.friend_remove_title, friend.nickname), stringResource(R.string.friend_remove_text), { removing = null }) {
            vm.removeFriend(friend)
        }
    }
    leaving?.let { group ->
        ConfirmDialog(stringResource(R.string.group_leave_title, group.name), stringResource(R.string.group_leave_text), { leaving = null }) {
            vm.leaveGroup(group.id)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun MyQrScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val text = remember(settings.nickname) { vm.myQrText() }
    val bitmap = remember(text) { qrBitmap(text).asImageBitmap() }
    var copied by remember { mutableStateOf(false) }
    ScreenScaffold(title = stringResource(R.string.friends_my_code), onBack = { vm.back() }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                bitmap, stringResource(R.string.friends_my_code),
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White),
            )
            Text(settings.nickname, style = MaterialTheme.typography.headlineMedium)
            Text(vm.c.identity.nodeId.toHex(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.qr_hint), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("qr", text))
                copied = true
            }) { Text(stringResource(if (copied) R.string.qr_copied else R.string.qr_copy)) }
        }
    }
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            onResult(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
        } catch (_: NotFoundException) {
        } catch (_: RuntimeException) {
        } finally {
            reader.reset()
            image.close()
        }
    }
}

@Composable
private fun QrCamera(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onResult)
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val main = ContextCompat.getMainExecutor(ctx)
                    analysis.setAnalyzer(executor, QrAnalyzer { text -> main.execute { callback(text) } })
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

@Composable
fun ScanScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }
    var manual by rememberSaveable { mutableStateOf("") }
    var lastText by remember { mutableStateOf("") }

    fun handle(text: String) {
        if (outcome != null || text == lastText) return
        lastText = text
        outcome = vm.handleScan(text)
    }

    ScreenScaffold(title = stringResource(R.string.scan_title), onBack = { vm.back() }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (granted) QrCamera(::handle)
            else Text(stringResource(R.string.scan_no_camera), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.scan_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it.trim() },
                label = { Text(stringResource(R.string.scan_manual)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { handle(manual) }, enabled = manual.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_manual_add))
            }
        }
    }

    outcome?.let { result ->
        val success = result == ScanOutcome.FRIEND_ADDED || result == ScanOutcome.STAFF_ON || result == ScanOutcome.ANCHOR_ON
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(if (success) R.string.scan_ok else R.string.scan_failed)) },
            text = {
                Text(
                    stringResource(
                        when (result) {
                            ScanOutcome.FRIEND_ADDED -> R.string.scan_friend_added
                            ScanOutcome.OWN_CODE -> R.string.scan_own_code
                            ScanOutcome.STAFF_ON -> R.string.scan_staff_on
                            ScanOutcome.ANCHOR_ON -> R.string.scan_anchor_on
                            ScanOutcome.WRONG_EVENT -> R.string.scan_wrong_event
                            ScanOutcome.UNKNOWN -> R.string.scan_unknown
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    outcome = null
                    if (success) {
                        vm.stack.clear()
                        if (result == ScanOutcome.STAFF_ON) vm.tab = Tab.INCIDENTS
                    } else lastText = ""
                }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

@Composable
fun NewGroupScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val max = Limits.GROUP_MAX_MEMBERS - 1
    ScreenScaffold(title = stringResource(R.string.group_new), onBack = { vm.back() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24) }, label = { Text(stringResource(R.string.group_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.group_pick, max), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                items(friends.sortedBy { it.nickname.lowercase() }, key = { it.nodeId }) { friend ->
                    val checked = friend.nodeId in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (checked) selected.remove(friend.nodeId) else if (selected.size < max) selected.add(friend.nodeId)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(friend.nickname, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Button(
                onClick = {
                    vm.createGroup(name, selected.toList())
                    vm.back()
                    vm.tab = Tab.CHAT
                },
                enabled = selected.isNotEmpty() && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.group_create)) }
        }
    }
}
