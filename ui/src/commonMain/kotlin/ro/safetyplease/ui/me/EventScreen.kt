package ro.safetyplease.ui.me

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.use
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.event.Added
import ro.safetyplease.core.event.EventKind
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.event.Loaded
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.event.StoredPack
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.PickedFile
import ro.safetyplease.ui.common.rememberFilePicker
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
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.event_bundled
import ro.safetyplease.ui.resources.event_bundled_text
import ro.safetyplease.ui.resources.event_download_failed
import ro.safetyplease.ui.resources.event_exercise
import ro.safetyplease.ui.resources.event_expired
import ro.safetyplease.ui.resources.event_in_use
import ro.safetyplease.ui.resources.event_known
import ro.safetyplease.ui.resources.event_link_placeholder
import ro.safetyplease.ui.resources.event_load
import ro.safetyplease.ui.resources.event_load_file
import ro.safetyplease.ui.resources.event_load_hint
import ro.safetyplease.ui.resources.event_load_link
import ro.safetyplease.ui.resources.event_loaded
import ro.safetyplease.ui.resources.event_malformed
import ro.safetyplease.ui.resources.event_not_yet
import ro.safetyplease.ui.resources.event_older
import ro.safetyplease.ui.resources.event_pack_line
import ro.safetyplease.ui.resources.event_packs
import ro.safetyplease.ui.resources.event_real
import ro.safetyplease.ui.resources.event_remove
import ro.safetyplease.ui.resources.event_remove_text
import ro.safetyplease.ui.resources.event_revoked
import ro.safetyplease.ui.resources.event_shared_key
import ro.safetyplease.ui.resources.event_switch
import ro.safetyplease.ui.resources.event_switch_exercise_text
import ro.safetyplease.ui.resources.event_switch_text
import ro.safetyplease.ui.resources.event_switch_title
import ro.safetyplease.ui.resources.event_tampered
import ro.safetyplease.ui.resources.event_title
import ro.safetyplease.ui.resources.event_wrong_chain
import ro.safetyplease.ui.text.dayText

/** What a load from a file or a link did, in a sentence. */
fun loadMessage(loaded: Loaded): StringResource = when (loaded.status) {
    PackStatus.VALID -> when (loaded.added) {
        Added.KNOWN -> Res.string.event_known
        Added.OLDER -> Res.string.event_older
        Added.SHARED_KEY -> Res.string.event_shared_key
        else -> Res.string.event_loaded
    }
    PackStatus.MALFORMED -> Res.string.event_malformed
    PackStatus.TAMPERED -> Res.string.event_tampered
    PackStatus.WRONG_CHAIN -> Res.string.event_wrong_chain
    PackStatus.REVOKED -> Res.string.event_revoked
    PackStatus.NOT_YET -> Res.string.event_not_yet
    PackStatus.EXPIRED -> Res.string.event_expired
}

/**
 * The event packs on this phone: which one is in use, the others to switch to (the app restarts) and loading a new
 * one from a file or an https link before the festival.
 */
@Composable
fun EventScreen(vm: AppViewModel) {
    val c = vm.c
    val shelf by c.events.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    var switchTo by remember { mutableStateOf<StoredPack?>(null) }
    var backToBundled by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<StoredPack?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    val inUse = c.event?.hashHex.orEmpty()

    suspend fun report(text: String?) {
        val message = if (text == null) Res.string.event_download_failed else loadMessage(c.loadEventPack(text))
        vm.phone.toast(getString(message, vm.phone.appName), long = true)
    }
    val picker = rememberFilePicker(listOf("*/*")) { files ->
        vm.viewModelScope.launch { report(readPack(files.first()) ?: "") }
    }

    NavScreen(
        title = stringResource(Res.string.event_title), background = colors.grouped, scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column {
                SectionTitle(stringResource(Res.string.event_packs), top = 0.dp)
                InsetGroup {
                    GroupRow(
                        stringResource(Res.string.event_bundled), subtitle = stringResource(Res.string.event_bundled_text), icon = Sym.MapFill,
                        value = stringResource(Res.string.event_in_use).takeIf { inUse.isEmpty() },
                        onClick = { if (inUse.isNotEmpty()) backToBundled = true },
                    )
                    for (pack in shelf.packs) {
                        GroupDivider()
                        GroupRow(
                            pack.name.ifEmpty { pack.id }, subtitle = packLine(pack),
                            icon = if (pack.kind == EventKind.EXERCISE) Sym.Science else Sym.Flag,
                            value = stringResource(Res.string.event_in_use).takeIf { pack.hash == inUse },
                            onClick = { if (pack.hash != inUse) switchTo = pack },
                            onLongClick = { remove = pack },
                        )
                    }
                }
            }
            InsetGroup {
                GroupRow(stringResource(Res.string.event_load_file), icon = Sym.Add, onClick = picker)
                GroupDivider()
                GroupRow(stringResource(Res.string.event_load_link), icon = Sym.Copy, onClick = { linkDialog = true })
            }
            Text(
                stringResource(Res.string.event_load_hint), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter * 2),
            )
        }
    }

    switchTo?.let { pack ->
        val text = stringResource(if (pack.kind == EventKind.EXERCISE) Res.string.event_switch_exercise_text else Res.string.event_switch_text)
        val expired = stringResource(Res.string.event_expired)
        ConfirmDialog(
            stringResource(Res.string.event_switch_title), text, { switchTo = null }, confirmLabel = stringResource(Res.string.event_switch),
        ) {
            if (c.usable(pack)) c.switchEvent(pack.hash) else vm.phone.toast(expired, long = true)
        }
    }
    if (backToBundled) {
        ConfirmDialog(
            stringResource(Res.string.event_switch_title), stringResource(Res.string.event_switch_text), { backToBundled = false },
            confirmLabel = stringResource(Res.string.event_switch),
        ) { c.switchEvent("") }
    }
    remove?.let { pack ->
        ConfirmDialog(
            stringResource(Res.string.event_remove), stringResource(Res.string.event_remove_text), { remove = null },
            confirmLabel = stringResource(Res.string.event_remove), destructive = true,
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
            onDismiss = { linkDialog = false }, title = stringResource(Res.string.event_load_link),
            confirmLabel = stringResource(Res.string.event_load), onConfirm = load, confirmEnabled = link.isNotBlank(),
        ) {
            InputField(
                link, { link = it.trim() }, stringResource(Res.string.event_link_placeholder), Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank()) load() }),
            )
        }
    }
}

@Composable
private fun packLine(pack: StoredPack): String {
    val kind = stringResource(if (pack.kind == EventKind.EXERCISE) Res.string.event_exercise else Res.string.event_real)
    val until = dayText(pack.validUntil * 1000)
    return stringResource(Res.string.event_pack_line, kind, pack.version, until)
}

private suspend fun readPack(file: PickedFile): String? = withContext(Dispatchers.IO) {
    runCatching { file.open().use(EventPacks::read) }.getOrNull()
}
