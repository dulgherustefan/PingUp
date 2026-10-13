package ro.safetyplease.core.announce

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import ro.safetyplease.core.util.utf8

/**
 * One text the console may send: its [topic] and [level], the text in each language (`ro`, `en`), with `{1}` to `{4}`
 * where the console's [params] go, and the label of each parameter in each language.
 */
class NoticeTemplate(
    val id: Int,
    val topic: Int,
    val level: Int,
    val text: Map<String, String>,
    val params: List<Map<String, String>>,
) {
    /** In [language], else in English, else Romanian. */
    fun text(language: String): String = pick(text, language)

    fun paramLabel(index: Int, language: String): String = pick(params[index], language)
}

/**
 * The notice texts of an event (PROTOCOL.md, section 16): the pack's `notices`, or those the app ships. The wire carries
 * only a template's id and its parameters, so a phone shows a notice in its own language.
 */
class NoticeTemplates(val all: List<NoticeTemplate>) {
    fun byId(id: Int): NoticeTemplate? = all.firstOrNull { it.id == id }

    fun forTopic(topic: Int): List<NoticeTemplate> = all.filter { it.topic == topic }

    /** The notice's text in [language]; null for a template this phone doesn't have or one of another topic. */
    fun render(notice: Notice, language: String): String? {
        val template = byId(notice.template)?.takeIf { it.topic == notice.topic } ?: return null
        return fill(template.text(language), notice.params)
    }

    companion object {
        const val MAX_TEXT = 400
        val NONE = NoticeTemplates(emptyList())
        private val TOPICS = mapOf("alert" to NoticeTopic.ALERT, "heat" to NoticeTopic.HEAT, "schedule" to NoticeTopic.SCHEDULE, "transport" to NoticeTopic.TRANSPORT)
        private val PLACEHOLDER = Regex("""\{([1-9])\}""")
        private val json = Json { ignoreUnknownKeys = true }

        /** `{1}` to `{4}` replaced by the parameters; a placeholder without one stays empty. */
        fun fill(text: String, params: List<String>): String =
            PLACEHOLDER.replace(text) { m -> params.getOrNull(m.groupValues[1].toInt() - 1).orEmpty() }

        fun parse(text: String): NoticeTemplates? = try {
            parse(json.parseToJsonElement(text))
        } catch (_: SerializationException) {
            null
        }

        /**
         * A list of templates, or null if one is malformed: an id outside 1..255 or taken twice, an unknown topic, a
         * level the topic doesn't have, no Romanian or English text, a text over [MAX_TEXT] characters, more than
         * [Notice.MAX_PARAMS] parameters or a placeholder past them.
         */
        fun parse(element: JsonElement): NoticeTemplates? {
            val list = (element as? JsonArray)?.map { template(it) ?: return null } ?: return null
            if (list.map { it.id }.toSet().size != list.size) return null
            return NoticeTemplates(list)
        }

        private fun template(element: JsonElement): NoticeTemplate? {
            val o = element as? JsonObject ?: return null
            val id = o.int("id")?.takeIf { it in 1..0xFF } ?: return null
            val topic = TOPICS[o.string("topic")] ?: return null
            val level = o.int("level")?.takeIf { it in NoticeTopic.levels(topic) } ?: return null
            val text = languages(o["text"]) ?: return null
            val params = (o["params"] as? JsonArray ?: JsonArray(emptyList())).map { languages(it) ?: return null }
            if (params.size > Notice.MAX_PARAMS || text.values.any { it.length > MAX_TEXT }) return null
            val highest = text.values.flatMap { t -> PLACEHOLDER.findAll(t).map { it.groupValues[1].toInt() } }.maxOrNull() ?: 0
            if (highest > params.size) return null
            return NoticeTemplate(id, topic, level, text, params)
        }

        /** `{"ro": "...", "en": "..."}`, both present and not blank; other languages are kept. */
        private fun languages(element: JsonElement?): Map<String, String>? {
            val o = element as? JsonObject ?: return null
            val map = o.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null }
            return map.takeIf { m -> listOf("ro", "en").all { !m[it].isNullOrBlank() } }
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        /** A parameter fits the wire. */
        fun fits(param: String): Boolean = param.utf8().size <= Notice.PARAM_BYTES
    }
}

private fun pick(text: Map<String, String>, language: String): String = text[language] ?: text["en"] ?: text.getValue("ro")
