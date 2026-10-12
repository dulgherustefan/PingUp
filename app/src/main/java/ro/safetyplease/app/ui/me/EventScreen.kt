package ro.safetyplease.app.ui.me

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.source
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.core.event.Added
import ro.safetyplease.core.event.EventKind
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.event.Loaded
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.event.StoredPack
import java.text.DateFormat
import java.util.Date

/** What a load from a file or a link did, in a sentence. */
@StringRes
fun loadMessage(loaded: Loaded): Int = when (loaded.status) {
    PackStatus.VALID -> when (loaded.added) {
        Added.KNOWN -> R.string.event_known
        Added.OLDER -> R.string.event_older
        Added.SHARED_KEY -> R.string.event_shared_key
        else -> R.string.event_loaded
    }
    PackStatus.MALFORMED -> R.string.event_malformed
    PackStatus.TAMPERED -> R.string.event_tampered
    PackStatus.WRONG_CHAIN -> R.string.event_wrong_chain
    PackStatus.REVOKED -> R.string.event_revoked
    PackStatus.NOT_YET -> R.string.event_not_yet
    PackStatus.EXPIRED -> R.string.event_expired
}

/**
 * The event packs on this phone: which one is in use, the others to switch to (the app restarts) and loading a new
 * one from a file or an https link before the festival.
 */
@Composable
fun EventScreen(vm: AppViewModel) {
    val c = vm.c
    val shelf by c.events.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    var switchTo by remember { mutableStateOf<StoredPack?>(null) }
    var backToBundled by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<StoredPack?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    val inUse = c.event?.hashHex.orEmpty()

    fun report(text: String?) {
        val message = if (text == null) R.string.event_download_failed else loadMessage(c.loadEventPack(text))
        Toast.makeText(context, resources.getString(message, resources.getString(R.string.app_name)), Toast.LENGTH_LONG).show()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.viewModelScope.launch { report(readPack(context, uri) ?: "") }
    }

    NavScreen(
        title = stringResource(R.string.event_title), background = colors.grouped, scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column {
                SectionTitle(stringResource(R.string.event_packs), top = 0.dp)
                InsetGroup {
                    GroupRow(
                        stringResource(R.string.event_bundled), subtitle = stringResource(R.string.event_bundled_text), icon = Sym.MapFill,
                        value = stringResource(R.string.event_in_use).takeIf { inUse.isEmpty() },
                        onClick = { if (inUse.isNotEmpty()) backToBundled = true },
                    )
                    for (pack in shelf.packs) {
                        GroupDivider()
                        GroupRow(
                            pack.name.ifEmpty { pack.id }, subtitle = packLine(pack),
                            icon = if (pack.kind == EventKind.EXERCISE) Sym.Science else Sym.Flag,
                            value = stringResource(R.string.event_in_use).takeIf { pack.hash == inUse },
                            onClick = { if (pack.hash != inUse) switchTo = pack },
                            onLongClick = { remove = pack },
                        )
                    }
                }
            }
            InsetGroup {
                GroupRow(stringResource(R.string.event_load_file), icon = Sym.Add, onClick = { picker.launch(arrayOf("*/*")) })
                GroupDivider()
                GroupRow(stringResource(R.string.event_load_link), icon = Sym.Copy, onClick = { linkDialog = true })
            }
            Text(
                stringResource(R.string.event_load_hint), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter * 2),
            )
        }
    }

    switchTo?.let { pack ->
        val text = stringResource(if (pack.kind == EventKind.EXERCISE) R.string.event_switch_exercise_text else R.string.event_switch_text)
        ConfirmDialog(
            stringResource(R.string.event_switch_title), text, { switchTo = null }, confirmLabel = stringResource(R.string.event_switch),
        ) {
            if (c.usable(pack)) c.switchEvent(pack.hash) else Toast.makeText(context, R.string.event_expired, Toast.LENGTH_LONG).show()
        }
    }
    if (backToBundled) {
        ConfirmDialog(
            stringResource(R.string.event_switch_title), stringResource(R.string.event_switch_text), { backToBundled = false },
            confirmLabel = stringResource(R.string.event_switch),
        ) { c.switchEvent("") }
    }
    remove?.let { pack ->
        ConfirmDialog(
            stringResource(R.string.event_remove), stringResource(R.string.event_remove_text), { remove = null },
            confirmLabel = stringResource(R.string.event_remove), destructive = true,
        ) {
            c.removeEventPack(pack.hash)
            // everything running was built from the pack in use
            if (pack.hash == inUse) c.switchEvent("")
        }
    }
    if (linkDialog) {
        var link by remember { mutableStateOf("") }
        val load = {
            linkDialog = false
            vm.viewModelScope.launch { report(c.downloadEventPack(link)) }
            Unit
        }
        AppDialog(
            onDismiss = { linkDialog = false }, title = stringResource(R.string.event_load_link),
            confirmLabel = stringResource(R.string.event_load), onConfirm = load, confirmEnabled = link.isNotBlank(),
        ) {
            InputField(
                link, { link = it.trim() }, stringResource(R.string.event_link_placeholder), Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank()) load() }),
            )
        }
    }
}

@Composable
private fun packLine(pack: StoredPack): String {
    val kind = stringResource(if (pack.kind == EventKind.EXERCISE) R.string.event_exercise else R.string.event_real)
    val until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pack.validUntil * 1000))
    return stringResource(R.string.event_pack_line, kind, pack.version, until)
}

private suspend fun readPack(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { EventPacks.read(it.source()) } }.getOrNull()
}
