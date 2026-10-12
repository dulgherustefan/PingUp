package ro.safetyplease.core

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
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.V10Node
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentBody
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.StaffFields
import ro.safetyplease.core.protocol.Triage
import ro.safetyplease.core.util.hexToBytes

/**
 * Field states, closing by control, reopening, cancels staff has to check, staff reports, triage and lost people,
 * end to end on the simulated radio; and what phones still on 1.0 make of it all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncidentStatesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent()
        private var seed = 300L

        fun phone(name: String, team: String? = null) = TestPhone(name, scope, net, clock, tmp.root, event, seed++).also {
            if (team != null) {
                it.staffSecret = event.secret
                it.team = team
                it.engine.setRole(staff = true, anchor = false)
            }
        }

        /** Give it a low [id]: it doesn't connect by itself, and only 1.1 phones with a higher nodeId connect to it. */
        fun oneZero(name: String, id: Long) = V10Node(scope.backgroundScope, net, name, id, clock, { event.staffCrypto.verifyAck(it) })
    }

    /** A reporter, a medical team and the control console, all in range. */
    private class Site(val world: World, val reporter: TestPhone, val medic: TestPhone, val console: TestPhone) {
        val id: String get() = reporter.myReports.single().incidentId
    }

    private fun TestScope.site(): Site {
        val w = World(this)
        val s = Site(w, w.phone("R"), w.phone("M", "Medical 1"), w.phone("K", "Control"))
        w.net.start()
        advanceTimeBy(30_000)
        return s
    }

    private fun draft(category: Int, severity: Int, triage: Triage? = null, staff: StaffFields? = null) =
        ReportDraft(category, severity, "main-stage", 43.9505, 28.6350, "A leșinat lângă bar", null, triage, staff)

    private fun medical() = draft(IncidentCategory.MEDICAL, Severity.URGENT)

    private fun TestPhone.status(id: String) = staffIncidents.single { it.incidentId == id }.status

    private fun cancelsFrom(phone: TestPhone): () -> Int {
        var count = 0
        phone.radio.tamper = { frame -> frame.also { if (it[1].toInt() == PacketType.INCIDENT_CANCEL) count++ } }
        return { count }
    }

    // --- cancels staff has to check ---

    @Test
    fun anUrgentReportsCancelWaitsForTheOperator() = runTest {
        val s = site()
        val sent = cancelsFrom(s.reporter)
        s.reporter.incidents.report(medical())
        advanceTimeBy(10_000)
        assertEquals(1, s.medic.alerts.size)

        s.reporter.incidents.cancel(s.id)
        advanceTimeBy(10_000)
        for (staff in listOf(s.medic, s.console)) {
            val incident = staff.staffIncidents.single()
            assertTrue("shown as cancelled by the reporter, to check", incident.cancelRequested)
            assertEquals("nobody signs CANCELLED by itself", AckStatus.RECEIVED, incident.status)
            assertTrue("the alert stays", staff.clearedAlerts.isEmpty())
        }
        val mine = s.reporter.myReports.single()
        assertTrue(mine.cancelled)
        assertEquals(AckStatus.RECEIVED, mine.status)
        assertTrue(IncidentManager.cancelIsChecked(mine))
        assertEquals("the report keeps spreading, so every staff phone sees both", 1, s.reporter.engine.state.value.cachedReports)

        s.console.incidents.confirmCancel(s.id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.CANCELLED, s.reporter.myReports.single().status)
        assertEquals(AckStatus.CANCELLED, s.medic.status(s.id))
        assertEquals(listOf(s.id), s.medic.clearedAlerts)
        val after = sent()
        advanceTimeBy(5 * 60_000L)
        assertEquals("the reporter stops once it's closed", after, sent())
    }

    @Test
    fun theOperatorMayCloseItAsUnfoundedInstead() = runTest {
        val s = site()
        s.reporter.incidents.report(draft(IncidentCategory.VIOLENCE, Severity.MEDIUM))
        advanceTimeBy(10_000)
        s.reporter.incidents.cancel(s.id)
        advanceTimeBy(10_000)
        assertTrue(s.console.staffIncidents.single().cancelRequested)
        s.console.incidents.unfounded(s.id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.UNFOUNDED, s.reporter.myReports.single().status)
        assertEquals(AckStatus.UNFOUNDED, s.medic.status(s.id))
        assertEquals(listOf(s.id), s.medic.clearedAlerts)
    }

    @Test
    fun onlyCategoriesWhereAWrongCancelCostsLittleCancelByThemselves() {
        for (category in listOf(IncidentCategory.CROWD, IncidentCategory.OTHER, IncidentCategory.LOST_FRIEND)) {
            assertFalse(Priority.cancelNeedsReview(category, Severity.MEDIUM))
            assertTrue("unless urgent", Priority.cancelNeedsReview(category, Severity.URGENT))
        }
        val atRisk = listOf(
            IncidentCategory.MEDICAL, IncidentCategory.VIOLENCE, IncidentCategory.LOST_CHILD, IncidentCategory.HARASSMENT,
            IncidentCategory.FIRE, 42,
        )
        for (category in atRisk) assertTrue("$category", Priority.cancelNeedsReview(category, Severity.LOW))
    }

    // --- field states, closing and reopening ---

    @Test
    fun fieldStatesReachTheReporterAndControlClosesTheIncident() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(10_000)
        s.medic.incidents.acknowledge(s.id)
        advanceTimeBy(5_000)
        s.medic.incidents.onScene(s.id)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.ON_SCENE, s.reporter.myReports.single().status)
        assertEquals(AckStatus.ON_SCENE, s.console.status(s.id))
        s.medic.incidents.handOver(s.id)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.HANDED_OVER, s.console.status(s.id))

        s.console.incidents.close(s.id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.CLOSED_BY_CONTROL, s.reporter.myReports.single().status)
        assertEquals(AckStatus.CLOSED_BY_CONTROL, s.medic.status(s.id))
        assertEquals("Control", s.medic.staffIncidents.single().teamName)
        assertEquals(
            "the 1.0 status for it goes out first, then the new one",
            listOf(AckStatus.RECEIVED, AckStatus.ACKNOWLEDGED, AckStatus.ON_SCENE, AckStatus.HANDED_OVER, AckStatus.RESOLVED, AckStatus.CLOSED_BY_CONTROL),
            s.reporter.reportUpdates.map { it.status },
        )
        assertEquals(listOf(s.id), s.medic.clearedAlerts)
    }

    @Test
    fun aReopenedIncidentOpensAgainEverywhereAndItsOldClosingStaysClosed() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(10_000)
        s.medic.incidents.acknowledge(s.id)
        advanceTimeBy(5_000)
        s.medic.incidents.resolve(s.id)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.RESOLVED, s.reporter.myReports.single().status)

        s.console.incidents.reopen(s.id)
        advanceTimeBy(10_000)
        for (phone in listOf(s.medic, s.console)) {
            assertEquals(AckStatus.REOPENED, phone.status(s.id))
            assertEquals(1, phone.staffIncidents.single().epoch)
        }
        assertEquals(AckStatus.REOPENED, s.reporter.myReports.single().status)
        assertEquals(1, s.reporter.myReports.single().epoch)
        assertEquals("the teams are alerted again", 2, s.medic.alerts.size)

        s.medic.incidents.acknowledge(s.id)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.ACKNOWLEDGED, s.reporter.myReports.single().status)
        assertEquals(1, s.console.staffIncidents.single().epoch)
    }

    @Test
    fun aReopenKnownBeforeTheReportAlertsOnce() = runTest {
        val s = site()
        val late = s.world.phone("L").also { it.radio.announceStatus() }
        advanceTimeBy(30_000)
        s.reporter.incidents.report(medical())
        advanceTimeBy(10_000)
        s.console.incidents.close(s.id)
        advanceTimeBy(10_000)
        s.console.incidents.reopen(s.id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.REOPENED, late.engine.ackStatus(s.id.hexToBytes()))

        late.staffSecret = s.world.event.secret
        late.team = "Medical 2"
        late.incidents.reprocessCached()
        advanceTimeBy(5_000)
        assertEquals(AckStatus.REOPENED, late.status(s.id))
        assertEquals(1, late.alerts.size)
    }

    @Test
    fun aPhoneThatMissedTheReopenCatchesUpThroughSummaries() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(10_000)
        s.console.incidents.close(s.id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.CLOSED_BY_CONTROL, s.reporter.myReports.single().status)

        s.reporter.radio.powerOff()
        advanceTimeBy(2_000)
        s.console.incidents.reopen(s.id)
        advanceTimeBy(5_000)
        s.medic.incidents.acknowledge(s.id)
        advanceTimeBy(5_000)
        assertEquals(AckStatus.CLOSED_BY_CONTROL, s.reporter.myReports.single().status)

        s.reporter.radio.powerOn()
        advanceTimeBy(40_000)
        val mine = s.reporter.myReports.single()
        assertEquals("an older epoch's higher status doesn't hide the reopen", AckStatus.ACKNOWLEDGED, mine.status)
        assertEquals(1, mine.epoch)
        assertEquals("nor does the closing the reporter kept come back", AckStatus.ACKNOWLEDGED, s.console.status(s.id))
        assertEquals(AckStatus.ACKNOWLEDGED, s.medic.engine.ackStatus(s.id.hexToBytes()))
    }

    @Test
    fun aCancelAfterAReopenIsLeftToTheOperatorWhateverTheCategory() = runTest {
        val s = site()
        s.reporter.incidents.report(draft(IncidentCategory.CROWD, Severity.MEDIUM))
        advanceTimeBy(10_000)
        s.console.incidents.close(s.id)
        advanceTimeBy(10_000)
        s.console.incidents.reopen(s.id)
        advanceTimeBy(5_000)
        s.reporter.incidents.cancel(s.id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.REOPENED, s.console.status(s.id))
        assertTrue(s.console.staffIncidents.single().cancelRequested)
    }

    @Test
    fun aReporterThatMissedTheReopenLearnsThatStaffChecksItsCancel() = runTest {
        val s = site()
        s.reporter.incidents.report(draft(IncidentCategory.CROWD, Severity.MEDIUM))
        advanceTimeBy(10_000)
        s.reporter.radio.powerOff()
        advanceTimeBy(2_000)
        s.console.incidents.close(s.id)
        advanceTimeBy(5_000)
        s.console.incidents.reopen(s.id)
        advanceTimeBy(5_000)

        s.reporter.incidents.cancel(s.id)
        advanceTimeBy(1_000)
        assertFalse("it can't know of the reopen yet", IncidentManager.cancelIsChecked(s.reporter.myReports.single()))
        assertEquals("staff had the report: it stays", 1, s.reporter.engine.state.value.cachedReports)

        s.reporter.radio.powerOn()
        advanceTimeBy(40_000)
        val atConsole = s.console.staffIncidents.single()
        assertTrue("staff checks it", atConsole.cancelRequested)
        assertEquals(AckStatus.REOPENED, atConsole.status)
        val mine = s.reporter.myReports.single()
        assertEquals("a summary brought the reopen", 1, mine.epoch)
        assertTrue("so the reporter's screen says staff checks it", IncidentManager.cancelIsChecked(mine))
        assertEquals(1, s.reporter.engine.state.value.cachedReports)
    }

    @Test
    fun aWithdrawnReportNoStaffConfirmedLeavesTheCacheWhenItsCancelClosesIt() = runTest {
        val s = site()
        s.reporter.radio.powerOff()
        advanceTimeBy(2_000)
        s.reporter.incidents.report(draft(IncidentCategory.CROWD, Severity.MEDIUM))
        s.reporter.incidents.report(medical())
        advanceTimeBy(1_000)
        assertEquals(2, s.reporter.engine.state.value.cachedReports)
        s.reporter.myReports.forEach { s.reporter.incidents.cancel(it.incidentId) }
        advanceTimeBy(1_000)
        assertEquals("only the urgent one stays, for staff to check", 1, s.reporter.engine.state.value.cachedReports)
    }

    // --- staff reports, triage, lost people ---

    @Test
    fun staffReportWithTheTeamAndItsFieldsAndRoomForABusyShift() = runTest {
        val s = site()
        val fields = StaffFields(people = 3, dangerContinues = true)
        repeat(6) { assertTrue("report $it", s.medic.incidents.report(draft(IncidentCategory.VIOLENCE, Severity.URGENT, staff = fields))) }
        assertEquals(0L, s.medic.incidents.rateLimitWaitMs())
        advanceTimeBy(30_000)
        val atConsole = s.console.staffIncidents
        assertEquals(6, atConsole.size)
        for (incident in atConsole) {
            assertTrue(incident.fromStaff)
            assertEquals("the team from the staff QR", "Medical 1", incident.nickname)
            assertEquals(3, incident.people)
            assertTrue(incident.dangerContinues)
        }
        assertTrue("a participant can't claim the fields", s.reporter.incidents.report(draft(IncidentCategory.VIOLENCE, Severity.URGENT, staff = fields)))
        advanceTimeBy(30_000)
        val fromParticipant = s.console.staffIncidents.single { it.incidentId == s.id }
        assertFalse(fromParticipant.fromStaff)
        assertNull(fromParticipant.nickname)
        repeat(IncidentManager.STAFF_RATE_MAX - 6) { s.medic.incidents.report(draft(IncidentCategory.OTHER, Severity.LOW)) }
        assertTrue("staff has a limit too, a high one", s.medic.incidents.rateLimitWaitMs() > 0)
    }

    @Test
    fun triageAnswersDecideTheUrgencyAndReachStaffAsTheyWereGiven() = runTest {
        val s = site()
        val triage = Triage(responds = Answer.NO, breathes = Answer.UNKNOWN)
        val severity = Priority.severity(IncidentCategory.MEDICAL, urgent = false, triage = triage)
        assertEquals(Severity.URGENT, severity)
        s.reporter.incidents.report(draft(IncidentCategory.MEDICAL, severity, triage))
        advanceTimeBy(10_000)
        val incident = s.console.staffIncidents.single()
        assertEquals(Severity.URGENT, incident.severity)
        assertEquals(Answer.NO, incident.responds)
        assertEquals("\"don't know\" is shown as such", Answer.UNKNOWN, incident.breathes)
    }

    @Test
    fun severityComesFromTriageTheToggleOrStaysMedium() {
        fun severity(urgent: Boolean, triage: Triage?, category: Int = IncidentCategory.MEDICAL) = Priority.severity(category, urgent, triage)
        assertEquals(Severity.URGENT, severity(false, Triage(Answer.NO, Answer.YES)))
        assertEquals(Severity.URGENT, severity(false, Triage(Answer.YES, Answer.NO)))
        assertEquals(Severity.URGENT, severity(false, Triage(Answer.UNASKED, Answer.NO)))
        assertEquals(Severity.MEDIUM, severity(false, Triage(Answer.YES, Answer.YES)))
        assertEquals("not knowing isn't a no", Severity.MEDIUM, severity(false, Triage(Answer.UNKNOWN, Answer.UNKNOWN)))
        assertEquals(Severity.MEDIUM, severity(false, null))
        assertEquals(Severity.URGENT, severity(true, Triage(Answer.YES, Answer.YES)))
        for (category in listOf(IncidentCategory.FIRE, IncidentCategory.VIOLENCE, IncidentCategory.CROWD)) {
            assertEquals("no category is urgent by itself", Severity.MEDIUM, severity(false, null, category))
        }
        assertEquals(Severity.URGENT, severity(false, null, IncidentCategory.LOST_CHILD))
        assertEquals(Severity.LOW, severity(true, null, IncidentCategory.LOST_FRIEND))
    }

    @Test
    fun aLostFriendIsForControlOnlyAndALostChildAlertsEveryone() = runTest {
        val s = site()
        s.reporter.incidents.report(draft(IncidentCategory.LOST_FRIEND, Priority.severity(IncidentCategory.LOST_FRIEND, false)))
        advanceTimeBy(10_000)
        val friend = s.console.staffIncidents.single()
        assertEquals(Severity.LOW, friend.severity)
        assertTrue("no alert anywhere", s.medic.alerts.isEmpty() && s.console.alerts.isEmpty())
        assertTrue(Priority.isInformational(friend.category))
        assertEquals("the reporter still learns it arrived", AckStatus.RECEIVED, s.reporter.myReports.single().status)

        s.reporter.incidents.report(draft(IncidentCategory.LOST_CHILD, Priority.severity(IncidentCategory.LOST_CHILD, false)))
        advanceTimeBy(10_000)
        val child = s.medic.alerts.single()
        assertEquals(IncidentCategory.LOST_CHILD, child.category)
        assertEquals(Severity.URGENT, child.severity)
    }

    // --- next to phones still on 1.0 ---

    @Test
    fun aOneZeroPhoneShowsTheClosestStatusItKnowsAndCountsNothingMalformed() = runTest {
        val w = World(this)
        val console = w.phone("K", "Control")
        val old = w.oneZero("O", 0x0070_0000_0000_0007L)
        w.net.start()
        advanceTimeBy(30_000)

        val id = ByteArray(16) { (it + 1).toByte() }
        val body = IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, w.clock.wallMs() / 1000, "bar", null, null, "", null)
        old.publishReport(id, w.event.staffCrypto.sealReport(body.encode()))
        advanceTimeBy(10_000)
        assertEquals(AckStatus.RECEIVED, old.ackStatus(id))

        val hex = console.staffIncidents.single().incidentId
        console.incidents.onScene(hex)
        advanceTimeBy(10_000)
        assertEquals("on scene reads as taken", AckStatus.ACKNOWLEDGED, old.ackStatus(id))
        console.incidents.handOver(hex)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.ACKNOWLEDGED, old.ackStatus(id))
        console.incidents.close(hex)
        advanceTimeBy(10_000)
        assertEquals("closed by control reads as resolved", AckStatus.RESOLVED, old.ackStatus(id))
        console.incidents.reopen(hex)
        advanceTimeBy(5_000)
        console.incidents.acknowledge(hex)
        advanceTimeBy(70_000)
        assertEquals("1.0 can't go back: it keeps the closing", AckStatus.RESOLVED, old.ackStatus(id))
        assertEquals(AckStatus.ACKNOWLEDGED, console.status(hex))
        assertEquals(0, old.malformed)
        assertNull(console.engine.state.value.counters.drops[DropReason.MALFORMED])
    }

    @Test
    fun whileTheOneZeroStatusLeadsTheSignerShowsWhatTheNetworkHas() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(10_000)
        s.console.incidents.close(s.id)
        advanceTimeBy(1_000)
        val id = s.id.hexToBytes()
        assertEquals(AckStatus.RESOLVED, s.console.engine.ackStatus(id))
        assertEquals("not a status the network doesn't have yet", AckStatus.RESOLVED, s.console.status(s.id))
        assertTrue(s.console.acks.none { (ack, own, _) -> own && ack.status == AckStatus.CLOSED_BY_CONTROL })

        advanceTimeBy(IncidentManager.ONE_ZERO_LEAD_MS)
        assertEquals(AckStatus.CLOSED_BY_CONTROL, s.console.engine.ackStatus(id))
        assertEquals(AckStatus.CLOSED_BY_CONTROL, s.console.status(s.id))
        assertTrue(s.console.acks.any { (ack, own, _) -> own && ack.status == AckStatus.CLOSED_BY_CONTROL })
    }

    @Test
    fun behindAOneZeroRelayOneOnePhonesSeeWhatOneZeroSees() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val console = w.phone("K", "Control")
        val old = w.oneZero("O", 0x0070_0000_0000_0007L)
        w.net.outOfRange += setOf("R", "K")
        w.net.start()
        advanceTimeBy(30_000)

        reporter.incidents.report(draft(IncidentCategory.LOST_FRIEND, Severity.LOW))
        advanceTimeBy(15_000)
        val id = reporter.myReports.single().incidentId
        assertEquals("a 1.0 relay carries a report it couldn't open anyway", IncidentCategory.LOST_FRIEND, console.staffIncidents.single().category)
        assertEquals(AckStatus.RECEIVED, reporter.myReports.single().status)

        console.incidents.unfounded(id)
        advanceTimeBy(15_000)
        assertEquals(AckStatus.UNFOUNDED, console.status(id))
        assertEquals("only the 1.0 status crosses a 1.0 phone", AckStatus.RESOLVED, reporter.myReports.single().status)
        assertEquals(0, old.malformed)
        assertNull(reporter.engine.state.value.counters.drops[DropReason.MALFORMED])
        assertNull(console.engine.state.value.counters.drops[DropReason.MALFORMED])
    }
}
