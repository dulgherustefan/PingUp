package ro.safetyplease.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.delete
import ro.safetyplease.core.exists
import ro.safetyplease.core.mkdirs
import ro.safetyplease.core.readText
import ro.safetyplease.core.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val ana = Conversations.friend(0x1111L)
    private val bob = Conversations.friend(0x2222L)

    private fun message(id: Long, conversation: String, at: Long) = ChatMessage(
        msgId = id, conversation = conversation, senderId = 7L, fromMe = true, kind = MsgKind.TEXT, text = "m$id",
        timeMs = at, status = MsgStatus.SENT,
    )

    private val outbox = listOf(OutboxItem(3L, 0x2222L, "0102", 30L))

    private fun file(conversation: String) = tmp.root / "chat/$conversation.json"

    private fun json(file: Path) = Json.parseToJsonElement(file.readText()).jsonObject

    private fun saved(file: Path): List<ChatMessage> =
        Json.decodeFromJsonElement(ListSerializer(ChatMessage.serializer()), json(file).getValue("data"))

    @Test
    fun eachConversationHasItsOwnFileAndTheOutboxStaysInChatJson() = runTest {
        val store = ChatStore(tmp.root, backgroundScope)
        val messages = listOf(message(1, ana, 10), message(2, bob, 20), message(3, ana, 30))
        store.update { ChatData(messages, outbox) }
        store.flush()
        assertEquals(listOf(1L, 3L), saved(file(ana)).map { it.msgId })
        assertEquals(listOf(2L), saved(file(bob)).map { it.msgId })
        val chat = json(tmp.root / "chat.json")
        assertEquals(2, chat.getValue("schemaVersion").jsonPrimitive.int)
        assertFalse("messages" in chat.getValue("data").jsonObject)

        val again = ChatStore(tmp.root, backgroundScope)
        assertEquals(messages, again.value.messages)
        assertEquals(outbox, again.value.outbox)
    }

    @Test
    fun aNewMessageRewritesOnlyItsOwnConversation() = runTest {
        val store = ChatStore(tmp.root, backgroundScope)
        store.update { ChatData(listOf(message(1, ana, 10), message(2, bob, 20))) }
        advanceTimeBy(1_000)
        store.update { it.copy(messages = it.messages + message(3, ana, 30)) }
        advanceTimeBy(1_000)
        assertEquals(listOf(1L, 3L), saved(file(ana)).map { it.msgId })
        assertTrue(file(ana).withSuffix(".bak").exists(), "ana's file was written again")
        assertFalse(file(bob).withSuffix(".bak").exists(), "bob's wasn't")
        assertFalse((tmp.root / "chat.json").exists(), "nor chat.json: the outbox didn't change")
    }

    @Test
    fun aConversationLeftEmptyLosesItsFile() = runTest {
        val store = ChatStore(tmp.root, backgroundScope)
        store.update { ChatData(listOf(message(1, ana, 10), message(2, bob, 20))) }
        store.flush()
        store.update { data -> data.copy(messages = data.messages.filterNot { it.conversation == bob }) }
        store.flush()
        assertFalse(file(bob).exists())
        assertEquals(listOf(message(1, ana, 10)), ChatStore(tmp.root, backgroundScope).value.messages)
    }

    /** chat.json as version 1 wrote it, messages and outbox together. */
    private fun writeVersion1(messages: List<ChatMessage>): String {
        val data = Json { encodeDefaults = true }.encodeToJsonElement(ChatData.serializer(), ChatData(messages, outbox))
        return """{"schemaVersion":1,"data":$data}""".also { (tmp.root / "chat.json").writeText(it) }
    }

    @Test
    fun theSingleChatJsonOfVersion1MovesToConversationFilesAndStaysInTheBackup() = runTest {
        val messages = listOf(message(1, ana, 10), message(2, bob, 20), message(3, ana, 30))
        val old = writeVersion1(messages)
        val store = ChatStore(tmp.root, backgroundScope)
        assertEquals(messages, store.value.messages)
        assertEquals(outbox, store.value.outbox)
        assertTrue(file(ana).exists() && file(bob).exists())
        val chat = json(tmp.root / "chat.json")
        assertEquals(2, chat.getValue("schemaVersion").jsonPrimitive.int)
        assertFalse("messages" in chat.getValue("data").jsonObject)
        assertEquals(old, (tmp.root / "chat.json.bak").readText(), "the old file stays as the backup")
        assertEquals(messages, ChatStore(tmp.root, backgroundScope).value.messages)
    }

    @Test
    fun aMoveCutShortIsMadeAgainFromTheSameFile() = runTest {
        val messages = listOf(message(1, ana, 10), message(2, bob, 20), message(3, ana, 30))
        writeVersion1(messages)
        // killed after writing part of one conversation, before chat.json became version 2
        file(ana).parent!!.mkdirs()
        file(ana).writeText("""{"schemaVersion":1,"data":[]}""")
        val store = ChatStore(tmp.root, backgroundScope)
        assertEquals(messages, store.value.messages)
        assertEquals(listOf(1L, 3L), ChatStore(tmp.root, backgroundScope).value.messages.filter { it.conversation == ana }.map { it.msgId })
    }

    @Test
    fun anUnreadableChatJsonIsLeftAsItIs() = runTest {
        (tmp.root / "chat.json").writeText("{ nu e json")
        val store = ChatStore(tmp.root, backgroundScope)
        assertTrue(store.value.messages.isEmpty() && store.value.outbox.isEmpty())
        store.update { it.copy(outbox = outbox) }
        store.flush()
        assertEquals("{ nu e json", (tmp.root / "chat.json").readText())
    }
}
