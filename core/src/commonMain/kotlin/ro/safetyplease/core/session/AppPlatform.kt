package ro.safetyplease.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import okio.Path
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeAlarm
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.MotionSample
import ro.safetyplease.core.incidents.ResponderTarget
import ro.safetyplease.core.mesh.BatteryReading
import ro.safetyplease.core.mesh.PowerPlan
import ro.safetyplease.core.mesh.Radio
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.safety.SafetyNotice
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.Venue

/**
 * What a [PingUpSession] needs from the phone it runs on; the Android app and the iPhone app each give their own.
 * The session calls these from its own threads: the mesh's single thread, or the one for files.
 */
interface AppPlatform {
    val crypto: Crypto
    val clock: Clock

    /** Where the app keeps its files; [cacheDir] holds what the system may delete, like a shared export. */
    val filesDir: Path
    val cacheDir: Path

    /** The phone's identity and staff keys, sealed by the system's key store. */
    val vault: Vault

    /** A debug build: packets go to the log and the nickname into HELLO. */
    val debug: Boolean

    /** The language the app is shown in, "ro" or "en": venue names and notices come in it. */
    fun language(): String

    /** A file the app ships with: venue.json, notices.json, staff_public.json. */
    fun asset(name: String): String

    /** The files of a folder the app ships with, by name: firstaid, pages; empty if there's no such folder. */
    fun assets(folder: String): Map<String, String>

    fun log(message: String)

    /** The Bluetooth radio, once the session knows its node id; [scope] is the mesh's single thread. */
    fun radio(scope: CoroutineScope, nodeId: Long): MeshRadio

    /** The notifications for one event; in an exercise, none of them sounds an alarm. */
    fun notifications(venue: Venue, exercise: Boolean): Notifications

    /** The phone's last position; null without permission or a fix. */
    val location: StateFlow<GeoFix?>

    /**
     * A location share runs and wants a fix at least every [maxIntervalMs]; null once none runs. Android keeps the GPS
     * on for it with the screen off; the iPhone app shares only what it has while it's in front.
     */
    fun shareLocation(maxIntervalMs: Long?)

    /** Hands [onReading] each reading of the battery, the current one first, for as long as the app runs. */
    fun watchBattery(onReading: (BatteryReading) -> Unit)

    /**
     * Hands [onSample] the accelerometer's readings, from any thread, until called again with null: staff man-down.
     * A phone that can't keep it running in the background, the iPhone, ignores it.
     */
    fun watchMotion(onSample: ((MotionSample) -> Unit)?) = Unit

    /** [watchMotion] does something here. */
    val watchesMotion: Boolean get() = false

    /** The mobile network now, for staff who keep the network log; [cell] is the grid square of a fresh position. */
    suspend fun sampleMobileNetwork(atMs: Long, cell: String): NetworkSample

    /** A notice's title and text, in the app's language, for its notification and the console's log. */
    fun noticeTitle(notice: Notice): String

    fun noticeBody(templates: NoticeTemplates, venue: Venue, notice: Notice): String

    /** What an https link holds, as an organiser publishes an event pack: at most [limit] bytes; null if it can't be had. */
    suspend fun download(url: String, limit: Long): ByteArray?

    /** Starts the app again with a new session, to load the event just chosen. Files are flushed by then. */
    fun restart()

    /** Deletes everything the app keeps and closes it, as clearing the app's data in the system's settings does. */
    fun wipe()
}

/** Small secrets kept by the system's key store: the Android Keystore, the iOS Keychain. */
interface Vault {
    fun load(name: String): ByteArray?

    fun save(name: String, secret: ByteArray)

    fun delete(name: String)

    fun exists(name: String): Boolean

    /** Keeps an entry that can no longer be opened under another name, so a new one doesn't overwrite it. */
    fun setAside(name: String)
}

/** What the session tells the person when the app isn't in front of them. */
interface Notifications {
    fun chatMessage(message: ChatMessage, senderName: String)

    /** The conversation is on screen: its notification goes. */
    fun cancelConversation(conversation: String)

    fun staffAlert(post: AlertPost)

    fun cancelStaffAlert(incidentId: String)

    fun reportUpdate(report: MyReport, nowMs: Long)

    fun reportResponse(report: MyReport)

    /** [alarm] says whether it takes the screen and sounds, for this phone. */
    fun notice(notice: Notice, title: String, text: String, alarm: NoticeAlarm)

    fun shiftEnded()

    /** Every few hours of a location share without an end: it still runs, with [people] seeing this phone. */
    fun shareReminder(people: Int)

    /** "Amintește-mi": [set] starts soon on [stage], and the walk there takes [walkMin] minutes (0 when unknown). */
    fun setReminder(set: ScheduleItem, stage: String, walkMin: Int)

    /** Someone doesn't breathe nearby: the alert names the defibrillator to fetch on the way. */
    fun responderAlert(post: AlertPost, target: ResponderTarget?) = staffAlert(post)

    /**
     * "Ești OK?" after a suspected fall, with sound and vibration until [untilMs] (wall time) or an answer. False when
     * it couldn't be posted (notifications not allowed, or a phone without man-down): then only the open app asks.
     */
    fun manDownCheck(untilMs: Long): Boolean = false

    fun cancelManDownCheck() = Unit

    /** The check-in on the way, and a friend's missed one; [SafetyNotice.CheckinCleared] takes the check-in's notice away. */
    fun safety(notice: SafetyNotice)
}

/** The Bluetooth radio as the session drives it: on while the mesh runs, at the power the plan asks for. */
interface MeshRadio : Radio {
    fun start()

    fun stop()

    fun setPower(plan: PowerPlan)

    /** Tries again to come up, after the person granted Bluetooth or turned it on. */
    fun retry()
}
