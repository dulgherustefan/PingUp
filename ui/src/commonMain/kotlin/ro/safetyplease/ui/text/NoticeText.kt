package ro.safetyplease.ui.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_map_title
import ro.safetyplease.ui.resources.notice_evacuation
import ro.safetyplease.ui.resources.notice_heat
import ro.safetyplease.ui.resources.notice_in_zone
import ro.safetyplease.ui.resources.notice_info
import ro.safetyplease.ui.resources.notice_no_text
import ro.safetyplease.ui.resources.notice_other
import ro.safetyplease.ui.resources.notice_pause
import ro.safetyplease.ui.resources.notice_schedule
import ro.safetyplease.ui.resources.notice_transport

/** The words of a notice: what it is about, and its text from the event's templates. */
object NoticeText {
    /** What a notice is about; the heat notice takes its level as the argument. */
    fun title(topic: Int, level: Int): StringResource = when (topic) {
        NoticeTopic.ALERT -> when (level) {
            NoticeLevel.EVACUATION -> Res.string.notice_evacuation
            NoticeLevel.PAUSE -> Res.string.notice_pause
            else -> Res.string.notice_info
        }
        NoticeTopic.HEAT -> Res.string.notice_heat
        NoticeTopic.SCHEDULE -> Res.string.notice_schedule
        NoticeTopic.TRANSPORT -> Res.string.notice_transport
        NoticeTopic.MAP_STATE -> Res.string.control_map_title
        else -> Res.string.notice_other
    }

    /** The template filled in [language]; without it (another event's texts), the parameters alone; null for neither. */
    fun text(templates: NoticeTemplates, notice: Notice, language: String): String? =
        templates.render(notice, language) ?: notice.params.joinToString(" · ").ifEmpty { null }

    /** Text with no words of its own says so ([Res.string.notice_no_text]); one that names a zone goes after it ([Res.string.notice_in_zone]). */
    val noText: StringResource get() = Res.string.notice_no_text
    val inZone: StringResource get() = Res.string.notice_in_zone
}

@Composable
fun noticeTitle(notice: Notice): String = noticeTitle(notice.topic, notice.level)

@Composable
fun noticeTitle(topic: Int, level: Int): String = stringResource(NoticeText.title(topic, level), level)

@Composable
fun noticeBody(templates: NoticeTemplates, venue: Venue, notice: Notice, language: String): String {
    val text = NoticeText.text(templates, notice, language) ?: stringResource(NoticeText.noText)
    val inZone = if (notice.zone.isEmpty()) text else stringResource(NoticeText.inZone, venue.zoneName(notice.zone), text)
    return ControlNoticeText.body(notice, venue, text, ControlNoticeText.formatter()) ?: inZone
}
