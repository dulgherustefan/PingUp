package ro.safetyplease.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.app.core.hexToBytes
import ro.safetyplease.app.core.utf8
import ro.safetyplease.app.crypto.StaffCrypto
import ro.safetyplease.app.crypto.StaffPublicKeys
import ro.safetyplease.app.crypto.testCrypto
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MsgStatus
import ro.safetyplease.app.incidents.ReportDraft
import ro.safetyplease.app.mesh.SimNet
import ro.safetyplease.app.mesh.TestClock
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentCategory
import ro.safetyplease.app.protocol.PacketCodec
import ro.safetyplease.app.protocol.PacketType
import ro.safetyplease.app.protocol.QuickCode
import ro.safetyplease.app.protocol.Severity

/** Scenariile de acceptanta, rulate cap-coada pe radio simulat. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent()
        private var seed = 100L
        fun phone(name: String) = TestPhone(name, scope, net, clock, tmp.root, event, seed++)
    }

    private class Chain(val world: World, val a: TestPhone, val b: TestPhone, val c: TestPhone, val d: TestPhone)

    /** A participant, B si C relay, D staff; A ignora C si D, B ignora D. */
    private fun TestScope.chain(): Chain {
        val world = World(this)
        val a = world.phone("A")
        val b = world.phone("B")
        val c = world.phone("C")
        val d = world.phone("D")
        d.staffSecret = world.event.secret
        d.team = "Medical 1"
        d.engine.setRole(staff = true, anchor = false)
        a.ignore(c, d)
        b.ignore(d)
        world.net.start()
        advanceTimeBy(30_000)
        return Chain(world, a, b, c, d)
    }

    private fun medical(description: String = "A leșinat cineva", nickname: String? = null) = ReportDraft(
        IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", 43.9505, 28.6350, description, nickname,
    )

    // --- incidente (pasii 1-4 si 6) ---

    @Test
    fun urgentReportReachesStaffInThreeHopsAndStatusComesBack() = runTest {
        val w = chain()
        val start = testScheduler.currentTime
        assertTrue(w.a.incidents.report(medical()))
        advanceTimeBy(15_000)

        val atStaff = w.d.staffIncidents.single()
        assertEquals(3, atStaff.hops)
        assertEquals(IncidentCategory.MEDICAL, atStaff.category)
        assertEquals(Severity.URGENT, atStaff.severity)
        assertEquals("main-stage", atStaff.zone)
        assertEquals("A leșinat cineva", atStaff.description)
        assertNull("raportul e anonim implicit", atStaff.nickname)
        assertEquals(1, w.d.alerts.size)

        val mine = w.a.myReports.single()
        assertTrue(mine.sent)
        assertEquals("ajuns la staff", AckStatus.RECEIVED, mine.status)
        assertTrue(testScheduler.currentTime - start <= 15_000)

        w.d.incidents.acknowledge(atStaff.incidentId)
        advanceTimeBy(15_000)
        assertEquals(AckStatus.ACKNOWLEDGED, w.a.myReports.single().status)
        assertEquals("Medical 1", w.a.myReports.single().teamName)

        w.d.incidents.resolve(atStaff.incidentId)
        advanceTimeBy(15_000)
        assertEquals(AckStatus.RESOLVED, w.a.myReports.single().status)
        assertEquals(AckStatus.RESOLVED, w.d.staffIncidents.single().status)
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.ACKNOWLEDGED, AckStatus.RESOLVED), w.a.reportUpdates.map { it.status })
    }

    @Test
    fun relaysCarryTheReportButCannotReadIt() = runTest {
        val w = chain()
        val seen = mutableListOf<ByteArray>()
        w.b.radio.tamper = { frame -> frame.also { seen += it } }
        w.a.incidents.report(medical("text secret pentru staff", nickname = "Andrei"))
        advanceTimeBy(10_000)
        assertEquals(1, w.d.staffIncidents.size)
        assertEquals("Andrei", w.d.staffIncidents.single().nickname)
        assertTrue(w.b.staffIncidents.isEmpty() && w.c.staffIncidents.isEmpty())
        val reports = seen.mapNotNull { PacketCodec.decode(it)?.packet }.filter { it.type == PacketType.INCIDENT_REPORT }
        assertTrue(reports.isNotEmpty())
        for (p in reports) {
            assertEquals("fara expeditor", 0L, p.sender)
            assertFalse(String(p.payload, Charsets.ISO_8859_1).contains("secret"))
            assertFalse(String(p.payload, Charsets.ISO_8859_1).contains("Andrei"))
        }
    }

    @Test
    fun reportWaitsInTheMeshWhileStaffIsOffline() = runTest {
        val w = chain()
        w.d.radio.powerOff()
        advanceTimeBy(5_000)
        w.a.incidents.report(medical())
        advanceTimeBy(120_000)
        assertTrue(w.d.staffIncidents.isEmpty())
        assertEquals(AckStatus.NONE, w.a.myReports.single().status)
        w.d.radio.powerOn()
        advanceTimeBy(40_000)
        assertEquals(1, w.d.staffIncidents.size)
        assertEquals(AckStatus.RECEIVED, w.a.myReports.single().status)
    }

    @Test
    fun localRateLimitIsThreeReportsPerTenMinutes() = runTest {
        val w = chain()
        repeat(3) { assertTrue(w.a.incidents.report(medical("r$it"))) }
        assertFalse(w.a.incidents.report(medical("al patrulea")))
        assertTrue(w.a.incidents.rateLimitWaitMs() > 0)
        assertTrue("incidentele de test din demo trec", w.a.incidents.report(medical("demo"), bypassRateLimit = true))
        advanceTimeBy(10 * 60_000L + 1_000)
        assertEquals(0L, w.a.incidents.rateLimitWaitMs())
        assertTrue(w.a.incidents.report(medical("dupa fereastra")))
    }

    @Test
    fun secondStaffPhoneDoesNotRepeatReceivedAndSeesOtherTeamsWork() = runTest {
        val w = chain()
        w.c.staffSecret = w.world.event.secret
        w.c.team = "Medical 2"
        w.a.incidents.report(medical())
        advanceTimeBy(10_000)
        assertEquals(1, w.c.staffIncidents.size)
        assertEquals(1, w.d.staffIncidents.size)
        assertEquals("o singura confirmare de primire", 1, w.a.reportUpdates.size)
        assertEquals("Medical 2", w.d.staffIncidents.single().teamName)

        w.c.incidents.acknowledge(w.c.staffIncidents.single().incidentId)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.ACKNOWLEDGED, w.d.staffIncidents.single().status)
        assertEquals("ca D sa nu plece la acelasi incident", "Medical 2", w.d.staffIncidents.single().teamName)
    }

    @Test
    fun ackSignedWithAnotherKeyNeverReachesTheReporter() = runTest {
        val w = chain()
        w.d.radio.powerOff()
        advanceTimeBy(2_000)
        w.a.incidents.report(medical())
        advanceTimeBy(5_000)
        val rogue = TestEvent()
        val id = w.a.myReports.single().incidentId
        val forged = rogue.staffCrypto.signAck(
            id.hexToBytes(),
            AckStatus.RESOLVED, 1L, "Fals", rogue.secret,
        )
        w.c.engine.publishAck(forged)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.NONE, w.a.myReports.single().status)
        assertTrue(w.a.reportUpdates.isEmpty())
    }

    @Test
    fun phoneThatBecomesStaffLaterOpensCachedReports() = runTest {
        val w = chain()
        w.d.staffSecret = null
        w.a.incidents.report(medical())
        advanceTimeBy(10_000)
        assertTrue(w.d.staffIncidents.isEmpty())
        w.d.staffSecret = w.world.event.secret
        w.d.incidents.reprocessCached()
        advanceTimeBy(10_000)
        assertEquals(3, w.d.staffIncidents.single().hops)
        assertEquals(AckStatus.RECEIVED, w.a.myReports.single().status)
    }

    @Test
    fun staffPhoneSeesItsOwnReport() = runTest {
        val w = chain()
        w.d.incidents.report(medical())
        advanceTimeBy(1_000)
        assertEquals(0, w.d.staffIncidents.single().hops)
        assertEquals(AckStatus.RECEIVED, w.d.myReports.single().status)
        assertTrue("fara alerta pentru propriul raport", w.d.alerts.isEmpty())
    }

    @Test
    fun wrongStaffKeyCannotOpenReports() = runTest {
        val w = chain()
        val other = TestEvent()
        w.d.staffSecret = other.secret
        w.a.incidents.report(medical())
        advanceTimeBy(10_000)
        assertTrue(w.d.staffIncidents.isEmpty())
        assertEquals(AckStatus.NONE, w.a.myReports.single().status)
    }

    // --- chat (pasul 5) ---

    @Test
    fun whereAreYouAndZoneReplyRoundTripUnderTenSeconds() = runTest {
        val w = chain()
        w.a.befriend(w.c)
        advanceTimeBy(5_000)
        val start = testScheduler.currentTime
        val convAtA = Conversations.friend(w.c.nodeId)
        val convAtC = Conversations.friend(w.a.nodeId)

        w.a.chat.sendQuick(convAtA, QuickCode.WHERE_ARE_YOU)
        advanceTimeBy(3_000)
        val question = w.c.messages.single { !it.fromMe }
        assertEquals(MsgKind.QUICK, question.kind)
        assertEquals(QuickCode.WHERE_ARE_YOU, question.quickCode)
        assertEquals(2, question.hops)
        assertEquals(convAtC, question.conversation)

        w.c.chat.sendZone(convAtC, "food", 43.9494, 28.6329)
        advanceTimeBy(3_000)
        val answer = w.a.messages.single { !it.fromMe }
        assertEquals(MsgKind.ZONE, answer.kind)
        assertEquals("food", answer.zone)
        assertEquals(43.9494, answer.lat!!, 1e-6)
        assertEquals(28.6329, answer.lon!!, 1e-6)
        assertTrue(testScheduler.currentTime - start <= 10_000)

        assertEquals(MsgStatus.DELIVERED, w.a.messages.single { it.fromMe }.status)
        assertEquals(MsgStatus.DELIVERED, w.c.messages.single { it.fromMe }.status)
        assertTrue(w.a.chatStore.value.outbox.isEmpty() && w.c.chatStore.value.outbox.isEmpty())
        assertTrue("relay-ul nu vede nimic", w.b.messages.isEmpty())
    }

    @Test
    fun relayNeverSeesPlaintext() = runTest {
        val w = chain()
        w.a.befriend(w.c)
        advanceTimeBy(3_000)
        val seen = mutableListOf<ByteArray>()
        w.b.radio.tamper = { frame -> frame.also { seen += it } }
        w.a.chat.sendText(Conversations.friend(w.c.nodeId), "ne vedem la bar la zece")
        advanceTimeBy(3_000)
        assertEquals("ne vedem la bar la zece", w.c.messages.single().text)
        val privates = seen.mapNotNull { PacketCodec.decode(it)?.packet }.filter { it.type == PacketType.PRIVATE }
        assertTrue(privates.isNotEmpty())
        val needle = "la bar".utf8()
        for (p in privates) {
            assertTrue(p.encrypted)
            assertFalse(p.payload.toList().windowed(needle.size).any { it == needle.toList() })
        }
    }

    @Test
    fun messagesFromNonFriendsAreDropped() = runTest {
        val w = chain()
        w.d.chat.addFriend(w.c.chat.myCard())
        advanceTimeBy(2_000)
        w.d.chat.sendText(Conversations.friend(w.c.nodeId), "salut")
        advanceTimeBy(20_000)
        assertTrue(w.c.messages.isEmpty())
        assertEquals(MsgStatus.SENT, w.d.messages.single().status)
    }

    @Test
    fun presenceComesFromPongAndIncomingPackets() = runTest {
        val w = chain()
        w.a.befriend(w.c)
        advanceTimeBy(5_000)
        val friendAtA = w.a.friends.value.single()
        assertTrue(friendAtA.lastSeenAt > 0)
        assertEquals(2, friendAtA.lastHops)
    }

    @Test
    fun messageWrittenWithoutAnyLinkLeavesWhenAPeerAppears() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val c = world.phone("C")
        a.befriend(c)
        a.chat.sendText(Conversations.friend(c.nodeId), "ajung in 5 minute")
        advanceTimeBy(2_000)
        assertEquals(MsgStatus.QUEUED, a.messages.single().status)
        world.net.start()
        advanceTimeBy(40_000)
        assertEquals("ajung in 5 minute", c.messages.single().text)
        assertEquals(MsgStatus.DELIVERED, a.messages.single().status)
    }

    @Test
    fun outboxKeepsRetryingUntilRecipientComesBack() = runTest {
        val w = chain()
        w.a.befriend(w.c)
        advanceTimeBy(3_000)
        w.c.radio.powerOff()
        advanceTimeBy(2_000)
        w.a.chat.sendText(Conversations.friend(w.c.nodeId), "mai esti?")
        advanceTimeBy(60_000)
        assertEquals(MsgStatus.SENT, w.a.messages.single().status)
        assertEquals(1, w.a.chatStore.value.outbox.size)
        w.c.radio.powerOn()
        advanceTimeBy(5 * 60_000L)
        assertEquals(1, w.c.messages.size)
        assertEquals(MsgStatus.DELIVERED, w.a.messages.single().status)
        assertTrue(w.a.chatStore.value.outbox.isEmpty())
    }

    @Test
    fun lostDeliveryReceiptCausesRetryButNoDuplicateMessage() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val c = world.phone("C")
        a.befriend(c)
        world.net.start()
        advanceTimeBy(10_000)
        var dropped = 0
        c.radio.tamper = { frame ->
            if (dropped == 0 && frame[1].toInt() == PacketType.PRIVATE) {
                dropped++
                null
            } else frame
        }
        a.chat.sendText(Conversations.friend(c.nodeId), "o singura data")
        advanceTimeBy(5_000)
        assertEquals(1, dropped)
        assertEquals(MsgStatus.SENT, a.messages.single().status)
        advanceTimeBy(30_000)
        assertEquals(MsgStatus.DELIVERED, a.messages.single().status)
        assertEquals(1, c.messages.size)
        assertEquals(1, c.incoming.size)
    }

    @Test
    fun undeliveredMessageFailsAfterTwentyFourHours() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val c = world.phone("C")
        a.befriend(c)
        a.chat.sendText(Conversations.friend(c.nodeId), "nu ajunge")
        advanceTimeBy(24 * 60 * 60_000L + 60_000)
        assertEquals(MsgStatus.FAILED, a.messages.single().status)
        assertTrue(a.chatStore.value.outbox.isEmpty())
    }

    @Test
    fun resendAfterFailureSendsANewCopyInPlaceOfTheOldOne() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val c = world.phone("C")
        a.befriend(c)
        a.chat.sendText(Conversations.friend(c.nodeId), "nu ajunge")
        advanceTimeBy(24 * 60 * 60_000L + 60_000)
        val failed = a.messages.single()
        assertEquals(MsgStatus.FAILED, failed.status)
        world.net.start()
        advanceTimeBy(10_000)
        a.chat.resend(failed)
        advanceTimeBy(40_000)
        val again = a.messages.single()
        assertTrue(again.msgId != failed.msgId)
        assertEquals(MsgStatus.DELIVERED, again.status)
        assertEquals("nu ajunge", c.messages.single().text)
    }

    @Test
    fun resendRetriesAPendingMessageAtOnce() = runTest {
        val w = chain()
        w.a.befriend(w.c)
        advanceTimeBy(3_000)
        w.c.radio.powerOff()
        advanceTimeBy(2_000)
        w.a.chat.sendText(Conversations.friend(w.c.nodeId), "mai esti?")
        // la un minut de la trimitere urmatoarea incercare programata e inca departe
        advanceTimeBy(60_000)
        val attempts = w.a.chatStore.value.outbox.single().attempts
        w.a.chat.resend(w.a.messages.single())
        advanceTimeBy(1_000)
        assertEquals(attempts + 1, w.a.chatStore.value.outbox.single().attempts)
        assertEquals(1, w.a.messages.size)
    }

    @Test
    fun deletedMessageIsNotSentAnymore() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val c = world.phone("C")
        a.befriend(c)
        a.chat.sendText(Conversations.friend(c.nodeId), "m-am razgandit")
        advanceTimeBy(2_000)
        a.chat.deleteMessage(a.messages.single())
        assertTrue(a.messages.isEmpty())
        assertTrue(a.chatStore.value.outbox.isEmpty())
        world.net.start()
        advanceTimeBy(40_000)
        assertTrue(c.messages.isEmpty())
    }

    // --- grupuri ---

    @Test
    fun groupMessageFansOutToEveryMemberEvenIfTheyAreNotFriends() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val b = world.phone("B")
        val c = world.phone("C")
        a.befriend(b)
        a.befriend(c)
        world.net.start()
        advanceTimeBy(30_000)

        a.chat.createGroup("Gașca", listOf(b.nodeId, c.nodeId))
        advanceTimeBy(5_000)
        val group = a.groups.value.single()
        assertEquals(3, group.members.size)
        assertEquals("Gașca", b.groups.value.single().name)
        assertEquals(setOf(a.nodeId, b.nodeId, c.nodeId), c.groups.value.single().members.map { it.nodeId }.toSet())
        val conversation = Conversations.group(group.id)

        a.chat.sendText(conversation, "unde ne vedem?")
        advanceTimeBy(5_000)
        assertEquals("unde ne vedem?", b.messages.last().text)
        assertEquals("unde ne vedem?", c.messages.last().text)
        val sent = a.messages.single { it.fromMe }
        assertEquals(2, sent.delivered)
        assertEquals(MsgStatus.DELIVERED, sent.status)

        c.chat.sendText(conversation, "la intrare")
        advanceTimeBy(5_000)
        assertEquals("la intrare", a.messages.last().text)
        assertEquals("B primeste de la C desi nu sunt prieteni", "la intrare", b.messages.last().text)
        assertEquals(c.nodeId, b.messages.last().senderId)
    }

    @Test
    fun outsiderCannotPostIntoGroup() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val b = world.phone("B")
        val x = world.phone("X")
        a.befriend(b)
        a.befriend(x)
        world.net.start()
        advanceTimeBy(30_000)
        a.chat.createGroup("Doi", listOf(b.nodeId))
        advanceTimeBy(5_000)
        val group = a.groups.value.single()
        // X stie id-ul grupului si isi construieste local un grup identic
        x.groups.update { listOf(group) }
        x.chat.sendText(Conversations.group(group.id), "intrus")
        advanceTimeBy(5_000)
        assertTrue(a.messages.none { it.text == "intrus" })
        assertTrue(b.messages.none { it.text == "intrus" })
    }

    @Test
    fun staffKeysFromAnotherEventAreRejected() {
        val event = TestEvent()
        val other = StaffCrypto(testCrypto, StaffPublicKeys(testCrypto.boxKeyPair().publicKey, testCrypto.signKeyPair().publicKey))
        assertNull(other.secretFromSeeds(testCrypto.random(32), testCrypto.random(32)))
        assertFalse(other.verifyAck(event.staffCrypto.signAck(ByteArray(16), AckStatus.RECEIVED, 1, "x", event.secret).encode()))
    }
}
