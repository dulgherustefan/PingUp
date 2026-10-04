package ro.safetyplease.app.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import ro.safetyplease.app.R
import ro.safetyplease.app.crypto.QrDecoder
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.protocol.Limits
import java.util.concurrent.Executors

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            QrDecoder.decode(data, plane.rowStride, image.width, image.height)?.let(onResult)
        } catch (_: RuntimeException) {
        } finally {
            image.close()
        }
    }
}

@Composable
private fun QrCamera(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier,
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

/** Colturile vizorului si o linie care urca si coboara: semn ca aparatul cauta un cod. */
@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val sweep = if (reduce) 0.5f else {
        val transition = rememberInfiniteTransition(label = "scan")
        transition.animateFloat(
            initialValue = 0.18f, targetValue = 0.82f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "scanLine",
        ).value
    }
    Canvas(modifier) {
        val inset = size.width * 0.14f
        val arm = size.width * 0.1f
        val stroke = 3.dp.toPx()
        val color = Brand.Sage
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        for ((x, dx) in listOf(left to 1f, right to -1f)) {
            for ((y, dy) in listOf(top to 1f, bottom to -1f)) {
                drawLine(color, Offset(x, y), Offset(x + arm * dx, y), stroke, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y + arm * dy), stroke, StrokeCap.Round)
            }
        }
        if (!reduce) {
            val y = size.height * sweep
            drawLine(color.copy(alpha = 0.7f), Offset(left + arm * 0.4f, y), Offset(right - arm * 0.4f, y), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Un singur ecran: codul tau sus, camera pentru codul prietenului dedesubt. Tot aici se scaneaza si codul de staff. */
@Composable
fun AddFriendScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val haptics = rememberHaptics()
    val code = remember(settings.nickname) { vm.myQrText() }
    val bitmap = remember(code) { qrBitmap(code).asImageBitmap() }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }
    var lastText by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var bigCode by remember { mutableStateOf(false) }

    fun handle(text: String) {
        // camera vede acelasi cod de multe ori pe secunda
        if (text == lastText) return
        lastText = text
        val result = vm.handleScan(text)
        outcome = result
        if (result.success) haptics.confirm() else haptics.reject()
    }

    val success = outcome?.takeIf { it.success }
    ScreenScaffold(title = stringResource(R.string.add_friend_title), onBack = { vm.back() }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // alb pe ambele teme: orice camera trebuie sa il poata citi
                    Image(
                        bitmap, stringResource(R.string.add_friend_mine),
                        Modifier.size(148.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                            .clickable(onClickLabel = stringResource(R.string.add_friend_enlarge), role = Role.Button) { bigCode = true },
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.add_friend_mine), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        Text(settings.nickname, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.add_friend_mine_hint), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }
            }

            if (success != null) {
                AppCard(Modifier.fillMaxWidth().enter()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SuccessCheck(size = 64.dp)
                        Text(
                            when (success) {
                                is ScanOutcome.FriendAdded -> stringResource(R.string.add_friend_done, success.name)
                                ScanOutcome.AnchorOn -> stringResource(R.string.scan_anchor_on)
                                else -> stringResource(R.string.scan_staff_on)
                            },
                            style = MaterialTheme.typography.bodyLarge, color = colors.text, textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (success is ScanOutcome.FriendAdded) {
                                AppButton(stringResource(R.string.add_friend_again), {
                                    outcome = null
                                    lastText = ""
                                }, kind = ButtonKind.Secondary, compact = true)
                            }
                            AppButton(stringResource(R.string.done), {
                                when (success) {
                                    ScanOutcome.StaffOn -> vm.home(Tab.INCIDENTS)
                                    ScanOutcome.AnchorOn -> vm.stack.clear()
                                    else -> vm.back()
                                }
                            }, compact = true)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                    SectionLabel(stringResource(R.string.add_friend_scan))
                    Text(stringResource(R.string.add_friend_scan_hint), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                if (granted) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CardShape).background(Color.Black)) {
                        QrCamera(::handle, Modifier.fillMaxSize())
                        ScanFrame(Modifier.fillMaxSize())
                    }
                } else {
                    ProblemCard(
                        AppIcons.Camera, stringResource(R.string.add_friend_scan), stringResource(R.string.add_friend_no_camera),
                        action = stringResource(R.string.add_friend_allow_camera), onAction = { launcher.launch(Manifest.permission.CAMERA) },
                    )
                }
                AnimatedVisibility(outcome != null, enter = fadeIn(tween(Motion.QUICK)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.danger.copy(alpha = 0.14f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Info, null, tint = colors.danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(
                                when (outcome) {
                                    ScanOutcome.OwnCode -> R.string.scan_own_code
                                    ScanOutcome.WrongEvent -> R.string.scan_wrong_event
                                    else -> R.string.scan_unknown
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium, color = colors.text,
                        )
                    }
                }
            }
            TextAction(stringResource(R.string.add_friend_text_link), { sheet = true }, Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (bigCode) {
        // codul mare, pe alb: de aproape sau in lumina slaba se citeste mai usor
        Dialog(onDismissRequest = { bigCode = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { bigCode = false }.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    bitmap, stringResource(R.string.add_friend_mine),
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(8.dp),
                )
                Spacer(Modifier.height(20.dp))
                Text(settings.nickname, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
    if (sheet) {
        CodeSheet(
            myCode = code,
            onUse = {
                sheet = false
                handle(it)
            },
            onDismiss = { sheet = false },
        )
    }
}

private val ScanOutcome.success: Boolean
    get() = this is ScanOutcome.FriendAdded || this == ScanOutcome.StaffOn || this == ScanOutcome.AnchorOn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeSheet(myCode: String, onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    var copied by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.code_sheet_mine))
            Text(myCode, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AppButton(
                stringResource(if (copied) R.string.code_sheet_copied else R.string.code_sheet_copy),
                {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("cod", myCode))
                    copied = true
                },
                kind = ButtonKind.Secondary, icon = if (copied) AppIcons.Check else AppIcons.Copy, compact = true,
            )
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.code_sheet_theirs))
            PillTextField(
                manual, { manual = it.trim() }, stringResource(R.string.code_sheet_placeholder),
                Modifier.fillMaxWidth(), container = colors.cardHigh,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (manual.isNotBlank()) onUse(manual) }),
            )
            AppButton(stringResource(R.string.code_sheet_use), { onUse(manual) }, Modifier.fillMaxWidth(), enabled = manual.isNotBlank())
        }
    }
}

@Composable
fun NewGroupScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    var name by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val max = Limits.GROUP_MAX_MEMBERS - 1
    val sorted = remember(friends) { friends.sortedBy { it.nickname.lowercase() } }
    ScreenScaffold(
        title = stringResource(R.string.group_new), onBack = { vm.back() },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                AppButton(
                    stringResource(R.string.group_create),
                    {
                        vm.createGroup(name, selected.toList())
                        vm.home(Tab.MESSAGES)
                    },
                    Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && name.isNotBlank(),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PillTextField(
                name, { name = it.take(24) }, stringResource(R.string.group_name),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), leading = AppIcons.People,
            )
            AnimatedVisibility(selected.isNotEmpty(), enter = fadeIn(tween(Motion.QUICK)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    for (friend in sorted.filter { it.nodeId in selected }) {
                        Column(
                            Modifier.width(56.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { selected.remove(friend.nodeId) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Avatar(friend.nickname, 48.dp, near = true)
                            Text(friend.nickname, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text(
                stringResource(if (sorted.isEmpty()) R.string.group_no_friends else R.string.group_pick, max),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(sorted, key = { it.nodeId }) { friend ->
                    val checked = friend.nodeId in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Checkbox) {
                            if (checked) selected.remove(friend.nodeId) else if (selected.size < max) selected.add(friend.nodeId)
                        }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(friend.nickname, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(friend.nickname, style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(26.dp).clip(CircleShape)
                                .then(if (checked) Modifier.background(colors.accent) else Modifier.border(1.5.dp, colors.textSecondary.copy(alpha = 0.5f), CircleShape)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) Icon(AppIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Antetul profilului e verde inchis pe ambele teme, deci iconitele barei de stare trebuie sa fie deschise cat e pe ecran. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    val dark = LocalAppColors.current.dark
    DisposableEffect(view, dark) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = !dark }
    }
}

@Composable
fun ProfileScreen(vm: AppViewModel, conversation: String) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val friend = friends.firstOrNull { Conversations.friend(it.nodeId) == conversation }
    val group = groups.firstOrNull { Conversations.group(it.id) == conversation }
    var confirm by remember { mutableStateOf(false) }
    if (friend == null && group == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    LightStatusBarIcons()
    val title = friend?.nickname ?: group!!.name

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)).background(colors.forest)
                .statusBarsPadding().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                IconAction(AppIcons.Back, stringResource(R.string.back), { vm.back() }, tint = Color.White)
            }
            Avatar(title, 96.dp, group = group != null, onDark = true)
            Spacer(Modifier.height(14.dp))
            Text(
                title, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (friend != null) presenceText(vm, friend, mesh)
                else pluralStringResource(R.plurals.group_members, group!!.members.size, group.members.size),
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f),
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (friend != null) {
                AppCard(Modifier.fillMaxWidth()) {
                    InfoRow(stringResource(R.string.profile_added), Labels.date(friend.addedAt))
                    Spacer(Modifier.height(12.dp))
                    InfoRow(
                        stringResource(R.string.profile_last_seen),
                        if (friend.lastSeenAt > 0) agoText(friend.lastSeenAt) + " · " + hopsText(friend.lastHops) else stringResource(R.string.presence_never),
                    )
                }
            } else if (group != null) {
                AppCard(Modifier.fillMaxWidth()) { InfoRow(stringResource(R.string.profile_created), Labels.date(group.createdAt)) }
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel(stringResource(R.string.profile_members))
                    Spacer(Modifier.height(6.dp))
                    for (member in group.members) {
                        val known = friends.firstOrNull { it.nodeId == member.nodeId }
                        val me = member.nodeId == vm.c.identity.nodeId
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(member.nickname, 40.dp, near = known != null && vm.isInRange(known, mesh))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    if (me) stringResource(R.string.msg_you) else known?.nickname ?: member.nickname,
                                    style = MaterialTheme.typography.titleMedium, color = colors.text,
                                )
                                if (known != null) {
                                    Text(presenceText(vm, known, mesh), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            AppButton(
                stringResource(if (friend != null) R.string.profile_remove_friend else R.string.profile_leave_group), { confirm = true },
                Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = if (friend != null) AppIcons.Delete else AppIcons.Leave,
            )
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = if (friend != null) stringResource(R.string.friend_remove_title, friend.nickname) else stringResource(R.string.group_leave_title, group!!.name),
            text = stringResource(if (friend != null) R.string.friend_remove_text else R.string.group_leave_text),
            onDismiss = { confirm = false },
            confirmLabel = stringResource(if (friend != null) R.string.remove else R.string.profile_leave_group),
            destructive = true,
        ) {
            vm.home(Tab.MESSAGES)
            if (friend != null) vm.removeFriend(friend) else if (group != null) vm.leaveGroup(group.id)
        }
    }
}
