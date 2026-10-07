package ro.safetyplease.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import ro.safetyplease.app.R
import ro.safetyplease.app.crypto.QrDecoder
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.agoText
import ro.safetyplease.app.text.hopsText
import ro.safetyplease.app.text.rememberNow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Codul QR, cu modulele in [ink] pe alb si fara margine: marginea alba o da cardul pe care sta. */
fun qrBitmap(text: String, size: Int = 640, ink: Int = android.graphics.Color.BLACK): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) ink else android.graphics.Color.WHITE }
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
    val disposed = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            // fara get() blocant: daca CameraX nu e gata, listenerul de mai jos vede ca am plecat si nu mai leaga nimic
            val future = ProcessCameraProvider.getInstance(context)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                if (disposed.get() || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
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
        val stroke = 4.dp.toPx()
        val color = Color.White
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
            drawLine(color.copy(alpha = 0.8f), Offset(left + arm * 0.4f, y), Offset(right - arm * 0.4f, y), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

// culorile cardului cu cod nu urmeaza tema: codul trebuie sa se citeasca la fel ziua si noaptea.
// Verdele de padure din logo; numele alb pe el are 7,5:1, iar codul verde-negru pe alb peste 11:1, cat sa-l prinda orice camera.
private val QrBorder = Color(0xFF2B5E45)
private val QrInk = 0xFF17402E.toInt()
private val QrFrame = Color(0xFFE9E9E9)

/** Latimea cardului cu cod; butoanele de sub el se aliniaza cu el. */
private val CardWidth = 296.dp

/**
 * Codul tau pe un card verde de padure, ca in Signal: patratul alb cu codul si numele tau dedesubt, in alb.
 * Atins, se deschide mare, pe tot ecranul.
 */
@Composable
fun QrBadge(code: String, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = remember(code) { qrBitmap(code, ink = QrInk).asImageBitmap() }
    Column(
        modifier.widthIn(max = CardWidth).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(QrBorder)
            .clickable(onClickLabel = stringResource(R.string.add_friend_enlarge), role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // alb pe orice tema: orice camera trebuie sa il poata citi
        Image(
            bitmap, stringResource(R.string.add_friend_mine),
            Modifier.padding(start = 40.dp, end = 40.dp, top = 32.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .background(Color.White).border(2.dp, QrFrame, RoundedCornerShape(12.dp)).padding(16.dp),
        )
        Text(
            name, style = MaterialTheme.typography.title3, color = Color.White, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
        )
    }
}

/**
 * Adauga prieten, ca ecranul cu codul QR din Signal: comutatorul sus, apoi cardul verde cu codul tau
 * sau camera pentru codul altcuiva.
 */
@Composable
fun AddFriendScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val colors = AppTheme.colors
    val code = remember(settings.nickname) { vm.myQrText() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val activity = LocalActivity.current
    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraAllowed()) }
    // refuzata definitiv, camera se mai poate da doar din setari; la intoarcere verificam din nou
    var blocked by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = cameraAllowed()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        blocked = !it && deniedForGood(activity, listOf(Manifest.permission.CAMERA))
    }
    LaunchedEffect(tab) { if (tab == 1 && !granted) launcher.launch(Manifest.permission.CAMERA) }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }
    var lastText by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var bigCode by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }

    fun handle(text: String) {
        // camera vede acelasi cod de multe ori pe secunda
        if (text == lastText) return
        lastText = text
        val result = vm.handleScan(text)
        outcome = result
        if (result.success) haptics.confirm() else haptics.reject()
    }

    val success = outcome?.takeIf { it.success }
    NavScreen(
        title = stringResource(R.string.add_friend_title),
        background = colors.grouped,
        scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(R.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SegmentedControl(
                listOf(0 to stringResource(R.string.add_friend_tab_code), 1 to stringResource(R.string.add_friend_tab_scan)),
                tab,
                {
                    tab = it
                    outcome = null
                    lastText = ""
                },
                Modifier.padding(top = 8.dp),
            )
            if (success != null) {
                Icon(Sym.CheckCircle, null, Modifier.padding(top = 48.dp).size(64.dp), tint = colors.accent)
                Text(
                    when (success) {
                        is ScanOutcome.FriendAdded -> stringResource(R.string.add_friend_done, success.name)
                        ScanOutcome.AnchorOn -> stringResource(R.string.scan_anchor_on)
                        else -> stringResource(R.string.scan_staff_on)
                    },
                    style = MaterialTheme.typography.body, color = colors.label, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp).padding(top = 16.dp),
                )
                Column(
                    Modifier.widthIn(max = CardWidth).fillMaxWidth().padding(top = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // prietenia merge in ambele sensuri: dupa ce l-ai scanat, el trebuie sa te scaneze pe tine
                    if (success is ScanOutcome.FriendAdded) {
                        AppButton(stringResource(R.string.add_friend_show_mine), {
                            outcome = null
                            lastText = ""
                            tab = 0
                        }, Modifier.fillMaxWidth())
                    }
                    AppButton(stringResource(R.string.done), {
                        when (success) {
                            ScanOutcome.StaffOn -> vm.home(Tab.INCIDENTS)
                            ScanOutcome.AnchorOn -> vm.stack.clear()
                            else -> vm.back()
                        }
                    }, Modifier.fillMaxWidth(), kind = if (success is ScanOutcome.FriendAdded) ButtonKind.Secondary else ButtonKind.Primary)
                }
            } else if (tab == 0) {
                QrBadge(code, settings.nickname, { bigCode = true }, Modifier.padding(top = 24.dp))
                Text(
                    stringResource(R.string.add_friend_mine_hint), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = CardWidth).padding(top = 16.dp),
                )
                // scanarea are tabul ei sus; aici ramane doar varianta pentru cand camera nu merge
                TextLink(stringResource(R.string.add_friend_text_link), { sheet = true }, Modifier.padding(top = 8.dp))
            } else {
                if (granted) {
                    Box(
                        Modifier.padding(top = 24.dp).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(26.dp)).background(Color.Black),
                    ) {
                        QrCamera(::handle, Modifier.fillMaxSize())
                        ScanFrame(Modifier.fillMaxSize())
                    }
                } else {
                    NoCamera(
                        blocked, { if (blocked) openAppSettings(context) else launcher.launch(Manifest.permission.CAMERA) },
                        Modifier.padding(top = 24.dp),
                    )
                }
                Text(
                    stringResource(R.string.add_friend_scan_hint), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp).padding(top = 16.dp),
                )
                AnimatedVisibility(outcome != null, enter = fadeIn(tween(Motion.QUICK)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                    StatusLabel(
                        stringResource(
                            when (outcome) {
                                ScanOutcome.OwnCode -> R.string.scan_own_code
                                ScanOutcome.WrongEvent -> R.string.scan_wrong_event
                                else -> R.string.scan_unknown
                            }
                        ),
                        Modifier.padding(top = 12.dp), icon = Sym.Info, color = colors.redInk,
                    )
                }
                TextLink(stringResource(R.string.add_friend_text_link), { sheet = true }, Modifier.padding(top = 8.dp))
            }
        }
    }
    if (bigCode) BigCodeDialog(code, settings.nickname) { bigCode = false }
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

/** Codul mare, pe alb, peste tot ecranul: de aproape sau in lumina slaba se citeste mai usor. Atingerea il inchide. */
@Composable
fun BigCodeDialog(code: String, name: String, onDismiss: () -> Unit) {
    val bitmap = remember(code) { qrBitmap(code).asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().clickable(onClickLabel = stringResource(R.string.close), onClick = onDismiss).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap, stringResource(R.string.add_friend_mine),
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(name, style = MaterialTheme.typography.title1, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}

private val ScanOutcome.success: Boolean
    get() = this is ScanOutcome.FriendAdded || this == ScanOutcome.StaffOn || this == ScanOutcome.AnchorOn

/** In locul camerei, cand nu avem voie la ea: acelasi patrat, cu explicatia si butonul care cere accesul. */
@Composable
private fun NoCamera(blocked: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(colors.cell).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Sym.QrScan, null, Modifier.size(44.dp), tint = colors.secondaryLabel)
        Text(
            stringResource(R.string.add_friend_scan), style = MaterialTheme.typography.headline, color = colors.label,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.add_friend_no_camera), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        AppButton(
            stringResource(if (blocked) R.string.open_settings else R.string.add_friend_allow_camera), onAllow,
            Modifier.padding(top = 16.dp), compact = true,
        )
    }
}

/** Foaia pentru codul ca text: codul tau de copiat, sus, si campul pentru codul prietenului, jos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeSheet(myCode: String, onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    var copied by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = colors.grouped,
        contentColor = colors.label,
        dragHandle = {
            Box(Modifier.padding(vertical = 6.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(colors.tertiaryLabel))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).imePadding()) {
            Text(
                stringResource(R.string.add_friend_text_link), style = MaterialTheme.typography.headline, color = colors.label,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
            )
            SectionTitle(stringResource(R.string.code_sheet_mine))
            InsetGroup {
                Text(
                    myCode, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                )
                GroupDivider(start = Gutter)
                GroupRow(
                    stringResource(if (copied) R.string.code_sheet_copied else R.string.code_sheet_copy),
                    icon = if (copied) Sym.Check else Sym.Copy, tint = colors.accent,
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("cod", myCode))
                        copied = true
                    },
                )
            }
            SectionTitle(stringResource(R.string.code_sheet_theirs))
            InputField(
                manual, { manual = it.trim() }, stringResource(R.string.code_sheet_placeholder), Modifier.fillMaxWidth().padding(horizontal = Gutter),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (manual.isNotBlank()) onUse(manual) }),
                background = colors.cell,
            )
            AppButton(
                stringResource(R.string.code_sheet_use), { onUse(manual) },
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 16.dp), enabled = manual.isNotBlank(),
            )
        }
    }
}

/**
 * Grup nou, ca in Signal: numele sus, prietenii alesi ca bule deasupra listei, apoi prietenii intr-un card,
 * fiecare cu bifa lui. Butonul de creare pluteste jos, deasupra tastaturii cand e deschisa.
 */
@Composable
fun NewGroupScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val max = Limits.GROUP_MAX_MEMBERS - 1
    val sorted = remember(friends) { friends.sortedBy { it.nickname.lowercase() } }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    // tastatura acopera si bara de gesturi: conteaza doar cea mai inalta dintre ele
    val bottom = maxOf(WindowInsets.ime.asPaddingValues().calculateBottomPadding(), LocalBottomClearance.current)
    val fade = with(LocalDensity.current) { 24.dp.toPx() }

    NavScreen(
        title = stringResource(R.string.group_new),
        background = colors.grouped,
        scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(R.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottom + 8.dp + 50.dp + 24.dp),
            ) {
                item(key = "name") {
                    InputField(
                        name, { name = it.take(24) }, stringResource(R.string.group_name),
                        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp), background = colors.cell,
                    )
                }
                item(key = "selected") {
                    AnimatedVisibility(
                        selected.isNotEmpty(),
                        enter = fadeIn(tween(Motion.QUICK)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Gutter, end = Gutter, top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (friend in sorted.filter { it.nodeId in selected }) {
                                SelectedMember(friend.nickname) { selected.remove(friend.nodeId) }
                            }
                        }
                    }
                }
                if (sorted.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.group_no_friends), style = MaterialTheme.typography.subheadline,
                                color = colors.secondaryLabel, textAlign = TextAlign.Center,
                            )
                            TextLink(stringResource(R.string.menu_add_friend), { vm.open(Dest.AddFriend) }, Modifier.padding(top = 4.dp))
                        }
                    }
                } else {
                    item(key = "header") { SectionTitle(stringResource(R.string.group_pick, max)) }
                    item(key = "friends") {
                        InsetGroup {
                            sorted.forEachIndexed { index, friend ->
                                val checked = friend.nodeId in selected
                                GroupRow(
                                    friend.nickname,
                                    Modifier.toggleable(checked, role = Role.Checkbox) {
                                        when {
                                            checked -> selected.remove(friend.nodeId)
                                            selected.size < max -> selected.add(friend.nodeId)
                                            else -> haptics.reject()
                                        }
                                    },
                                    subtitle = presenceText(friend, nearby, now),
                                    leading = { Avatar(friend.nickname, 36.dp, near = nearby.isInRange(friend.nodeId)) },
                                    trailing = { RoundCheck(checked) },
                                )
                                if (index < sorted.lastIndex) GroupDivider(start = 64.dp)
                            }
                        }
                    }
                }
            }
            // randurile trec pe sub buton printr-o estompare spre fundal, ca sub bara de sus
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.grouped.copy(alpha = 0f), colors.grouped), endY = fade))
                    .padding(start = Gutter, end = Gutter, top = 24.dp, bottom = bottom + 8.dp),
            ) {
                AppButton(
                    stringResource(R.string.group_create),
                    {
                        vm.createGroup(name, selected.toList())
                        vm.home(Tab.MESSAGES)
                    },
                    Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && name.isNotBlank(),
                )
            }
        }
    }
}

/** Un prieten ales, deasupra listei: bula lui cu un x mic in colt; atinsa, il scoate din grup. */
@Composable
private fun SelectedMember(name: String, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.remove), role = Role.Button, onClick = onRemove).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(name, 48.dp)
            // inelul in culoarea fundalului desparte x-ul de bula
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(22.dp).clip(CircleShape).background(colors.grouped)
                    .padding(2.dp).clip(CircleShape).background(colors.secondaryLabel),
                contentAlignment = Alignment.Center,
            ) { Icon(Sym.Close, null, Modifier.size(10.dp), tint = colors.cell) }
        }
        Text(
            name, style = MaterialTheme.typography.caption1, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Bifa rotunda din lista de alegere: cerc gol, sau plin albastru cu bifa alba. */
@Composable
private fun RoundCheck(checked: Boolean) {
    val colors = AppTheme.colors
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .then(if (checked) Modifier.background(colors.accent) else Modifier.border(1.5.dp, colors.tertiaryLabel, CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Sym.Check, null, Modifier.size(14.dp), tint = colors.onAccent) }
}

/** Antetul unui om sau al unui grup, ca in Signal: bula mare centrata, numele si un rand de lamurire. */
@Composable
fun PersonHeader(name: String, note: String, modifier: Modifier = Modifier, near: Boolean = false, group: Boolean = false, size: Dp = 80.dp) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name, size, near = near, group = group)
        Text(
            name, style = MaterialTheme.typography.title1, color = colors.label, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
        )
        Text(
            note, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 2.dp),
        )
    }
}

/**
 * Profilul unui prieten sau al unui grup, ca setarile unei conversatii din Signal: antetul, butonul de mesaj,
 * detaliile in carduri, membrii grupului, apoi, separat, ce se poate sterge.
 */
@Composable
fun ProfileScreen(vm: AppViewModel, conversation: String) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val friend = friends.firstOrNull { Conversations.friend(it.nodeId) == conversation }
    val group = groups.firstOrNull { Conversations.group(it.id) == conversation }
    var confirm by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    // numele urca in bara de sus abia cand cel din antet a trecut pe sub ea
    val nameUnderBar by remember { derivedStateOf { scroll.value > with(density) { 150.dp.toPx() } } }
    if (friend == null && group == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val title = friend?.nickname ?: group!!.name

    NavScreen(
        title = if (nameUnderBar) title else "",
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            PersonHeader(
                title,
                if (friend != null) presenceText(friend, nearby, now)
                else pluralStringResource(R.plurals.group_members, group!!.members.size, group.members.size),
                near = friend != null && nearby.isInRange(friend.nodeId), group = group != null, size = 88.dp,
            )
            ActionTile(
                Sym.Chat, stringResource(R.string.profile_message), { vm.back() },
                Modifier.align(Alignment.CenterHorizontally).width(80.dp), background = colors.cell,
            )
            InsetGroup(Modifier.padding(top = 24.dp)) {
                if (friend != null) {
                    GroupRow(stringResource(R.string.profile_added), value = Labels.date(friend.addedAt), icon = Sym.PersonAdd)
                    GroupDivider()
                    // drumul sta sub titlu: valoarea din dreapta ramane scurta si pe ecranele inguste
                    GroupRow(
                        stringResource(R.string.profile_last_seen),
                        subtitle = if (friend.lastSeenAt > 0) hopsText(friend.lastHops) else null,
                        value = if (friend.lastSeenAt > 0) agoText(friend.lastSeenAt, now) else stringResource(R.string.presence_never),
                        icon = Sym.History,
                    )
                } else if (group != null) {
                    GroupRow(stringResource(R.string.profile_created), value = Labels.date(group.createdAt), icon = Sym.Clock)
                }
            }
            if (group != null) {
                SectionTitle(pluralStringResource(R.plurals.group_members, group.members.size, group.members.size))
                InsetGroup {
                    group.members.forEachIndexed { index, member ->
                        val known = friends.firstOrNull { it.nodeId == member.nodeId }
                        val me = member.nodeId == vm.c.identity.nodeId
                        GroupRow(
                            if (me) stringResource(R.string.msg_you) else known?.nickname ?: member.nickname,
                            subtitle = known?.let { presenceText(it, nearby, now) },
                            leading = { Avatar(member.nickname, 36.dp, near = known != null && nearby.isInRange(known.nodeId)) },
                        )
                        if (index < group.members.lastIndex) GroupDivider(start = 64.dp)
                    }
                }
            }
            InsetGroup(Modifier.padding(top = 24.dp)) {
                GroupRow(
                    stringResource(if (friend != null) R.string.profile_remove_friend else R.string.profile_leave_group),
                    icon = if (friend != null) Sym.Delete else Sym.Logout, tint = colors.redInk, onClick = { confirm = true },
                )
            }
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
