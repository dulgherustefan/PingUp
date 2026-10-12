package ro.safetyplease.core.announce

import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.Revocation
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.MalformedException
import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.util.utf8

/** What a notice is about; a new one replaces the one of the same topic. A topic this version doesn't know is kept as a number. */
object NoticeTopic {
    const val ALERT = 1
    const val HEAT = 2
    const val SCHEDULE = 3
    const val TRANSPORT = 4

    val KNOWN = listOf(ALERT, HEAT, SCHEDULE, TRANSPORT)

    /** The levels a topic has: an alert informs, pauses or evacuates, heat has four steps, a change has one. */
    fun levels(topic: Int): IntRange = when (topic) {
        ALERT -> NoticeLevel.INFO..NoticeLevel.EVACUATION
        HEAT -> 1..4
        SCHEDULE, TRANSPORT -> 1..1
        else -> 1..0xFF
    }
}

/** The levels of [NoticeTopic.ALERT]. */
object NoticeLevel {
    const val INFO = 1
    const val PAUSE = 2
    const val EVACUATION = 3
}

/**
 * ANNOUNCEMENT of kind 2 (PROTOCOL.md, section 6.8): what the control console tells every phone, signed by a control
 * shift whose [certificate] it carries. The text isn't on the wire: [template] names one of the event pack's, in every
 * language it has, and [params] fill it in. A notice replaces the one of its [topic] with a lower [seq]; [CANCEL] withdraws
 * it. It holds from [issued] to [validUntil] (seconds, the console's clock). [zone] is empty for the whole site.
 */
class Notice(
    val topic: Int,
    val level: Int,
    val flags: Int,
    val seq: Long,
    val issued: Long,
    val validUntil: Long,
    val template: Int,
    val zone: String,
    val params: List<String>,
    val certificate: Certificate,
    val signature: ByteArray,
    val extensions: ByteArray = ByteArray(0),
) {
    val cancelled: Boolean get() = flags and CANCEL != 0

    /** A pause or an evacuation: it takes the screen once and sounds. */
    val urgent: Boolean get() = topic == NoticeTopic.ALERT && level >= NoticeLevel.PAUSE && !cancelled

    fun encode(): ByteArray = unsigned(topic, level, flags, seq, issued, validUntil, template, zone, params, certificate) + extensions + signature

    companion object {
        const val KIND = 2

        /** Flag bit: the notice withdraws the one of its topic and shows nothing. Other bits are reserved and ignored. */
        const val CANCEL = 0x01
        const val MAX_PARAMS = 4
        const val PARAM_BYTES = 40

        /** The longest a notice may hold, from its issue. */
        const val MAX_VALID_S = 24 * 3600L
        private val DOMAIN = "SP-ANN-v1".utf8()

        fun unsigned(
            topic: Int, level: Int, flags: Int, seq: Long, issued: Long, validUntil: Long, template: Int, zone: String,
            params: List<String>, certificate: Certificate,
        ): ByteArray {
            val zoneBytes = zone.utf8()
            val paramBytes = params.map { it.utf8() }
            require(topic in 1..0xFF && level in 0..0xFF && flags in 0..0xFF && template in 0..0xFF)
            require(zoneBytes.size <= Limits.ZONE_BYTES && params.size <= MAX_PARAMS && paramBytes.all { it.size <= PARAM_BYTES })
            val w = WireWriter(256).u8(KIND).u8(topic).u8(level).u8(flags).u32(seq).u32(issued).u32(validUntil).u8(template)
            w.u8(zoneBytes.size).bytes(zoneBytes).u8(params.size)
            paramBytes.forEach { w.u8(it.size).bytes(it) }
            return w.bytes(certificate.encode()).toByteArray()
        }

        fun signedMessage(payload: ByteArray): ByteArray = Revocation.signedMessage(payload)

        /** Signed with the control shift of [credential]. */
        fun sign(
            crypto: Crypto, credential: Credential, topic: Int, level: Int, seq: Long, issued: Long, validUntil: Long, template: Int,
            zone: String = "", params: List<String> = emptyList(), flags: Int = 0,
        ): Notice {
            val cert = credential.certificate
            val unsigned = unsigned(topic, level, flags, seq, issued, validUntil, template, zone, params, cert)
            val signature = crypto.sign(DOMAIN + unsigned, credential.key.secretKey)
            return Notice(topic, level, flags, seq, issued, validUntil, template, zone, params, cert, signature)
        }

        /** Null for another kind of announcement, or one that doesn't parse. */
        fun decode(payload: ByteArray): Notice? = parseOrNull {
            val r = WireReader(payload)
            if (r.u8() != KIND) throw MalformedException("kind")
            val topic = r.u8()
            if (topic == 0) throw MalformedException("topic")
            val level = r.u8()
            val flags = r.u8()
            val seq = r.u32()
            val issued = r.u32()
            val validUntil = r.u32()
            val template = r.u8()
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            val count = r.u8()
            if (count > MAX_PARAMS) throw MalformedException("params")
            val params = List(count) { r.string(r.u8(), PARAM_BYTES) }
            val certificate = Certificate.read(r)
            val extensions = r.bytes(r.remaining - Certificate.SIGNATURE_SIZE)
            Notice(topic, level, flags, seq, issued, validUntil, template, zone, params, certificate, r.bytes(Certificate.SIGNATURE_SIZE), extensions)
        }
    }
}
