package ro.safetyplease.core.position

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.OutgoingShare
import ro.safetyplease.core.data.PositionData
import ro.safetyplease.core.protocol.SharePrecision

private const val MINUTE_MS = 60_000L

enum class ShareLength { MIN_15, HOUR_1, FESTIVAL_DAY, UNTIL_STOPPED }

/** The choice made when a switch goes on: nothing is picked until the user picks it. */
data class ShareChoice(val length: ShareLength? = null, val precision: Int? = null) {
    val ready: Boolean get() = length != null && precision != null

    companion object {
        val PRECISIONS = listOf(SharePrecision.EXACT, SharePrecision.APPROXIMATE, SharePrecision.ZONE)

        /** Marked as the suggestion, so the restrictive option is the one in view; still not selected. */
        val SUGGESTED_LENGTH = ShareLength.HOUR_1
        const val SUGGESTED_PRECISION = SharePrecision.APPROXIMATE
    }
}

/**
 * What the user asked for one conversation: until [endAtMs], or until they stop it when null. A mesh session lasts at
 * most two hours, so a longer plan is carried by renewals.
 */
@Serializable
data class SharePlan(val conversation: String, val endAtMs: Long?, val startedAtMs: Long, val remindedAtMs: Long = 0)

object ShareTime {
    const val DAY_MS = 24 * 60 * MINUTE_MS
    const val RENEW_AHEAD_MS = 10 * MINUTE_MS

    /** "partajezi locația cu N prieteni", every 4 hours of a share without an end. */
    const val REMINDER_MS = 4 * 60 * MINUTE_MS

    /** Null for "până opresc". The festival day ends where the next one starts, counted from the pack's day start. */
    fun endAt(length: ShareLength, nowMs: Long, dayStartMs: Long): Long? = when (length) {
        ShareLength.MIN_15 -> nowMs + 15 * MINUTE_MS
        ShareLength.HOUR_1 -> nowMs + 60 * MINUTE_MS
        ShareLength.FESTIVAL_DAY -> dayStartMs + (Math.floorDiv(nowMs - dayStartMs, DAY_MS) + 1) * DAY_MS
        ShareLength.UNTIL_STOPPED -> null
    }

    /** The mesh session to ask for: what is left of the plan, from 1 to 120 minutes. */
    fun sessionMinutes(endAtMs: Long?, nowMs: Long): Int {
        val end = endAtMs ?: return PositionData.MAX_SHARE_MIN
        return ((end - nowMs + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceIn(1, PositionData.MAX_SHARE_MIN)
    }

    /** The session ends within [RENEW_AHEAD_MS] while the plan goes on past it. */
    fun needsRenew(plan: SharePlan, sessionEndsAtMs: Long, nowMs: Long): Boolean =
        (plan.endAtMs == null || plan.endAtMs > sessionEndsAtMs) && sessionEndsAtMs - nowMs <= RENEW_AHEAD_MS

    fun reminderDue(plan: SharePlan, nowMs: Long): Boolean =
        plan.endAtMs == null && nowMs - maxOf(plan.startedAtMs, plan.remindedAtMs) >= REMINDER_MS

    /** When the share ends for the user: the plan's end, or the session's without a plan; null for "până opresc". */
    fun endsAt(plan: SharePlan?, sessionEndsAtMs: Long): Long? = if (plan == null) sessionEndsAtMs else plan.endAtMs
}

/** One conversation this phone shares with now: [endsAtMs] null for "până opresc". */
data class MyShare(val conversation: String, val precision: Int, val endsAtMs: Long?, val recipients: List<Long>)

/** Everything shared now, for the bar, the notification and "Cine mă vede acum". */
data class SharingNow(val shares: List<MyShare>) {
    /** The people who see this phone, each counted once across friends and groups. */
    val people: Int get() = shares.flatMap { it.recipients }.distinct().size

    /** When the last share ends; null while one runs until stopped. */
    val endsAtMs: Long? get() = if (shares.any { it.endsAtMs == null }) null else shares.maxOfOrNull { it.endsAtMs ?: 0L }

    companion object {
        /** Null when nothing runs at [nowMs]. [recipients] gives who a conversation reaches, me excluded. */
        fun of(active: List<OutgoingShare>, plans: List<SharePlan>, recipients: (String) -> List<Long>, nowMs: Long): SharingNow? {
            val shares = active.filter { it.expiresAtMs > nowMs }.map { share ->
                MyShare(share.conversation, share.precision, ShareTime.endsAt(plans.firstOrNull { it.conversation == share.conversation }, share.expiresAtMs), recipients(share.conversation))
            }
            return if (shares.isEmpty()) null else SharingNow(shares)
        }

        /** The plans still worth keeping: their share runs, or was asked for a moment ago and the mesh hasn't recorded it yet. */
        fun keptPlans(plans: List<SharePlan>, active: List<OutgoingShare>, nowMs: Long): List<SharePlan> = plans.filter { plan ->
            val running = active.any { it.conversation == plan.conversation && it.expiresAtMs > nowMs }
            (running || nowMs - plan.startedAtMs < START_GRACE_MS) && (plan.endAtMs == null || plan.endAtMs > nowMs)
        }

        const val START_GRACE_MS = 60_000L
    }
}
