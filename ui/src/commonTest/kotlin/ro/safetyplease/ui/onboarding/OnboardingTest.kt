package ro.safetyplease.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnboardingTest {
    private val hop = MeshHop.TRAVEL + MeshHop.DWELL

    @Test
    fun theMessageLeavesFromTheFirstPhone() {
        val start = MeshHop.frame(0)
        assertEquals(0, start.hop)
        assertEquals(0f, start.along)
        assertEquals(1, start.reached, "the sender's phone is lit from the start")
        assertEquals(1f, start.lit)
    }

    @Test
    fun halfwayThroughAHopTheMessageIsBetweenTwoPhones() {
        val middle = MeshHop.frame(MeshHop.TRAVEL / 2)
        assertEquals(0, middle.hop)
        assertTrue(middle.along in 0.3f..0.7f, "eased, but about halfway: ${middle.along}")
        assertEquals(1, middle.reached)
    }

    @Test
    fun aPhoneLightsUpAsTheMessageReachesItAndARingSpreadsFromIt() {
        val arrived = MeshHop.frame(MeshHop.TRAVEL + 1)
        assertEquals(2, arrived.reached)
        assertEquals(1f, arrived.along, "it waits on the phone before the next hop")
        val ring = arrived.rings.single { it.first == 1 }.second
        assertTrue(ring in 0f..0.05f, "the ring has just started: $ring")
        val next = MeshHop.frame(hop + 1)
        assertEquals(1, next.hop)
        assertTrue(next.along < 0.05f)
    }

    @Test
    fun afterTheLastHopTheMessageStaysOnTheFriendsPhone() {
        val done = MeshHop.frame(MeshHop.HOPS * hop + 10)
        assertEquals(MeshHop.HOPS, done.hop)
        assertEquals(MeshHop.HOPS + 1, done.reached, "every phone on the way is lit")
        assertEquals(1f, done.lit)
    }

    @Test
    fun thePictureDimsBeforeTheLoopStartsAgain() {
        assertTrue(MeshHop.frame(MeshHop.PERIOD - 1).lit < 0.05f)
        assertEquals(1f, MeshHop.frame(MeshHop.PERIOD).lit, "a new loop starts lit")
        assertEquals(0, MeshHop.frame(MeshHop.PERIOD).hop)
    }

    @Test
    fun everyFrameStaysInRange() {
        for (ms in 0..MeshHop.PERIOD * 2 step 7) {
            val frame = MeshHop.frame(ms)
            assertTrue(frame.hop in 0..MeshHop.HOPS, "hop at $ms")
            assertTrue(frame.along in 0f..1f, "along at $ms")
            assertTrue(frame.reached in 1..MeshHop.HOPS + 1, "reached at $ms")
            assertTrue(frame.lit in 0f..1f, "lit at $ms")
            assertTrue(frame.rings.all { it.second in 0f..1f && it.first in 0..MeshHop.HOPS }, "rings at $ms")
        }
    }

    @Test
    fun onePlayLastsUnderFiveSecondsAndEndsOnTheMessageArrived() {
        // anything moving on its own for longer needs a way to stop it (WCAG 2.2.2): the picture plays once and stays
        assertTrue(MeshHop.PLAY < 5_000, "${MeshHop.PLAY} ms")
        for (ms in listOf(MeshHop.PLAY - 1, MeshHop.PLAY)) {
            val end = MeshHop.frame(ms)
            assertEquals(MeshHop.HOPS, end.hop, "at $ms")
            assertEquals(1f, end.lit, "at $ms")
        }
        // played again, it dims first and a new message leaves, then it stops where the first play did
        assertTrue(MeshHop.frame(MeshHop.PLAY + MeshHop.DIM / 2).lit < 1f)
        assertEquals(0, MeshHop.frame(MeshHop.PLAY + MeshHop.DIM).hop)
        assertEquals(MeshHop.HOPS, MeshHop.frame(MeshHop.PLAY + MeshHop.PERIOD).hop)
    }

    @Test
    fun theRadarSendsAShortBurstOfRingsAndThenStops() {
        assertEquals(listOf(0f), radarRings(0))
        assertTrue(RADAR_BURST < 5_000, "$RADAR_BURST ms")
        assertTrue(radarRings(RADAR_BURST).isEmpty(), "nothing moves once the burst is over")
        assertTrue(radarRings(RADAR_BURST * 3).isEmpty())
        for (ms in 0..RADAR_BURST step 11) {
            val rings = radarRings(ms)
            assertTrue(rings.all { it in 0f..1f }, "at $ms: $rings")
            assertTrue(rings.size <= 3, "at $ms: $rings")
        }
        assertTrue(radarRings(RADAR_BURST - 1).isNotEmpty(), "the last ring is still fading out")
    }

    @Test
    fun withReducedMotionThePictureShowsTheMessageArrived() {
        val still = MeshHop.still
        assertEquals(MeshHop.HOPS, still.hop)
        assertEquals(MeshHop.HOPS + 1, still.reached)
        assertEquals(1f, still.lit)
        assertTrue(still.rings.isEmpty())
    }

    @Test
    fun theMessageFollowsLinksBetweenNeighbours() {
        // each hop of the path is short enough to be one of the links the picture draws
        for (i in 0 until MeshHop.HOPS) {
            val (a, b) = MeshHop.path[i] to MeshHop.path[i + 1]
            assertTrue(MeshHop.links.any { it == a to b || it == b to a }, "hop $i: $a to $b")
        }
        assertEquals(MeshHop.HOPS + 1, MeshHop.path.size)
    }

    @Test
    fun noPhoneFoundShowsNoDotAndManyStopAtTheMost() {
        assertTrue(radarSpots(0).isEmpty())
        assertEquals(3, radarSpots(3).size)
        assertEquals(RADAR_MAX, radarSpots(40).size)
        assertEquals(radarSpots(5), radarSpots(5), "the same count always lands on the same spots")
    }

    @Test
    fun theDotsAroundTheAvatarNeverCoverEachOther() {
        val spots = radarSpots(RADAR_MAX)
        for ((i, a) in spots.withIndex()) {
            for (b in spots.drop(i + 1)) {
                val apart = kotlin.math.abs(((a.angle - b.angle) % 360f + 540f) % 360f - 180f)
                assertTrue(a.ring != b.ring || apart >= 30f, "$a and $b")
            }
        }
        // the first ones found go on the inner ring, nearest to the person
        assertEquals(0, radarSpots(1).single().ring)
    }

    @Test
    fun theStepsGoInOrderAndOnlyTheSetUpOnesCount() {
        assertEquals(OnboardingStep.NAME, OnboardingStep.WELCOME.next)
        assertEquals(OnboardingStep.READY, OnboardingStep.PERMISSIONS.next)
        assertEquals(null, OnboardingStep.READY.next)
        assertEquals(null, OnboardingStep.WELCOME.previous)
        assertEquals(OnboardingStep.NAME, OnboardingStep.PERMISSIONS.previous)
        assertEquals(null, OnboardingStep.WELCOME.progress, "the welcome is not a step to count")
        assertEquals(listOf(0, 1, 2), listOf(OnboardingStep.NAME, OnboardingStep.PERMISSIONS, OnboardingStep.READY).map { it.progress })
        assertEquals(3, OnboardingStep.COUNTED)
    }
}
