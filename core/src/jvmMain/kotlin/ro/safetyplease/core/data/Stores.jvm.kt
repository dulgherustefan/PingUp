package ro.safetyplease.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import okio.Path.Companion.toOkioPath
import ro.safetyplease.core.crypto.Revocations
import ro.safetyplease.core.crypto.RevokedList
import java.io.File

// Android and the JVM tests name their files as java.io.File; the stores themselves take an okio Path.

fun <T> JsonStore(
    file: File,
    serializer: KSerializer<T>,
    default: T,
    scope: CoroutineScope,
    schemaVersion: Int = 1,
    migrate: (fromVersion: Int, data: JsonElement) -> JsonElement = { _, data -> data },
): JsonStore<T> = JsonStore(file.toOkioPath(), serializer, default, scope, schemaVersion, migrate)

fun ChatStore(dir: File, scope: CoroutineScope): ChatStore = ChatStore(dir.toOkioPath(), scope)

fun Revocations.Companion.store(file: File, scope: CoroutineScope): JsonStore<RevokedList> = store(file.toOkioPath(), scope)
