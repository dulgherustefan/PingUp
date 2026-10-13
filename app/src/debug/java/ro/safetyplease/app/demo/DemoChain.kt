package ro.safetyplease.app.demo

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.EventIssuer
import ro.safetyplease.core.crypto.KeyPair
import ro.safetyplease.core.crypto.ShiftCard
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.utf8
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The debug build's demo chain: public test keys (assets/demo_chain.json) under a demo root only debug builds trust
 * (assets/staff_public.json, which also names the organiser and event certificates). Release builds ship neither and
 * refuse what it signs. Each day has its own report key, derived from the demo report seed. The demo exercise
 * ([load] with exercise) has an event key, report seed and shared pair of its own, derived from the demo seeds and
 * signed by the same organiser, since an exercise never shares its event key with a real event.
 */
class DemoChain(
    private val crypto: Crypto,
    private val organiser: KeyPair,
    event: KeyPair,
    private val reportSeed: ByteArray,
    private val legacyBoxSeed: ByteArray,
    private val organiserCert: Certificate,
    private val eventCert: Certificate,
    private val zone: ZoneId,
    private val exercise: Boolean = false,
    /** The exercise's shared pair, as public keys: box and sign. */
    private val shared: Pair<ByteArray, ByteArray>? = null,
) {
    private val issuer = EventIssuer(crypto, event)

    fun today(nowSec: Long): LocalDate = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()

    fun reportKey(day: LocalDate): Pair<Certificate, ByteArray> {
        val seed = daySeed(reportSeed, day)
        val (from, until) = dayWindow(day, zone)
        return issuer.reportKey(seed, from, until) to seed
    }

    /** From an hour ago for [SHIFT_HOURS]. Every demo team opens reports, so each phone of the demo shows them. */
    fun shift(role: StaffRole, team: String, zone: String, nowSec: Long): ShiftCard {
        val (key, seed) = reportKey(today(nowSec))
        return issuer.shift(role, team, nowSec - 3600, nowSec + SHIFT_HOURS * 3600, zone, key, seed, legacyBoxSeed)
    }

    /**
     * The demo pack from today for [PACK_DAYS] days, with the schedule of those nights ([DemoSchedule]); a new day gives a
     * newer version, with that day's keys.
     */
    fun pack(template: JsonObject, venue: String, notices: String, firstAid: Map<String, String>, pages: Map<String, Map<String, String>>, nowSec: Long): String {
        val today = today(nowSec)
        val body = buildJsonObject {
            template.forEach { (key, value) -> put(key, value) }
            if (exercise) {
                put("id", EXERCISE_ID)
                put("name", EXERCISE_NAME)
            }
            put("version", today.toEpochDay().toInt())
            put("kind", if (exercise) "exercise" else "real")
            shared?.let { (box, sign) ->
                put("box", box.toHex())
                put("sign", sign.toHex())
            }
            put("validFrom", dayWindow(today, zone).first)
            put("validUntil", dayWindow(today.plusDays(PACK_DAYS - 1), zone).second)
            put("organiser", organiserCert.encode().toHex())
            put("event", eventCert.encode().toHex())
            putJsonArray("reportKeys") {
                for (i in 0 until PACK_DAYS) add(reportKey(today.plusDays(i)).first.encode().toHex())
            }
            put("venue", Json.parseToJsonElement(venue))
            put("schedule", DemoSchedule.json(DemoSchedule.sets(today, PACK_DAYS, zone)))
            put("notices", Json.parseToJsonElement(notices))
            putJsonObject("firstAid") { firstAid.forEach { (name, text) -> put(name, text) } }
            putJsonObject("pages") {
                pages.forEach { (language, files) -> putJsonObject(language) { files.forEach { (name, text) -> put(name, text) } } }
            }
        }
        return EventPacks.sign(body.toString(), crypto, organiser)
    }

    companion object {
        const val SHIFT_HOURS = 16L
        const val PACK_DAYS = 4L
        const val EXERCISE_ID = "demo-exercitiu"
        const val EXERCISE_NAME = "Exercițiu demo"

        fun daySeed(master: ByteArray, day: LocalDate): ByteArray = sha256(master + "day $day".utf8())

        /** Local midnight to the next one, in seconds. */
        fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Long, Long> =
            day.atStartOfDay(zone).toEpochSecond() to day.plusDays(1).atStartOfDay(zone).toEpochSecond()

        /** The demo chain, or with [exercise] the demo exercise's, which the phone in an exercise trusts instead. */
        fun load(context: Context, crypto: Crypto, exercise: Boolean = false): DemoChain {
            fun asset(name: String) = Json.parseToJsonElement(context.assets.open(name).bufferedReader().use { it.readText() }).jsonObject
            fun JsonObject.hex(key: String) = getValue(key).jsonPrimitive.content.hexToBytes()
            val seeds = asset("demo_chain.json")
            val chain = asset("staff_public.json").getValue("chain").jsonArray.map {
                Certificate.decode(it.jsonPrimitive.content.hexToBytes()) ?: error("bad demo certificate")
            }
            val organiser = crypto.signKeyPairFromSeed(seeds.hex("organiserSeed"))
            val boxSeed = asset("demo_staff.json").hex("boxSeed")
            if (!exercise) {
                return DemoChain(
                    crypto, organiser, crypto.signKeyPairFromSeed(seeds.hex("eventSeed")), seeds.hex("reportSeed"), boxSeed,
                    chain[0], chain[1], ZoneId.systemDefault(),
                )
            }
            fun derived(seed: ByteArray, what: String) = sha256(seed + " exercise $what".utf8())
            val event = crypto.signKeyPairFromSeed(derived(seeds.hex("eventSeed"), "event"))
            val real = chain[1]
            val eventCert = Certificate.issue(crypto, CertRole.EVENT, event.publicKey, real.validFrom, real.validUntil, EXERCISE_NAME, organiser)
            val sharedBox = derived(boxSeed, "box")
            val sharedSign = crypto.signKeyPairFromSeed(derived(boxSeed, "sign")).publicKey
            return DemoChain(
                crypto, organiser, event, derived(seeds.hex("reportSeed"), "report"), sharedBox, chain[0], eventCert, ZoneId.systemDefault(),
                exercise = true, shared = crypto.boxKeyPairFromSeed(sharedBox).publicKey to sharedSign,
            )
        }
    }
}
