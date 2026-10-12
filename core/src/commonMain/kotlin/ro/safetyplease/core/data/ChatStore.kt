package ro.safetyplease.core.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** What `chat.json` holds since version 2: the outbox only. */
@Serializable
data class ChatOutbox(val outbox: List<OutboxItem> = emptyList())

/**
 * Chat as one [ChatData], saved as one file per conversation in `chat/` plus the outbox in `chat.json`, so a new
 * message rewrites its own conversation, not the whole history.
 *
 * Version 1 kept everything in `chat.json`. On the first start after it, the messages move to their conversations'
 * files, then `chat.json` becomes version 2; its `.bak` keeps the version 1 file. If the move is cut short, the next
 * start makes it again from the same file.
 */
class ChatStore(private val dir: Path, private val scope: CoroutineScope) {
    private val conversationsDir = dir / "chat"
    private var migrated: List<ChatMessage>? = null
    private val outboxStore = JsonStore(
        dir / "chat.json", ChatOutbox.serializer(), ChatOutbox(), scope, SCHEMA_VERSION, ::migrate,
    )
    private val conversations = HashMap<String, JsonStore<List<ChatMessage>>>()
    private val lock = SynchronizedObject()
    private val _state: MutableStateFlow<ChatData>

    val state: StateFlow<ChatData> get() = _state
    val value: ChatData get() = _state.value

    init {
        (FileSystem.SYSTEM.listOrNull(conversationsDir) ?: emptyList())
            .filter { it.name.endsWith(SUFFIX) && FileSystem.SYSTEM.metadataOrNull(it)?.isRegularFile == true }
            .map { it.name.removeSuffix(SUFFIX) }
            .filter { it.matches(CONVERSATION) }
            .forEach { conversations[it] = storeOf(it) }
        migrated?.let { messages ->
            for ((conversation, list) in messages.groupBy { it.conversation }) {
                if (!conversation.matches(CONVERSATION)) continue
                conversations.getOrPut(conversation) { storeOf(conversation) }.apply {
                    update { list }
                    flush()
                }
            }
            outboxStore.flush()
        }
        val messages = conversations.values.flatMap { it.value }.sortedBy { it.timeMs }
        _state = MutableStateFlow(ChatData(messages, outboxStore.value.outbox))
    }

    /** Writes only the conversations whose messages changed, and the outbox if it did. */
    fun update(transform: (ChatData) -> ChatData) {
        synchronized(lock) {
            val old = _state.value
            val new = transform(old)
            _state.value = new
            if (new.outbox != old.outbox) outboxStore.update { ChatOutbox(new.outbox) }
            if (new.messages != old.messages) save(old.messages, new.messages)
        }
    }

    fun flush() {
        synchronized(lock) {
            outboxStore.flush()
            conversations.values.forEach { it.flush() }
        }
    }

    private fun save(old: List<ChatMessage>, new: List<ChatMessage>) {
        val before = old.groupBy { it.conversation }
        val after = new.groupBy { it.conversation }
        for (conversation in before.keys + after.keys) {
            val list = after[conversation].orEmpty()
            if (list == before[conversation].orEmpty() || !conversation.matches(CONVERSATION)) continue
            if (list.isEmpty()) {
                conversations[conversation]?.reset()
            } else {
                conversations.getOrPut(conversation) { storeOf(conversation) }.update { list }
            }
        }
    }

    private fun storeOf(conversation: String) = JsonStore(
        conversationsDir / (conversation + SUFFIX), ListSerializer(ChatMessage.serializer()), emptyList(), scope,
    )

    /** Version 1 held the messages too; they are kept aside for [init] to write out. */
    private fun migrate(fromVersion: Int, data: JsonElement): JsonElement {
        if (fromVersion < 2) {
            val messages = data.jsonObject["messages"]
            if (messages != null) migrated = json.decodeFromJsonElement(ListSerializer(ChatMessage.serializer()), messages)
        }
        return JsonObject(data.jsonObject - "messages")
    }

    private companion object {
        const val SCHEMA_VERSION = 2
        const val SUFFIX = ".json"

        /** [Conversations] ids: a letter and a nodeId or groupId in hex, safe as a file name. */
        val CONVERSATION = Regex("[fg][0-9a-f]{16}")

        val json = Json { ignoreUnknownKeys = true }
    }
}
