package ro.safetyplease.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import okio.source
import java.io.FileNotFoundException

@Composable
actual fun rememberFilePicker(types: List<String>, multiple: Boolean, onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val callback by rememberUpdatedState(onPicked)
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        callback(uris.map { uri -> PickedFile { resolver.openInputStream(uri)?.source() ?: throw FileNotFoundException(uri.toString()) } })
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked(listOfNotNull(it)) }
    return { if (multiple) many.launch(types.toTypedArray()) else one.launch(types.toTypedArray()) }
}
