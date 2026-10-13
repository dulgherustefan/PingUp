package ro.safetyplease.core.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.ManDownDetector.Companion.GRAVITY
import ro.safetyplease.core.incidents.ManDownWatch
import ro.safetyplease.core.incidents.MotionSample
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.incidents.StaffCode
import ro.safetyplease.core.incidents.StaffCodes
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Man-down and the crowd levels through [StaffChain] itself: the sensor, the question, the report it sends or not. */
@OptIn(ExperimentalCoroutinesApi::class)
class StaffChainTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    /** A phone whose accelerometer the test drives. */
    private class Sensor(base: FakePlatform) : AppPlatform by base {
        var onSample: ((MotionSample) -> Unit)? = null

        override fun watchMotion(onSample: ((MotionSample) -> Unit)?) {
            this.onSample = onSample
        }

        override val watchesMotion = true
    }

    /** Notifications that may be off, as the person may have set them. */
    private class Asking(var allowed: Boolean = true) : Notifications by FakeNotifications() {
        val asked = mutableListOf<Long>()

        override fun manDownCheck(untilMs: Long): Boolean {
            if (allowed) asked += untilMs
            return allowed
        }
    }

    private class Phone(val chain: StaffChain, val sensor: Sensor, val notifications: Asking, val sent: List<Pair<ReportDraft, Boolean>>, val foreground: MutableStateFlow<Boolean>)

    private val venue = Venue(
        "Test", Bounds(43.99, 27.99, 44.01, 28.02),
        listOf(Zone("main-stage", "Scena", "#000000", listOf(GeoPoint(43.999, 27.999), GeoPoint(44.001, 27.999), GeoPoint(44.001, 28.001)))),
    )

    private fun TestScope.phone(): Phone {
        val sensor = Sensor(FakePlatform(tmp))
        val notifications = Asking()
        val sent = mutableListOf<Pair<ReportDraft, Boolean>>()
        val foreground = MutableStateFlow(false)
        val settings = JsonStore(tmp.root / "settings.json", Settings.serializer(), Settings(role = Role.STAFF, teamName = "Steward 1"), backgroundScope)
        val chain = StaffChain(
            backgroundScope, backgroundScope, sensor, notifications, TestClock(testScheduler), settings, MutableStateFlow(IncidentData()), venue,
            { StraightLine }, { draft, bypass -> sent += draft to bypass; true },
            tmp.root / "density.json", tmp.root / "handover.json", tmp.root / "staff_chain.json", tmp.root / "export", foreground,
        )
        chain.start()
        runCurrent()
        return Phone(chain, sensor, notifications, sent, foreground)
    }

    /** Walking with the phone upright in a pocket, a fall onto the ground, then lying still for [lieMs]: 25 readings a second. */
    private fun Phone.fall(fromMs: Long, lieMs: Long = 25_000) {
        val feed = assertNotNull(sensor.onSample, "the sensor runs")
        var t = fromMs
        fun add(ms: Long, g: (Long) -> Triple<Double, Double, Double>) {
            val begin = t
            while (t < begin + ms) {
                val (x, y, z) = g(t - begin)
                feed(MotionSample(t, (x * GRAVITY).toFloat(), (y * GRAVITY).toFloat(), (z * GRAVITY).toFloat()))
                t += 40
            }
        }
        add(5_000) { s -> Triple(0.0, -1.0 + 0.25 * sin(2 * PI * s / 550.0), 0.0) }
        add(400) { Triple(0.4, -0.3, 0.2) }
        add(80) { Triple(0.3, -0.5, 4.0) }
        add(lieMs) { Triple(0.0, 0.0, 1.0) }
    }

    @Test
    fun anAnswerEndsTheQuestionAndSilenceSendsExactlyOneReport() = runTest {
        val p = phone()
        p.fall(0)
        runCurrent()
        assertNotNull(p.chain.manDownAsk.value, "\"Ești OK?\" on the screen")
        assertEquals(1, p.notifications.asked.size, "and as a notification")
        p.chain.answerManDown()
        runCurrent()
        assertNull(p.chain.manDownAsk.value)
        advanceTimeBy(ManDownWatch.ASK_MS + 5_000)
        assertTrue(p.sent.isEmpty(), "an answer sends nothing")

        advanceTimeBy(ManDownWatch.REST_AFTER_ANSWER_MS)
        p.fall(100_000_000)
        runCurrent()
        assertNotNull(p.chain.manDownAsk.value)
        advanceTimeBy(ManDownWatch.ASK_MS + 1)
        val (draft, bypass) = p.sent.single()
        val spec = assertNotNull(StaffCodes.report(StaffCode.STAFF_IN_DANGER))
        assertTrue(draft.extras.manDown, "the man-down entry, TLV 7")
        assertEquals(spec.category, draft.category)
        assertEquals(Severity.URGENT, draft.severity)
        assertTrue(bypass, "nobody chose to send it, so no rate limit holds it")
        assertNull(p.chain.manDownAsk.value)

        p.fall(200_000_000)
        runCurrent()
        advanceTimeBy(ManDownWatch.ASK_MS * 4)
        assertEquals(1, p.sent.size, "one fall, one report: the next waits out the rest")
    }

    @Test
    fun turningItOffEndsTheQuestionAndTheNextFallIsAskedAgain() = runTest {
        val p = phone()
        p.fall(0)
        runCurrent()
        assertNotNull(p.chain.manDownAsk.value)
        p.chain.setManDown(false)
        runCurrent()
        assertNull(p.chain.manDownAsk.value)
        assertNull(p.sensor.onSample, "the sensor stops")
        advanceTimeBy(ManDownWatch.ASK_MS * 2)
        assertTrue(p.sent.isEmpty())

        p.chain.setManDown(true)
        runCurrent()
        p.fall(100_000_000)
        runCurrent()
        assertNotNull(p.chain.manDownAsk.value, "nothing left of the question before")
        assertEquals(2, p.notifications.asked.size)
    }

    @Test
    fun withoutNotificationsOnlyTheOpenAppAsksAndNothingGoesUnasked() = runTest {
        val p = phone()
        p.notifications.allowed = false
        p.fall(0)
        runCurrent()
        assertNull(p.chain.manDownAsk.value, "nobody could see the question")
        advanceTimeBy(ManDownWatch.ASK_MS * 2)
        assertTrue(p.sent.isEmpty(), "so nothing is sent in the person's name")

        p.foreground.value = true
        p.fall(100_000_000)
        runCurrent()
        assertNotNull(p.chain.manDownAsk.value, "the app on screen asks itself")
        advanceTimeBy(ManDownWatch.ASK_MS + 1)
        assertEquals(1, p.sent.size)
    }

    @Test
    fun aRoutineLevelIsKeptInsteadOfAnIncidentAndAHighOneIsBoth() = runTest {
        val p = phone()
        fun crowd(id: String, level: Int) = StaffIncident(
            incidentId = id, category = IncidentCategory.CROWD, severity = Severity.LOW, zone = "main-stage",
            reportedAt = 1_000, receivedAt = 2_000, hops = 1, fromStaff = true, nickname = "Steward 2", density = level,
        )
        assertTrue(p.chain.divert(crowd("a", 2)), "a level of 2 opens no incident")
        assertFalse(p.chain.divert(crowd("b", 5)), "a 5 is an incident")
        assertFalse(p.chain.divert(crowd("c", 0)), "a crowd report without a level is an ordinary one")
        assertEquals(listOf(2, 5), p.chain.density.value.readings.map { it.level })
        assertEquals("Steward 2", p.chain.density.value.readings.first().team)
    }
}
