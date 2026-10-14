package ro.safetyplease.core.event

import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.KeyPair
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.Venue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EventPackTest {
    private val t0 = 1_760_000_000L
    private val day = 86_400L
    private val root = testCrypto.signKeyPair()
    private val organiser = testCrypto.signKeyPair()
    private val organiserCert = issue(CertRole.ORGANISER, organiser.publicKey, root, t0 - 30 * day, t0 + 365 * day, "Fest SRL")
    private val bundled = StaffPublicKeys(testCrypto.boxKeyPair().publicKey, testCrypto.signKeyPair().publicKey)

    private fun issue(role: Int, key: ByteArray, by: KeyPair, from: Long, until: Long, name: String) =
        Certificate.issue(testCrypto, role, key, from, until, name, by)

    /** An event of the organiser above: its key, the day's report key and the pack the organiser signs for it. */
    private inner class Event(
        val id: String = "fest",
        val kind: EventKind = EventKind.REAL,
        val version: Int = 1,
        val key: KeyPair = testCrypto.signKeyPair(),
        val by: KeyPair = organiser,
        val byCert: Certificate = organiserCert,
        val validFrom: Long = t0 - 7 * day,
        val validUntil: Long = t0 + 3 * day,
    ) {
        val cert = issue(CertRole.EVENT, key.publicKey, organiser, t0 - 3600, t0 + 3 * day, id)
        val dayBox = testCrypto.boxKeyPair()
        val dayCert = issue(CertRole.REPORT_KEY, dayBox.publicKey, key, t0 - 3600, t0 + day, "")
        val legacyBox = testCrypto.boxKeyPair()
        val legacySign = testCrypto.signKeyPair()

        fun body(venueName: String = "Fest", pages: String = """{"en":{"01-beach.md":"# Beach\nFlags"},"ro":{"01-plaja.md":"# Plajă\nSteaguri"}}"""): String {
            val keys = if (kind == EventKind.EXERCISE) {
                ""","box":"${legacyBox.publicKey.toHex()}","sign":"${legacySign.publicKey.toHex()}""""
            } else {
                ""
            }
            return """{"id":"$id","version":$version,"kind":"${kind.code}","name":"$id","validFrom":$validFrom,""" +
                """"validUntil":$validUntil,"organiser":"${byCert.encode().toHex()}","event":"${cert.encode().toHex()}",""" +
                """"reportKeys":["${dayCert.encode().toHex()}"]$keys,"grid":{"cellMeters":40},""" +
                """"venue":{"name":"$venueName","bounds":{"minLat":44.0,"minLon":26.0,"maxLat":44.01,"maxLon":26.01},"zones":[]},""" +
                """"firstAid":{"01-caldura.md":"# Căldură\n1. Umbră"},"pages":$pages}"""
        }

        fun text(body: String = body()): String {
            val signature = testCrypto.sign(EventPacks.signedMessage(body), by.secretKey).toHex()
            return """{"format":"pingup-event","version":1,"body":${JsonPrimitive(body)},"signature":"$signature"}"""
        }

        fun check(now: Long = t0, text: String = text()) = EventPacks.check(text, testCrypto, root.publicKey, now)

        fun pack(): EventPack = check().pack!!

        fun chain(): KeyChain = pack().let { KeyChain(testCrypto, Wall(t0), it.publicKeys(bundled).sign, root.publicKey, it.issuers, it.reportKeys) }

        fun staffCrypto(): StaffCrypto = pack().let { StaffCrypto(testCrypto, it.publicKeys(bundled), chain()) }

        fun shift(team: String): StaffSecretKeys {
            val key = testCrypto.signKeyPair()
            val cert = issue(CertRole.STAFF, key.publicKey, this.key, t0 - 60, t0 + 3600, team)
            return StaffSecretKeys(dayBox, key, cert)
        }
    }

    private class Wall(private val sec: Long) : Clock {
        override fun wallMs(): Long = sec * 1000
        override fun monoMs(): Long = sec * 1000
    }

    @Test
    fun aPackSignedDownTheChainHoldsAndCarriesTheVenueTheSheetsAndTheKeys() {
        val event = Event()
        val check = event.check()
        assertEquals(PackStatus.VALID, check.status)
        val pack = check.pack!!
        assertEquals("fest", pack.id)
        assertEquals(EventKind.REAL, pack.kind)
        assertEquals("Fest", Venue.parse(pack.venue).name)
        assertEquals(40, pack.gridMeters)
        assertEquals("# Căldură\n1. Umbră", pack.firstAid["01-caldura.md"])
        assertEquals(mapOf("en" to mapOf("01-beach.md" to "# Beach\nFlags"), "ro" to mapOf("01-plaja.md" to "# Plajă\nSteaguri")), pack.pages)
        assertContentEquals(EventPacks.hashOf(event.body()), pack.hash)
        // a real pack keeps the build's shared keys; the chain accepts its event and its day's key
        assertSame(bundled.box, pack.publicKeys(bundled).box)
        val chain = event.chain()
        assertEquals(CertStatus.VALID, chain.status(event.cert))
        assertContentEquals(event.dayBox.publicKey, chain.reportKey(t0))
    }

    @Test
    fun aPackWithoutPagesHasNoneAndOneWithMalformedPagesIsMalformed() {
        val event = Event()
        val without = event.body().replace(Regex(""","pages":\{.*\}\}$"""), "}")
        assertTrue(without != event.body())
        assertEquals(emptyMap<String, Map<String, String>>(), event.check(text = event.text(without)).pack!!.pages)
        val bad = listOf(
            """["a"]""",
            """{"en":"# Beach"}""",
            """{"en":{"01-beach.md":5}}""",
            """{"en":{"01-beach.md":"no title here"}}""",
            """{"en":{"beach.txt":"# Beach"}}""",
            """{"en":{"../beach.md":"# Beach"}}""",
            """{"english":{"01-beach.md":"# Beach"}}""",
        )
        for (pages in bad) {
            assertEquals(PackStatus.MALFORMED, event.check(text = event.text(event.body(pages = pages))).status, pages)
        }
    }

    @Test
    fun aPackLoadsWeeksBeforeItsEventAndNotAfter() {
        val event = Event(validFrom = t0 - 20 * day)
        assertEquals(PackStatus.VALID, event.check(now = t0 - 14 * day).status)
        assertEquals(PackStatus.NOT_YET, event.check(now = t0 - 21 * day).status)
        assertEquals(PackStatus.EXPIRED, event.check(now = t0 + 3 * day + KeyChain.SKEW_S + 1).status)
        assertEquals(PackStatus.VALID, event.check(now = t0 + 3 * day + KeyChain.SKEW_S).status)
        // the organiser's certificate bounds it too
        assertEquals(PackStatus.NOT_YET, event.check(now = t0 - 31 * day - KeyChain.SKEW_S).status)
    }

    @Test
    fun anOrganiserCertifiedAfterTheDayBeganStillSignsIt() {
        val late = issue(CertRole.ORGANISER, organiser.publicKey, root, t0 - 600, t0 + 365 * day, "Fest SRL")
        assertEquals(PackStatus.VALID, Event(byCert = late).check().status)
    }

    @Test
    fun aChangedPackIsTampered() {
        val event = Event()
        val signed = event.text()
        val tampered = signed.replace("\\\"name\\\":\\\"Fest\\\"", "\\\"name\\\":\\\"Fals\\\"")
        assertTrue(tampered != signed)
        assertEquals(PackStatus.TAMPERED, event.check(text = tampered).status)
        // a pack signed over one body and sent with another
        val swapped = signed.replace(JsonPrimitive(event.body()).toString(), JsonPrimitive(event.body("Fals")).toString())
        assertEquals(PackStatus.TAMPERED, event.check(text = swapped).status)
        assertEquals(PackStatus.MALFORMED, event.check(text = signed.dropLast(2)).status)
        assertEquals(PackStatus.MALFORMED, event.check(text = signed.replace("pingup-event", "other")).status)
    }

    @Test
    fun aPackOutsideTheChainIsRefused() {
        // an organiser the root never signed
        val stranger = testCrypto.signKeyPair()
        val strangerCert = issue(CertRole.ORGANISER, stranger.publicKey, testCrypto.signKeyPair(), t0 - day, t0 + day, "Fals SRL")
        assertEquals(PackStatus.WRONG_CHAIN, Event(by = stranger, byCert = strangerCert).check().status)
        // the organiser signs the pack with a key other than the one its certificate names
        assertEquals(PackStatus.TAMPERED, Event(by = stranger).check().status)
        // an event key signed by someone else
        val event = Event()
        val foreign = issue(CertRole.EVENT, event.key.publicKey, stranger, t0 - 3600, t0 + 3 * day, "fest")
        val body = event.body().replace(event.cert.encode().toHex(), foreign.encode().toHex())
        assertEquals(PackStatus.WRONG_CHAIN, event.check(text = event.text(body)).status)
        // a day's report key the event didn't sign
        val loose = issue(CertRole.REPORT_KEY, event.dayBox.publicKey, stranger, t0, t0 + day, "")
        val looseBody = event.body().replace(event.dayCert.encode().toHex(), loose.encode().toHex())
        assertEquals(PackStatus.WRONG_CHAIN, event.check(text = event.text(looseBody)).status)
        // an app without a root trusts no pack
        assertEquals(PackStatus.WRONG_CHAIN, EventPacks.check(event.text(), testCrypto, null, t0).status)
    }

    @Test
    fun aPackWhoseOrganiserOrEventWasRevokedNoLongerHolds() {
        val event = Event()
        fun check(vararg ids: Long) = EventPacks.check(event.text(), testCrypto, root.publicKey, t0, ids.toSet())
        assertEquals(PackStatus.REVOKED, check(organiserCert.id).status)
        assertEquals(PackStatus.REVOKED, check(event.cert.id).status)
        // a revoked day's key is only left out; the chain refuses it where it is used
        assertEquals(PackStatus.VALID, check(event.dayCert.id).status)
        val (shelf, loaded) = EventShelf().load(event.text(), testCrypto, root.publicKey, t0)
        assertEquals(PackStatus.VALID, loaded.status)
        val active = shelf.select(shelf.packs.single().hash)
        assertNull(active.activePack(testCrypto, root.publicKey, t0, setOf(event.cert.id)))
        assertEquals(PackStatus.REVOKED, EventShelf().load(event.text(), testCrypto, root.publicKey, t0, setOf(organiserCert.id)).second.status)
    }

    @Test
    fun eventsCoexistAndOneIsActive() {
        val fest = Event("fest")
        val other = Event("other")
        var shelf = EventShelf()
        val (withFest, first) = shelf.load(fest.text(), testCrypto, root.publicKey, t0)
        assertEquals(Added.NEW, first.added)
        shelf = withFest.load(other.text(), testCrypto, root.publicKey, t0).first
        assertEquals(listOf("fest", "other"), shelf.packs.map { it.id })
        assertNull(shelf.active())

        shelf = shelf.select(fest.pack().hashHex)
        assertEquals("fest", shelf.activePack(testCrypto, root.publicKey, t0)!!.id)
        assertEquals("other", shelf.byHash(other.pack().hash)!!.id)
        assertSame(shelf, shelf.select("00"))
        assertEquals(Added.KNOWN, shelf.load(fest.text(), testCrypto, root.publicKey, t0).second.added)

        // a new version replaces the old and stays active; an older one is refused
        val fest2 = Event("fest", version = 2, key = fest.key)
        val (updated, result) = shelf.load(fest2.text(), testCrypto, root.publicKey, t0)
        assertEquals(Added.UPDATED, result.added)
        assertEquals(2, updated.active()!!.version)
        assertEquals(2, updated.packs.size)
        assertEquals(Added.OLDER, updated.load(fest.text(), testCrypto, root.publicKey, t0).second.added)

        // a refused pack changes nothing; after its event the active pack no longer holds
        val (same, refused) = updated.load(fest.text().replace("fest", "fake"), testCrypto, root.publicKey, t0)
        assertSame(updated, same)
        assertNull(refused.added)
        assertNull(updated.activePack(testCrypto, root.publicKey, t0 + 4 * day))

        val removed = updated.without(updated.active)
        assertEquals("", removed.active)
        assertEquals(listOf("other"), removed.packs.map { it.id })
        assertEquals("", removed.select(other.pack().hashHex).select("").active)
    }

    @Test
    fun anExerciseHasKeysOfItsOwn() {
        val real = Event("fest")
        val drill = Event("fest", EventKind.EXERCISE)
        val pack = drill.pack()
        assertTrue(pack.exercise)
        assertContentEquals(drill.legacyBox.publicKey, pack.publicKeys(bundled).box)
        assertContentEquals(drill.legacySign.publicKey, pack.publicKeys(bundled).sign)
        // an exercise without its own shared keys would fall back to the real ones
        val bare = drill.body().replace(Regex(""","box":"[0-9a-f]+","sign":"[0-9a-f]+""""), "")
        assertEquals(PackStatus.MALFORMED, drill.check(text = drill.text(bare)).status)

        // the same id in both kinds coexists, but never under one event key
        var shelf = EventShelf().load(real.text(), testCrypto, root.publicKey, t0).first
        val (both, added) = shelf.load(drill.text(), testCrypto, root.publicKey, t0)
        assertEquals(Added.NEW, added.added)
        assertEquals(2, both.packs.size)
        shelf = EventShelf().load(real.text(), testCrypto, root.publicKey, t0).first
        val sharing = Event("fest", EventKind.EXERCISE, key = real.key)
        assertEquals(Added.SHARED_KEY, shelf.load(sharing.text(), testCrypto, root.publicKey, t0).second.added)
    }

    @Test
    fun nothingSignedOrSealedInAnExerciseCountsInTheRealEventOrBack() {
        val real = Event("fest")
        val drill = Event("fest-drill", EventKind.EXERCISE)
        val realCrypto = real.staffCrypto()
        val drillCrypto = drill.staffCrypto()
        val realMedic = real.shift("Medical 1")
        val drillMedic = drill.shift("Medical 1")
        val id = ByteArray(16) { it.toByte() }

        val drillAck = drillCrypto.signAck(id, AckStatus.ACKNOWLEDGED, t0, "Medical 1", drillMedic).encode()
        val realAck = realCrypto.signAck(id, AckStatus.ACKNOWLEDGED, t0, "Medical 1", realMedic).encode()
        assertEquals(Verdict.VALID, drill.chain().checkAck(drillAck))
        assertEquals(Verdict.VALID, real.chain().checkAck(realAck))
        assertEquals(Verdict.REFUSED, real.chain().checkAck(drillAck))
        assertEquals(Verdict.REFUSED, drill.chain().checkAck(realAck))

        val body = "exercițiu".encodeToByteArray()
        val sealed = drillCrypto.sealReport(body)!!
        assertContentEquals(body, drillCrypto.openReport(sealed, drillMedic))
        assertNull(realCrypto.openReport(sealed, realMedic))
        assertNull(drillCrypto.openReport(realCrypto.sealReport(body)!!, drillMedic))
    }

    @Test
    fun anExerciseKeepsItsFilesApart() {
        assertEquals("console_log.json", modeFileName("console_log.json", null))
        assertEquals("console_log.json", modeFileName("console_log.json", EventKind.REAL))
        assertEquals("console_log_exercise.json", modeFileName("console_log.json", EventKind.EXERCISE))
        assertEquals("incidents_exercise.json", modeFileName("incidents.json", EventKind.EXERCISE))
        assertEquals("shift_exercise", modeFileName("shift", EventKind.EXERCISE))
    }

    @Test
    fun aPackFileIsReadUpToItsLimit() {
        val text = Event().text()
        assertEquals(text, EventPacks.read(Buffer().writeUtf8(text)))
        assertNull(EventPacks.read(Buffer().write(ByteArray(EventPacks.MAX_BYTES + 1))))
    }

    @Test
    fun theGateCodeNamesAPackByItsHash() {
        val pack = Event().pack()
        val code = QrCodes.encodeEventGate(pack.hash)
        assertTrue(code.startsWith("SPE1."))
        assertContentEquals(pack.hash, QrCodes.decodeEventGate(code))
        assertNull(QrCodes.decodeEventGate("SPE1.AAAA"))
        assertNull(QrCodes.decodeEventGate(QrCodes.encodeRevocation(ByteArray(32))))
    }

    @Test
    fun theGateCodeOfThePackInUseChangesNothing() {
        val fest = Event("fest")
        val other = Event("other")
        val shelf = EventShelf().load(fest.text(), testCrypto, root.publicKey, t0).first
            .load(other.text(), testCrypto, root.publicKey, t0).first
        val festHash = fest.pack().hash
        assertEquals(festHash.toHex(), shelf.gateTarget(festHash)?.hash)
        val inUse = shelf.select(festHash.toHex())
        assertNull(inUse.gateTarget(festHash))
        assertEquals(other.pack().hashHex, inUse.gateTarget(other.pack().hash)?.hash)
        assertNull(inUse.gateTarget(ByteArray(32)))
    }

    @Test
    fun theGridCarriesTheBoxAndTheDayStartOfSharedPositions() {
        val event = Event()
        val plain = event.pack()
        assertNull(plain.positionBox, "the venue bounds give the box")
        assertEquals(event.validFrom.floorDiv(86_400L) * 86_400L, plain.dayStart, "the UTC day of validFrom")

        val grid = """"grid":{"cellMeters":40}"""
        val withBox = event.body().replace(
            grid, """"grid":{"cellMeters":40,"box":{"originLat":44.0,"originLon":26.0,"azimuth":30,"version":2}},"dayStart":1760054400""",
        )
        val pack = event.check(text = event.text(withBox)).pack!!
        assertEquals(PositionBox(44.0, 26.0, 30.0, 2), pack.positionBox)
        assertEquals(1_760_054_400L, pack.dayStart)

        for (bad in listOf("""{"originLat":100,"originLon":26}""", """{"originLat":44,"originLon":26,"azimuth":360}""", "[]")) {
            val body = event.body().replace(grid, """"grid":{"cellMeters":40,"box":$bad}""")
            assertEquals(PackStatus.MALFORMED, event.check(text = event.text(body)).status, bad)
        }
    }

    @Test
    fun theScheduleAndTheSitePlanComeWithThePack() {
        val event = Event()
        assertEquals(emptyList(), event.pack().schedule)
        assertNull(event.pack().plan)

        val zones = """"zones":[]"""
        val stage = """"zones":[{"id":"main","name":"Scena","color":"#E5484D","polygon":[{"lat":44.001,"lon":26.001},""" +
            """{"lat":44.001,"lon":26.002},{"lat":44.002,"lon":26.002}]}]"""
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val corners = """"topLeft":{"lat":44.01,"lon":26.0},"topRight":{"lat":44.01,"lon":26.01},""" +
            """"bottomRight":{"lat":44.0,"lon":26.01},"bottomLeft":{"lat":44.0,"lon":26.0}"""
        fun with(schedule: String, plan: String = """{"png":"$png",$corners}""") =
            event.body().replace(zones, stage).dropLast(1) + ""","schedule":$schedule,"plan":$plan}"""
        val sets = """[{"stage":"main","title":"Late","start":${t0 + 7200},"end":${t0 + 9000}},""" +
            """{"stage":"main","title":"Early","start":$t0,"end":${t0 + 3600}}]"""
        val pack = event.check(text = event.text(with(sets))).pack!!
        assertEquals(listOf("Early", "Late"), pack.schedule.map { it.title }, "by start")
        assertEquals(ScheduleItem("main", "Early", t0, t0 + 3600), pack.schedule.first())
        val plan = pack.plan!!
        assertEquals(listOf(44.01 to 26.0, 44.01 to 26.01, 44.0 to 26.01, 44.0 to 26.0), plan.corners.map { it.lat to it.lon })
        assertEquals(0x89, plan.png[0].toInt() and 0xFF)

        val malformed = listOf(
            with("""[{"stage":"nowhere","title":"X","start":$t0,"end":${t0 + 60}}]"""),
            with("""[{"stage":"main","title":"X","start":$t0,"end":$t0}]"""),
            with("""[{"stage":"main","title":"X","start":$t0,"end":${t0 + EventPacks.SET_MAX_S + 1}}]"""),
            with("""[{"stage":"main","title":"${"x".repeat(EventPacks.SCHEDULE_TITLE_CHARS + 1)}","start":$t0,"end":${t0 + 60}}]"""),
            with("[]", """{"png":"aGVsbG8=",$corners}"""),
            with("[]", """{"png":"not base64!",$corners}"""),
            with("[]", """{"png":"$png"}"""),
            with("[]", """{"png":"$png",${corners.replace("26.01},\"bottomRight", "26.0},\"bottomRight")}}"""),
            with("[]", """{"png":"${"A".repeat((EventPacks.PLAN_MAX_BYTES + 2) / 3 * 4 + 4)}",$corners}"""),
        )
        for (body in malformed) assertEquals(PackStatus.MALFORMED, event.check(text = event.text(body)).status, body.takeLast(120))
    }

    @Test
    fun aPackWithAZoneNoReportOrNoticeCanNameOrWithoutAShapeIsRefused() {
        val event = Event()
        val corners = listOf("""{"lat":44.001,"lon":26.001}""", """{"lat":44.001,"lon":26.002}""", """{"lat":44.002,"lon":26.002}""")
        fun zone(id: String, sides: Int = 3) = """{"id":"$id","name":"Z","color":"#E5484D","polygon":[${corners.take(sides).joinToString(",")}]}"""
        fun check(vararg zones: String) =
            event.check(text = event.text(event.body().replace(""""zones":[]""", """"zones":[${zones.joinToString(",")}]"""))).status
        assertEquals(PackStatus.VALID, check(zone("a".repeat(32))))
        assertEquals(PackStatus.VALID, check(zone("ă".repeat(16))), "32 bytes")
        // an evacuation of that zone alone couldn't be signed, and reports would cut its id short
        assertEquals(PackStatus.MALFORMED, check(zone("a".repeat(33))))
        assertEquals(PackStatus.MALFORMED, check(zone("ă".repeat(17))))
        assertEquals(PackStatus.MALFORMED, check(zone("")))
        assertEquals(PackStatus.MALFORMED, check(zone("a"), zone("a")))
        assertEquals(PackStatus.MALFORMED, check(zone("a", sides = 2)))
        assertEquals(PackStatus.MALFORMED, check(zone("a", sides = 0)))
    }
}
