package ro.safetyplease.core.event

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
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
import ro.safetyplease.core.venue.Venue

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

        fun body(venueName: String = "Fest"): String {
            val keys = if (kind == EventKind.EXERCISE) {
                ""","box":"${legacyBox.publicKey.toHex()}","sign":"${legacySign.publicKey.toHex()}""""
            } else {
                ""
            }
            return """{"id":"$id","version":$version,"kind":"${kind.code}","name":"$id","validFrom":$validFrom,""" +
                """"validUntil":$validUntil,"organiser":"${byCert.encode().toHex()}","event":"${cert.encode().toHex()}",""" +
                """"reportKeys":["${dayCert.encode().toHex()}"]$keys,"grid":{"cellMeters":40},""" +
                """"venue":{"name":"$venueName","bounds":{"minLat":44.0,"minLon":26.0,"maxLat":44.01,"maxLon":26.01},"zones":[]},""" +
                """"firstAid":{"01-caldura.md":"# Căldură\n1. Umbră"}}"""
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
        assertArrayEquals(EventPacks.hashOf(event.body()), pack.hash)
        // a real pack keeps the build's shared keys; the chain accepts its event and its day's key
        assertSame(bundled.box, pack.publicKeys(bundled).box)
        val chain = event.chain()
        assertEquals(CertStatus.VALID, chain.status(event.cert))
        assertArrayEquals(event.dayBox.publicKey, chain.reportKey(t0))
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
        assertArrayEquals(drill.legacyBox.publicKey, pack.publicKeys(bundled).box)
        assertArrayEquals(drill.legacySign.publicKey, pack.publicKeys(bundled).sign)
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

        val body = "exercițiu".toByteArray()
        val sealed = drillCrypto.sealReport(body)
        assertArrayEquals(body, drillCrypto.openReport(sealed, drillMedic))
        assertNull(realCrypto.openReport(sealed, realMedic))
        assertNull(drillCrypto.openReport(realCrypto.sealReport(body), drillMedic))
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
        assertEquals(text, EventPacks.read(text.byteInputStream()))
        assertNull(EventPacks.read(ByteArray(EventPacks.MAX_BYTES + 1).inputStream()))
    }

    @Test
    fun theGateCodeNamesAPackByItsHash() {
        val pack = Event().pack()
        val code = QrCodes.encodeEventGate(pack.hash)
        assertTrue(code.startsWith("SPE1."))
        assertArrayEquals(pack.hash, QrCodes.decodeEventGate(code))
        assertNull(QrCodes.decodeEventGate("SPE1.AAAA"))
        assertNull(QrCodes.decodeEventGate(QrCodes.encodeRevocation(ByteArray(32))))
    }
}
