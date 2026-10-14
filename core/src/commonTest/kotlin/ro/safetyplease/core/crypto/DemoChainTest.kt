package ro.safetyplease.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.SYSTEM
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.AckFlags
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.HelloProof
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.PointLevel
import ro.safetyplease.core.protocol.ResponseBody
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The debug build's demo key chain (app/src/debug/assets): what it issues holds there and nowhere else. */
class DemoChainTest {
    private class Wall(val sec: Long) : Clock {
        override fun wallMs(): Long = sec * 1000
        override fun monoMs(): Long = sec * 1000
    }

    private fun json(path: String): JsonObject = Json.parseToJsonElement(repoFile(path).readText()).jsonObject

    private fun JsonObject.hex(key: String): ByteArray = getValue(key).jsonPrimitive.content.hexToBytes()

    private fun folder(name: String) = JsonObject(
        FileSystem.SYSTEM.list(repoFile("app/src/main/assets/$name")).filter { it.name.endsWith(".md") }.sortedBy { it.name }
            .associate { it.name to JsonPrimitive(it.readText()) },
    )

    private val debugKeys = json("app/src/debug/assets/staff_public.json")
    private val releaseKeys = json("app/src/main/assets/staff_public.json")
    private val seeds = json("app/src/debug/assets/demo_chain.json")
    private val legacyBoxSeed = json("app/src/debug/assets/demo_staff.json").hex("boxSeed")

    private val t0 = 1_791_800_000L
    private val clock = Wall(t0)
    private val root = debugKeys.hex("root")
    private val issuers = debugKeys.getValue("chain").jsonArray.map { Certificate.decode(it.jsonPrimitive.content.hexToBytes())!! }
    private val organiser = testCrypto.signKeyPairFromSeed(seeds.hex("organiserSeed"))
    private val event = testCrypto.signKeyPairFromSeed(seeds.hex("eventSeed"))
    private val publicKeys = StaffPublicKeys(debugKeys.hex("box"), debugKeys.hex("sign"))
    private val issuer = EventIssuer(testCrypto, event)
    private val daySeed = testCrypto.random(Crypto.SEED)
    private val day = issuer.reportKey(daySeed, t0 - 3600, t0 + 20 * 3600)

    private fun chain(root: ByteArray?) = KeyChain(testCrypto, clock, publicKeys.sign, root, issuers)

    private fun card(role: StaffRole, name: String, zone: String = ""): ShiftCard {
        val issued = issuer.shift(role, name, t0 - 3600, t0 + 16 * 3600, zone, day, daySeed, legacyBoxSeed)
        return QrCodes.decodeShift(QrCodes.encodeShift(issued))!!
    }

    @Test
    fun theDemoSeedsSignTheChainTheDebugBuildTrusts() {
        val (organiserCert, eventCert) = issuers
        assertContentEquals(organiser.publicKey, organiserCert.key)
        assertContentEquals(event.publicKey, eventCert.key)
        assertEquals(CertRole.ORGANISER, organiserCert.role)
        assertEquals(CertRole.EVENT, eventCert.role)
        assertEquals(CertStatus.VALID, chain(root).status(eventCert))
        assertEquals(CertStatus.VALID, chain(root).status(card(StaffRole.CONTROL, "Control").certificate))
    }

    @Test
    fun demoShiftsSignWhatTheirRoleMay() {
        val chain = chain(root)
        val staff = StaffCrypto(testCrypto, publicKeys, chain)
        val (controlCred, control) = staff.credentialFrom(card(StaffRole.CONTROL, "Control"))!!
        val (_, medic) = staff.credentialFrom(card(StaffRole.STAFF, "Medical 1", "main-stage"))!!
        val id = ByteArray(16) { it.toByte() }

        assertEquals(Verdict.VALID, chain.checkAck(staff.signAck(id, AckStatus.ACKNOWLEDGED, t0, "Medical 1", control!!, AckFlags.ASSIGNED).encode()))
        assertEquals(Verdict.VALID, chain.checkAck(staff.signAck(id, AckStatus.ON_SCENE, t0, "Medical 1", medic!!).encode()))
        assertEquals(Verdict.REFUSED, chain.checkAck(staff.signAck(id, AckStatus.ACKNOWLEDGED, t0, "Medical 1", medic, AckFlags.ASSIGNED).encode()))
        assertEquals(Verdict.VALID, chain.checkPoint(staff.signPoint(5, PointLevel.BUSY, t0, "Medical 1", medic).encode()))

        val reply = testCrypto.boxKeyPair()
        assertEquals(Verdict.VALID, chain.checkResponse(staff.signResponse(id, t0, ResponseBody(1), reply.publicKey, control)!!.encode()))
        val notice = Notice.sign(testCrypto, controlCred, NoticeTopic.HEAT, 2, 1, t0, t0 + 3600, 11)
        assertEquals(Verdict.VALID, chain.checkAnnouncement(notice.encode()))

        // every demo team opens the day's reports, and those sealed to the debug build's key
        chain.addReportKey(day)
        assertNotNull(staff.openReport(staff.sealReport(byteArrayOf(1, 2, 3))!!, medic))
        assertNotNull(staff.openReport(testCrypto.seal(byteArrayOf(4), publicKeys.box)!!, medic))
    }

    @Test
    fun aDemoAnchorProvesItsRoleAndOpensNothing() {
        val chain = chain(root)
        val anchor = card(StaffRole.ANCHOR, "Ancora bar", "bar")
        assertNull(anchor.reportKey)
        val (credential, secret) = StaffCrypto(testCrypto, publicKeys, chain).credentialFrom(anchor)!!
        assertNull(secret)
        val nonce = testCrypto.random(Hello.NONCE_SIZE)
        val proof = HelloProof(credential.certificate, testCrypto.sign(Hello.proofMessage(nonce, 7L), credential.key.secretKey))
        assertEquals(NodeFlags.ANCHOR, chain.provenRoles(proof, nonce, 7L))
    }

    @Test
    fun releaseBuildsRefuseTheDemoChain() {
        val releaseRoot = (releaseKeys["root"] as? JsonPrimitive)?.content?.hexToBytes()
        if (releaseRoot != null) assertFalse(releaseRoot.contentEquals(root))
        val chain = chain(releaseRoot)
        val card = card(StaffRole.CONTROL, "Control")
        assertEquals(CertStatus.UNKNOWN, chain.status(card.certificate))
        val (_, control) = StaffCrypto(testCrypto, publicKeys, chain).credentialFrom(card)!!
        val ack = StaffCrypto(testCrypto, publicKeys, chain).signAck(ByteArray(16), AckStatus.ACKNOWLEDGED, t0, "Control", control!!)
        assertEquals(Verdict.REFUSED, chain.checkAck(ack.encode()))
    }

    @Test
    fun aPackTheDemoOrganiserSignsLoadsOnlyUnderTheDebugRoot() {
        val venue = Json.parseToJsonElement(repoFile("app/src/main/assets/venue.json").readText())
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive("demo-festival"),
                "version" to JsonPrimitive(1),
                "kind" to JsonPrimitive("real"),
                "name" to JsonPrimitive("Festival demo"),
                "validFrom" to JsonPrimitive(t0 - 3600),
                "validUntil" to JsonPrimitive(t0 + 4 * 86_400),
                "organiser" to JsonPrimitive(issuers[0].encode().toHex()),
                "event" to JsonPrimitive(issuers[1].encode().toHex()),
                "reportKeys" to Json.parseToJsonElement("[\"${day.encode().toHex()}\"]"),
                "venue" to venue,
                // what the demo pack bundles from the app's assets
                "notices" to Json.parseToJsonElement(repoFile("app/src/main/assets/notices.json").readText()),
                "firstAid" to folder("firstaid"),
                "pages" to JsonObject(mapOf("ro" to folder("pages"), "en" to folder("pages-en"))),
            ),
        ).toString()
        val text = EventPacks.sign(body, testCrypto, organiser)
        val check = EventPacks.check(text, testCrypto, root, t0)
        assertEquals(PackStatus.VALID, check.status)
        val pack = check.pack!!
        assertContentEquals(day.key, pack.reportKeys.single().key)
        assertEquals(setOf("ro", "en"), pack.pages.keys)
        assertNotNull(pack.notices)
        val releaseRoot = (releaseKeys["root"] as? JsonPrimitive)?.content?.hexToBytes()
        assertEquals(PackStatus.WRONG_CHAIN, EventPacks.check(text, testCrypto, releaseRoot, t0).status)
    }
}
