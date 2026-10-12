package ro.safetyplease.app.text

import android.content.res.Resources
import ro.safetyplease.app.R
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.venue.Venue

/** The words of a notice: what it is about, and its text from the event's templates. */
object NoticeText {
    fun title(res: Resources, topic: Int, level: Int): String = when (topic) {
        NoticeTopic.ALERT -> res.getString(
            when (level) {
                NoticeLevel.EVACUATION -> R.string.notice_evacuation
                NoticeLevel.PAUSE -> R.string.notice_pause
                else -> R.string.notice_info
            },
        )
        NoticeTopic.HEAT -> res.getString(R.string.notice_heat, level)
        NoticeTopic.SCHEDULE -> res.getString(R.string.notice_schedule)
        NoticeTopic.TRANSPORT -> res.getString(R.string.notice_transport)
        else -> res.getString(R.string.notice_other)
    }

    fun title(res: Resources, notice: Notice): String = title(res, notice.topic, notice.level)

    /**
     * The template filled in [language], after the zone when it names one; without the template (another event's
     * texts), the parameters alone.
     */
    fun body(res: Resources, templates: NoticeTemplates, venue: Venue, notice: Notice, language: String): String {
        val text = templates.render(notice, language) ?: notice.params.joinToString(" · ").ifEmpty { res.getString(R.string.notice_no_text) }
        return if (notice.zone.isEmpty()) text else res.getString(R.string.notice_in_zone, venue.zoneName(notice.zone), text)
    }
}
