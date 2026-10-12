package ro.safetyplease.core.announce

import kotlinx.serialization.json.JsonPrimitive
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NoticeTemplatesTest {
    private val pause = """{"id":3,"topic":"alert","level":2,"text":{"ro":"Pauză până la {1}.","en":"Paused until {1}."},""" +
        """"params":[{"ro":"Ora","en":"Time"}]}"""

    private val control = testCrypto.signKeyPair()
    private val event = testCrypto.signKeyPair()
    private val credential = Credential(
        Certificate.issue(testCrypto, CertRole.CONTROL, control.publicKey, 0, 0xFFFFFFFFL, "Control", event), control,
    )

    private fun notice(topic: Int, template: Int, params: List<String>) =
        Notice.sign(testCrypto, credential, topic, NoticeLevel.PAUSE, 1, 0, 3600, template, params = params)

    @Test
    fun aTemplateIsFilledInThePhonesLanguage() {
        val templates = NoticeTemplates.parse("[$pause]")!!
        val notice = notice(NoticeTopic.ALERT, 3, listOf("15:30"))
        assertEquals("Pauză până la 15:30.", templates.render(notice, "ro"))
        assertEquals("Paused until 15:30.", templates.render(notice, "en"))
        assertEquals("Paused until 15:30.", templates.render(notice, "de"), "English for a language the pack lacks")
        assertEquals("Time", templates.byId(3)!!.paramLabel(0, "en"))
        assertNull(templates.render(notice(NoticeTopic.ALERT, 9, emptyList()), "ro"), "a template this phone doesn't have")
        assertNull(templates.render(notice(NoticeTopic.HEAT, 3, listOf("15:30")), "ro"), "one of another topic")
    }

    @Test
    fun aMalformedTemplateRejectsTheList() {
        fun bad(edit: (String) -> String) = assertNull(NoticeTemplates.parse("[${edit(pause)}]"))
        bad { it.replace("\"level\":2", "\"level\":4") }
        bad { it.replace("\"topic\":\"alert\"", "\"topic\":\"weather\"") }
        bad { it.replace("\"id\":3", "\"id\":0") }
        bad { it.replace(""","en":"Paused until {1}."""", "") }
        bad { it.replace("{1}.\",\"en\"", "{2}.\",\"en\"") }
        bad { it.replace("[{\"ro\":\"Ora\",\"en\":\"Time\"}]", List(5) { "{\"ro\":\"Ora\",\"en\":\"Time\"}" }.joinToString(",", "[", "]")) }
        bad { "$it,$it" }
        assertNull(NoticeTemplates.parse("{}"))
    }

    @Test
    fun theTemplatesTheAppShipsParse() {
        val shipped = NoticeTemplates.parse(repoFile("app/src/main/assets/notices.json").readText())
        assertNotNull(shipped)
        for (topic in NoticeTopic.KNOWN) {
            for (level in NoticeTopic.levels(topic)) assertNotNull(shipped.forTopic(topic).firstOrNull { it.level == level }, "$topic/$level")
        }
    }

    @Test
    fun aPackCarriesItsTemplatesAndAMalformedOneIsRefused() {
        val root = testCrypto.signKeyPair()
        val organiser = testCrypto.signKeyPair()
        val organiserCert = Certificate.issue(testCrypto, CertRole.ORGANISER, organiser.publicKey, 0, 0xFFFFFFFFL, "Org", root)
        val eventCert = Certificate.issue(testCrypto, CertRole.EVENT, event.publicKey, 0, 2_000_000_000, "Fest", organiser)
        fun text(notices: String): String {
            val body = """{"id":"fest","version":1,"kind":"real","name":"Fest","validFrom":0,"validUntil":2000000000,""" +
                """"organiser":"${organiserCert.encode().toHex()}","event":"${eventCert.encode().toHex()}",""" +
                """"venue":{"name":"Fest","bounds":{"minLat":44.0,"minLon":26.0,"maxLat":44.01,"maxLon":26.01},"zones":[]}$notices}"""
            val signature = testCrypto.sign(EventPacks.signedMessage(body), organiser.secretKey).toHex()
            return """{"format":"pingup-event","version":1,"body":${JsonPrimitive(body)},"signature":"$signature"}"""
        }
        fun check(notices: String) = EventPacks.check(text(notices), testCrypto, root.publicKey, 1_760_000_000)

        assertNull(check("").pack!!.notices, "without them, the app's own")
        assertEquals(listOf(3), check(""","notices":[$pause]""").pack!!.notices!!.all.map { it.id })
        assertEquals(PackStatus.MALFORMED, check(""","notices":[${pause.replace("\"level\":2", "\"level\":7")}]""").status)
    }
}
