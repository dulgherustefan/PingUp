package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.crypto.ReservedNames
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.incidents.Clustering
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.ConnectionPolicy
import ro.safetyplease.core.mesh.Position
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.InnerCodec
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.QuickCode
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.utf8
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end acceptance scenarios on the simulated radio. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent()
        private var seed = 100L
        fun phone(name: String) = TestPhone(name, scope, net, clock, tmp.root, event, seed++)
    }

    private class Chain(val world: World, val a: TestPhone, val b: TestPhone, val c: TestPhone, val d: TestPhone)

    /** A participant, B and C relays, D staff; A and C, A and D, B and D ignore each other. */
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
        c.ignore(a)
        d.ignore(a, b)
        world.net.start()
        advanceTimeBy(30_000)
        return Chain(world, a, b, c, d)
    }

    private fun medical(description: String = "A leșinat cineva", nickname: String? = null) = ReportDraft(
        IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", 43.9505, 28.6350, description, nickname,
    )

    /** A report whose cancel closes it by itself (see Priority.cancelNeedsReview). */
    private fun crowd(description: String = "Îmbulzeală la bar") = ReportDraft(
        IncidentCategory.CROWD, Severity.MEDIUM, "main-stage", 43.9505, 28.6350, description, null,
    )

    // --- incidents (steps 1-4 and 6) ---

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
        assertNull(atStaff.nickname, "reports are anonymous by default")
        assertEquals(1, w.d.alerts.size)

        val mine = w.a.myReports.single()
        assertTrue(mine.sent)
        assertEquals(AckStatus.RECEIVED, mine.status, "reached staff")
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
            assertEquals(0L, p.sender, "no sender")
            assertFalse(latin1(p.payload).contains("secret"))
            assertFalse(latin1(p.payload).contains("Andrei"))
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

    /**
     * Four phones that claim to be anchors sit closest to the reporter and drop everything. Anchors come in sessions,
     * a phone has one at a time outside its limits, and each ends once the exchange is over: the fakes never hold the
     * reporter's links, and the report reaches staff through the honest relay. The reporter doesn't advertise, so only
     * it chooses its links.
     */
    @Test
    fun fakeAnchorsThatDropEverythingCannotHoldEveryLink() = runTest {
        val world = World(this)
        world.net.range = 15.0
        val reporter = TestPhone("R", this, world.net, world.clock, tmp.root, world.event, 900, canAdvertise = false)
        reporter.radio.position = Position(0.0, 0.0)
        val fakes = List(4) { i -> world.phone("F$i") }
        for ((i, fake) in fakes.withIndex()) {
            fake.engine.setRole(staff = false, anchor = true)
            fake.radio.position = Position(2.0 * (i + 1), 1.0)
            fake.radio.tamper = { frame -> frame.takeIf { it[1].toInt() == PacketType.HELLO } }
        }
        val relay = world.phone("X").apply { radio.position = Position(12.0, 0.0) }
        val staff = world.phone("S").apply { radio.position = Position(24.0, 0.0) }
        staff.staffSecret = world.event.secret
        for (fake in fakes) for (other in fakes + relay + staff) if (fake !== other) world.net.outOfRange += setOf(fake.name, other.name)
        world.net.start()
        advanceTimeBy(50_000)
        assertTrue(relay.nodeId in reporter.engine.state.value.links.map { it.peerId })

        assertTrue(reporter.incidents.report(medical()))
        val start = testScheduler.currentTime
        var mostFakes = 0
        while (staff.staffIncidents.isEmpty() && testScheduler.currentTime - start < 10 * 60_000L) {
            advanceTimeBy(1_000)
            mostFakes = maxOf(mostFakes, reporter.engine.state.value.links.count { link -> fakes.any { it.nodeId == link.peerId } })
        }
        val tookMs = testScheduler.currentTime - start
        assertEquals(1, staff.staffIncidents.size)
        assertTrue(mostFakes <= 1, "one session at a time")
        assertTrue(relay.nodeId in reporter.engine.state.value.links.map { it.peerId })
        assertTrue(tookMs <= 3 * 60_000L, "$tookMs ms")
    }

    /**
     * One phone that claims to be an anchor and drops everything gets a session like any anchor. The session is outside
     * the reporter's limits, so its slots stay with the honest phones, and it ends a few seconds after the exchange.
     */
    @Test
    fun aFakeAnchorGetsOnlyAShortSession() = runTest {
        val world = World(this)
        world.net.range = 15.0
        val reporter = TestPhone("R", this, world.net, world.clock, tmp.root, world.event, 900, canAdvertise = false)
        reporter.radio.position = Position(0.0, 0.0)
        val fake = world.phone("F").apply {
            engine.setRole(staff = false, anchor = true)
            radio.position = Position(1.0, 1.0)
            radio.tamper = { frame -> frame.takeIf { it[1].toInt() == PacketType.HELLO } }
        }
        val honest = List(5) { i -> world.phone("H$i").apply { radio.position = Position(3.0 + 2 * i, -1.0) } }
        val others = honest + fake
        for (x in others) for (y in others) if (x !== y) world.net.outOfRange += setOf(x.name, y.name)
        world.net.start()
        var linkedMs = 0L
        var longestMs = 0L
        repeat(6 * 60) {
            advanceTimeBy(1_000)
            val peers = reporter.engine.state.value.links.map { it.peerId }
            linkedMs = if (fake.nodeId in peers) linkedMs + 1_000 else 0
            longestMs = maxOf(longestMs, linkedMs)
            if (it >= 50) assertEquals(4, peers.count { id -> honest.any { h -> h.nodeId == id } }, "the slots stay with the honest phones")
        }
        assertTrue(longestMs > 0, "it had a session")
        assertTrue(longestMs <= ConnectionPolicy.SESSION_IDLE_MS + 3_000, "$longestMs ms")
    }

    @Test
    fun localRateLimitIsThreeReportsPerTenMinutes() = runTest {
        val w = chain()
        repeat(3) { assertTrue(w.a.incidents.report(medical("r$it"))) }
        assertFalse(w.a.incidents.report(medical("al patrulea")))
        assertTrue(w.a.incidents.rateLimitWaitMs() > 0)
        assertTrue(w.a.incidents.report(medical("demo"), bypassRateLimit = true), "demo test incidents bypass the limit")
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
        assertEquals(1, w.a.reportUpdates.size, "a single receipt confirmation")
        assertEquals("Medical 2", w.d.staffIncidents.single().teamName)

        w.c.incidents.acknowledge(w.c.staffIncidents.single().incidentId)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.ACKNOWLEDGED, w.d.staffIncidents.single().status)
        assertEquals("Medical 2", w.d.staffIncidents.single().teamName, "so D doesn't go to the same incident")
    }

    @Test
    fun twoMedicalCasesInTheSameZoneAMinuteApartAreTakenOneByOne() = runTest {
        val w = chain()
        assertTrue(w.a.incidents.report(medical("A leșinat cineva lângă bar")))
        advanceTimeBy(60_000)
        assertTrue(w.b.incidents.report(medical("Cineva nu mai respiră")))
        advanceTimeBy(15_000)

        val atStaff = w.d.staffIncidents
        assertEquals(2, atStaff.size)
        val hint = Clustering.suggestions(atStaff).getValue(atStaff.first().incidentId)
        assertEquals(2, hint.count, "staff is told about the other report")
        assertFalse(hint.sameEvent)

        val first = w.a.myReports.single().incidentId
        w.d.incidents.acknowledge(first)
        advanceTimeBy(15_000)
        assertEquals(AckStatus.ACKNOWLEDGED, w.a.myReports.single().status)
        assertEquals(AckStatus.RECEIVED, w.b.myReports.single().status, "the second patient isn't shown as taken")
        assertEquals(AckStatus.RECEIVED, w.d.staffIncidents.single { it.incidentId != first }.status)
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
        assertTrue(w.d.alerts.isEmpty(), "no alert for our own report")
    }

    @Test
    fun pendingReportReachesNeitherTheAdvertisingNorHello() = runTest {
        val w = chain()
        w.d.radio.powerOff()
        advanceTimeBy(2_000)
        w.a.incidents.report(medical())
        advanceTimeBy(10 * 60_000L)
        assertTrue(IncidentManager.isPending(w.a.myReports.single(), w.world.clock.wallMs()), "still waiting for staff")
        assertTrue(w.a.radio.advertised.isNotEmpty())
        assertTrue(w.a.radio.advertised.none { it and NodeFlags.PENDING_INCIDENT != 0 })
        val fromA = w.b.engine.state.value.links.single { it.peerId == w.a.nodeId }
        assertEquals(0, fromA.flags and NodeFlags.PENDING_INCIDENT)
    }

    /** Stands in for the app being killed and started again: a new phone on the same incident store. */
    private fun TestScope.restart(world: World, phone: TestPhone, name: String): TestPhone {
        phone.incidentStore.flush()
        phone.radio.powerOff()
        (tmp.root / "${phone.name}-incidents.json").copyTo(tmp.root / "$name-incidents.json")
        return world.phone(name).also { runCurrent() }
    }

    @Test
    fun pendingReportIsPublishedAgainAfterARestart() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val d = world.phone("D")
        d.staffSecret = world.event.secret
        d.engine.setRole(staff = true, anchor = false)
        a.incidents.report(medical())
        advanceTimeBy(2_000)
        val id = a.myReports.single().incidentId
        assertTrue(a.myReports.single().sealed.isNotEmpty(), "kept while pending")

        // killed before any neighbor showed up: the report lived only in the mesh cache
        val again = restart(world, a, "A2")
        assertEquals(1, again.engine.state.value.cachedReports)
        world.net.start()
        advanceTimeBy(30_000)
        assertEquals(id, d.staffIncidents.single().incidentId)
        assertEquals(AckStatus.RECEIVED, again.myReports.single().status)
        assertEquals("", again.myReports.single().sealed, "staff has it, the copy goes")
    }

    @Test
    fun expiredOrWithdrawnReportsAreNotPublishedAgain() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val d = world.phone("D")
        d.staffSecret = world.event.secret
        a.incidents.report(medical("vechi"))
        advanceTimeBy(IncidentManager.PENDING_MS + 60_000L)
        a.incidents.report(crowd("retras"))
        a.incidents.cancel(a.myReports.last().incidentId)
        advanceTimeBy(2_000)

        assertEquals("", a.myReports.last().sealed, "withdrawn, the copy goes")

        val again = restart(world, a, "A2")
        assertEquals(0, again.engine.state.value.cachedReports)
        assertTrue(again.myReports.all { it.sealed.isEmpty() }, "expired, the copy goes")
        world.net.start()
        advanceTimeBy(30_000)
        assertTrue(d.staffIncidents.isEmpty())
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

    // --- cancel and delete ---

    @Test
    fun reporterCancelsAndBothSidesSeeItCancelled() = runTest {
        val w = chain()
        val seen = mutableListOf<ByteArray>()
        w.b.radio.tamper = { frame -> frame.also { seen += it } }
        w.a.incidents.report(crowd())
        advanceTimeBy(10_000)
        val id = w.a.myReports.single().incidentId
        assertEquals(AckStatus.RECEIVED, w.d.staffIncidents.single().status)

        w.a.incidents.cancel(id)
        assertTrue(w.a.myReports.single().cancelled, "the request shows immediately")
        advanceTimeBy(10_000)

        val atStaff = w.d.staffIncidents.single()
        assertEquals(AckStatus.CANCELLED, atStaff.status)
        assertEquals("Medical 1", atStaff.teamName)
        assertEquals(1, w.d.alerts.size)
        assertEquals(listOf(id), w.d.clearedAlerts, "the staff notification is cleared")
        val mine = w.a.myReports.single()
        assertEquals(AckStatus.CANCELLED, mine.status)
        assertTrue(mine.cancelled)
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.CANCELLED), w.a.reportUpdates.map { it.status })
        assertEquals(AckStatus.CANCELLED, w.c.engine.ackStatus(id.hexToBytes()), "the ACK travels like any other")

        val cancels = seen.mapNotNull { PacketCodec.decode(it)?.packet }.filter { it.type == PacketType.INCIDENT_CANCEL }
        assertTrue(cancels.isNotEmpty())
        assertTrue(cancels.all { it.sender == 0L }, "no sender")
    }

    @Test
    fun cancelWithAWrongTokenIsIgnored() = runTest {
        val w = chain()
        w.a.incidents.report(crowd())
        advanceTimeBy(10_000)
        val id = w.a.myReports.single().incidentId
        w.b.engine.publishCancel(id.hexToBytes(), testCrypto.random(IncidentCancel.TOKEN_SIZE))
        advanceTimeBy(10_000)
        assertEquals(AckStatus.RECEIVED, w.d.staffIncidents.single().status)
        assertEquals(AckStatus.RECEIVED, w.a.myReports.single().status)
        assertTrue(w.d.clearedAlerts.isEmpty())

        w.a.incidents.cancel(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.CANCELLED, w.d.staffIncidents.single().status)
    }

    @Test
    fun cancelThatOvertakesTheReportIsAppliedWhenTheReportArrives() = runTest {
        val w = chain()
        var holdReports = true
        var cancelsToD = 0
        w.c.radio.tamper = { frame ->
            when (frame[1].toInt()) {
                PacketType.INCIDENT_REPORT -> if (holdReports) null else frame
                PacketType.INCIDENT_CANCEL -> frame.also { cancelsToD++ }
                else -> frame
            }
        }
        w.a.incidents.report(crowd())
        advanceTimeBy(5_000)
        w.a.incidents.cancel(w.a.myReports.single().incidentId)
        advanceTimeBy(5_000)
        assertTrue(cancelsToD > 0 && w.d.staffIncidents.isEmpty(), "the cancel got past C, the report didn't")

        holdReports = false
        advanceTimeBy(70_000)
        assertEquals(AckStatus.CANCELLED, w.d.staffIncidents.single().status)
        assertTrue(w.d.alerts.isEmpty(), "no alert for an already cancelled report")
        assertEquals(listOf(AckStatus.CANCELLED), w.a.reportUpdates.map { it.status }, "no RECEIVED before")
    }

    @Test
    fun staffThatFindsAnAlreadyCancelledReportRaisesNoAlert() = runTest {
        val w = chain()
        w.a.incidents.report(crowd())
        advanceTimeBy(10_000)
        w.a.incidents.cancel(w.a.myReports.single().incidentId)
        advanceTimeBy(10_000)
        w.c.staffSecret = w.world.event.secret
        w.c.team = "Medical 2"
        w.c.incidents.reprocessCached()
        advanceTimeBy(5_000)
        assertEquals(AckStatus.CANCELLED, w.c.staffIncidents.single().status)
        assertTrue(w.c.alerts.isEmpty())
    }

    @Test
    fun staffPhoneCancelsItsOwnReport() = runTest {
        val w = chain()
        w.d.incidents.report(crowd())
        advanceTimeBy(1_000)
        w.d.incidents.cancel(w.d.myReports.single().incidentId)
        advanceTimeBy(1_000)
        assertEquals(AckStatus.CANCELLED, w.d.staffIncidents.single().status)
        assertEquals(AckStatus.CANCELLED, w.d.myReports.single().status)
    }

    @Test
    fun resolvedReportCannotBeCancelled() = runTest {
        val w = chain()
        w.a.incidents.report(medical())
        advanceTimeBy(10_000)
        val id = w.a.myReports.single().incidentId
        w.d.incidents.resolve(id)
        advanceTimeBy(10_000)
        w.a.incidents.cancel(id)
        advanceTimeBy(10_000)
        assertFalse(w.a.myReports.single().cancelled)
        assertEquals(AckStatus.RESOLVED, w.d.staffIncidents.single().status)
    }

    @Test
    fun cancelIsRepeatedUntilStaffConfirmsItThenStops() = runTest {
        val w = chain()
        w.d.radio.powerOff()
        advanceTimeBy(2_000)
        var cancelsSent = 0
        w.a.radio.tamper = { frame -> frame.also { if (it[1].toInt() == PacketType.INCIDENT_CANCEL) cancelsSent++ } }
        w.a.incidents.report(crowd())
        advanceTimeBy(5_000)
        w.a.incidents.cancel(w.a.myReports.single().incidentId)
        advanceTimeBy(1_000)
        assertFalse(IncidentManager.isPending(w.a.myReports.single(), w.world.clock.wallMs()), "a withdrawn report is no longer pending")

        advanceTimeBy(3 * 60_000L)
        assertTrue(cancelsSent >= 3, "resent while staff is missing: $cancelsSent")
        w.d.radio.powerOn()
        advanceTimeBy(3 * 60_000L)
        assertEquals(AckStatus.CANCELLED, w.a.myReports.single().status)
        assertEquals(AckStatus.CANCELLED, w.d.staffIncidents.single().status)
        val confirmedAt = cancelsSent
        advanceTimeBy(10 * 60_000L)
        assertEquals(confirmedAt, cancelsSent, "nothing is sent after confirmation")
    }

    @Test
    fun unconfirmedCancelGivesUpAfterThirtyMinutes() = runTest {
        val w = chain()
        w.d.radio.powerOff()
        advanceTimeBy(2_000)
        var cancelsSent = 0
        w.a.radio.tamper = { frame -> frame.also { if (it[1].toInt() == PacketType.INCIDENT_CANCEL) cancelsSent++ } }
        w.a.incidents.report(medical())
        advanceTimeBy(5_000)
        w.a.incidents.cancel(w.a.myReports.single().incidentId)
        advanceTimeBy(IncidentManager.CANCEL_RETRY_MS + 2 * 60_000L)
        val total = cancelsSent
        assertTrue(total > 20)
        advanceTimeBy(10 * 60_000L)
        assertEquals(total, cancelsSent)
        assertTrue(w.a.myReports.single().cancelled)
        assertEquals(AckStatus.NONE, w.a.myReports.single().status)
    }

    @Test
    fun deletingAnOpenReportCancelsItFirst() = runTest {
        val w = chain()
        w.a.incidents.report(crowd())
        advanceTimeBy(10_000)
        w.a.incidents.delete(w.a.myReports.single().incidentId)
        assertTrue(w.a.myReports.isEmpty())
        advanceTimeBy(10_000)
        assertEquals(AckStatus.CANCELLED, w.d.staffIncidents.single().status)
        assertTrue(w.a.myReports.isEmpty())
    }

    @Test
    fun deletedReportsStillCountForTheRateLimit() = runTest {
        val w = chain()
        repeat(3) { w.a.incidents.report(medical("r$it")) }
        w.a.myReports.map { it.incidentId }.forEach { w.a.incidents.delete(it) }
        assertTrue(w.a.myReports.isEmpty())
        assertFalse(w.a.incidents.report(medical("al patrulea")))
        advanceTimeBy(10 * 60_000L + 1_000)
        assertTrue(w.a.incidents.report(medical("dupa fereastra")))
    }

    @Test
    fun dismissedIncidentLeavesOnlyThisStaffListAndDoesNotComeBack() = runTest {
        val w = chain()
        w.a.incidents.report(medical())
        advanceTimeBy(10_000)
        val id = w.d.staffIncidents.single().incidentId
        w.d.incidents.dismiss(id)
        assertTrue(w.d.staffIncidents.isEmpty())
        assertEquals(listOf(id), w.d.clearedAlerts)

        w.d.incidents.reprocessCached()
        advanceTimeBy(70_000)
        assertTrue(w.d.staffIncidents.isEmpty())
        assertEquals(1, w.d.alerts.size)
        assertEquals(AckStatus.RECEIVED, w.a.myReports.single().status, "nothing goes out on the network")
    }

    // --- chat (step 5) ---

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
        assertTrue(w.b.messages.isEmpty(), "the relay sees nothing")
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
    fun scanningOnceMakesBothFriends() = runTest {
        val w = chain()
        w.a.chat.addFriend(w.c.chat.myCard())
        advanceTimeBy(5_000)
        val atC = w.c.friends.value.single()
        assertEquals(w.a.nodeId, atC.nodeId)
        assertEquals("A", atC.nickname)
        val notice = w.c.messages.single()
        assertEquals(MsgKind.SYSTEM, notice.kind)
        assertEquals(Conversations.friend(w.a.nodeId), notice.conversation)
        assertEquals(listOf(notice.msgId), w.c.incoming.map { it.msgId })
        assertTrue(w.a.chatStore.value.outbox.isEmpty(), "the intro was confirmed")

        w.a.chat.sendText(Conversations.friend(w.c.nodeId), "salut")
        advanceTimeBy(5_000)
        assertEquals("salut", w.c.messages.last().text)
        assertEquals(MsgStatus.DELIVERED, w.a.messages.single().status)
    }

    @Test
    fun privateMessagesFromStrangersAreDropped() = runTest {
        val w = chain()
        val inner = InnerCodec.encode(Inner.Text(1L, null, "salut"))
        val boxed = testCrypto.box(inner, w.c.identity.box.publicKey, w.d.identity.box.secretKey)!!
        w.d.engine.unicast(PacketType.PRIVATE, w.c.nodeId, boxed)
        advanceTimeBy(5_000)
        assertTrue(w.c.messages.isEmpty())
        assertTrue(w.c.friends.value.isEmpty())
    }

    @Test
    fun introCarryingSomeoneElsesKeysIsIgnored() = runTest {
        val w = chain()
        val a = w.a.identity
        // A's own intro for C, sent again by D under D's nodeId
        val signed = InnerCodec.introSignedBytes(w.c.nodeId, 9L, a.box.publicKey, a.sign.publicKey, "Mama")
        val replay = Inner.Intro(9L, a.box.publicKey, a.sign.publicKey, "Mama", testCrypto.sign(signed, a.sign.secretKey))
        w.d.engine.unicast(PacketType.PRIVATE, w.c.nodeId, testCrypto.seal(InnerCodec.encode(replay), w.c.identity.box.publicKey)!!)
        advanceTimeBy(5_000)
        assertTrue(w.c.friends.value.isEmpty())
        assertTrue(w.c.messages.isEmpty())
    }

    @Test
    fun introSignedForSomeoneElseIsIgnored() = runTest {
        val w = chain()
        val d = w.d.identity
        val signed = InnerCodec.introSignedBytes(w.b.nodeId, 5L, d.box.publicKey, d.sign.publicKey, "D")
        val intro = Inner.Intro(5L, d.box.publicKey, d.sign.publicKey, "D", testCrypto.sign(signed, d.sign.secretKey))
        w.d.engine.unicast(PacketType.PRIVATE, w.c.nodeId, testCrypto.seal(InnerCodec.encode(intro), w.c.identity.box.publicKey)!!)
        advanceTimeBy(5_000)
        assertTrue(w.c.friends.value.isEmpty())
    }

    @Test
    fun introUnderAStaffNameArrivesUnderANeutralOne() = runTest {
        val world = World(this)
        val fake = world.phone("Securitate")
        val c = world.phone("C")
        world.net.start()
        advanceTimeBy(30_000)
        fake.chat.addFriend(c.chat.myCard())
        advanceTimeBy(5_000)
        val atC = c.friends.value.single()
        assertEquals(fake.nodeId, atC.nodeId)
        assertFalse(ReservedNames.imitatesStaff(atC.nickname), atC.nickname)
    }

    @Test
    fun removedFriendStaysRemovedUntilTheyScanAgain() = runTest {
        val w = chain()
        w.a.chat.addFriend(w.c.chat.myCard())
        advanceTimeBy(5_000)
        w.c.chat.removeFriend(w.a.nodeId)
        advanceTimeBy(120_000)
        assertTrue(w.c.friends.value.isEmpty())

        w.a.chat.addFriend(w.c.chat.myCard())
        advanceTimeBy(5_000)
        assertEquals(listOf(w.a.nodeId), w.c.friends.value.map { it.nodeId })
    }

    @Test
    fun blockedPersonCantAddThemselvesBack() = runTest {
        val w = chain()
        w.a.chat.addFriend(w.c.chat.myCard())
        advanceTimeBy(5_000)
        w.c.chat.block(w.a.nodeId)
        w.a.chat.addFriend(w.c.chat.myCard())
        advanceTimeBy(60_000)
        assertTrue(w.c.friends.value.isEmpty(), "C blocked A, so A's new intro is ignored")

        w.c.chat.addFriend(w.a.chat.myCard())
        advanceTimeBy(5_000)
        assertEquals(listOf(w.a.nodeId), w.c.friends.value.map { it.nodeId })
        w.a.chat.sendText(Conversations.friend(w.c.nodeId), "iar suntem prieteni")
        advanceTimeBy(5_000)
        assertEquals("iar suntem prieteni", w.c.messages.last().text)
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

    /** Collects the distinct PRIVATE packets [phone] originates: every attempt is a new packet and a new flood. */
    private fun floodsFrom(phone: TestPhone): Set<Long> {
        val ids = mutableSetOf<Long>()
        phone.radio.tamper = { frame ->
            val p = PacketCodec.decode(frame)?.packet
            if (p != null && p.type == PacketType.PRIVATE && p.sender == phone.nodeId) ids += p.id
            frame
        }
        return ids
    }

    @Test
    fun undeliverableMessageFloodsAtMostTwentyTimesOverHours() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val b = world.phone("B")
        val c = world.phone("C")
        a.befriend(c)
        world.net.start()
        advanceTimeBy(10_000)
        c.radio.powerOff()
        advanceTimeBy(5_000)
        val floods = floodsFrom(a)
        a.chat.sendText(Conversations.friend(c.nodeId), "ma auzi?")
        advanceTimeBy(3 * 60 * 60_000L)
        assertEquals(1, b.engine.state.value.readyLinks)
        assertEquals(ChatManager.MAX_ATTEMPTS, floods.size)
        assertEquals(MsgStatus.FAILED, a.messages.single().status)
        assertTrue(a.chatStore.value.outbox.isEmpty())
    }

    @Test
    fun linkChurnDoesNotMultiplyRetries() = runTest {
        val world = World(this)
        val a = world.phone("A")
        world.phone("B")
        val c = world.phone("C")
        a.befriend(c)
        world.net.start()
        advanceTimeBy(10_000)
        c.radio.powerOff()
        advanceTimeBy(5_000)
        val floods = floodsFrom(a)
        a.chat.sendText(Conversations.friend(c.nodeId), "ma auzi?")
        // the only neighbor keeps leaving and coming back, as in a moving crowd
        var afterTenMinutes = 0
        repeat(600) {
            advanceTimeBy(10_000)
            world.net.cut("A", "B")
            advanceTimeBy(2_000)
            world.net.heal("A", "B")
            if (it == 49) afterTenMinutes = floods.size
        }
        assertTrue(afterTenMinutes <= 14, "$afterTenMinutes floods in 10 minutes, about one a minute")
        assertTrue(floods.size <= ChatManager.MAX_ATTEMPTS, "${floods.size} floods in 2 hours")
        assertEquals(MsgStatus.FAILED, a.messages.single().status)
    }

    @Test
    fun copiesOfOneMessageGetOneDeliveredUntilARetryShowsItWasLost() = runTest {
        val world = World(this)
        val a = world.phone("A")
        val c = world.phone("C")
        a.befriend(c)
        world.net.start()
        advanceTimeBy(10_000)
        val receipts = mutableSetOf<Long>()
        c.radio.tamper = { frame ->
            val p = PacketCodec.decode(frame)?.packet
            if (p != null && p.type == PacketType.PRIVATE) {
                receipts += p.id
                null
            } else frame
        }
        a.chat.sendText(Conversations.friend(c.nodeId), "o data")
        advanceTimeBy(1_000)
        repeat(3) {
            a.chat.resend(a.messages.single())
            advanceTimeBy(1_000)
        }
        assertEquals(4, a.chatStore.value.outbox.single().attempts)
        assertEquals(1, c.messages.size)
        assertEquals(1, receipts.size, "four copies, one DELIVERED")

        c.radio.tamper = null
        advanceTimeBy(10_000)
        a.chat.resend(a.messages.single())
        advanceTimeBy(5_000)
        assertEquals(MsgStatus.DELIVERED, a.messages.single().status)
        assertEquals(1, c.messages.size)
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
        // a minute after sending, the next scheduled attempt is still far off
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
        val deleted = a.messages.single()
        a.chat.deleteMessage(deleted)
        assertTrue(a.messages.isEmpty())
        // what's left is the intro, which still goes
        assertTrue(a.chatStore.value.outbox.none { it.msgId == deleted.msgId })
        world.net.start()
        advanceTimeBy(40_000)
        assertTrue(c.messages.isEmpty())
    }

    // --- groups ---

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
        assertEquals("la intrare", b.messages.last().text, "B receives from C even though they aren't friends")
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
        // X knows the group id and builds an identical group locally
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

/** Each byte as one character, so text sent in the clear shows up as itself. */
private fun latin1(bytes: ByteArray): String = CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
