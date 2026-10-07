package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus

/** Several reports about the same thing: same category and zone, at most [Clustering.WINDOW_MS] apart. */
class IncidentCluster(val incidents: List<StaffIncident>) {
    val lead: StaffIncident = incidents.maxWith(compareBy<StaffIncident> { it.severity }.thenBy { it.reportedAt })
    val count: Int get() = incidents.size
    val severity: Int = incidents.maxOf { it.severity }

    /** The card shows the least advanced report's status: while one is unassigned, the group needs attention. */
    val status: Int = incidents.minOf { it.status }
    val latestAt: Long = incidents.maxOf { it.reportedAt }
}

/** [ACTIVE]: everything not closed yet (unassigned or taken), i.e. what staff still has to do. */
enum class StatusFilter { ALL, OPEN, TAKEN, ACTIVE, RESOLVED }

object Clustering {
    const val WINDOW_MS = 5 * 60_000L

    fun cluster(incidents: List<StaffIncident>): List<IncidentCluster> {
        val clusters = mutableListOf<MutableList<StaffIncident>>()
        val sorted = incidents.sortedBy { it.reportedAt }
        // without a zone we can't tell it's the same place, so the report stays on its own
        val (located, unlocated) = sorted.partition { it.zone.isNotEmpty() }
        for ((_, group) in located.groupBy { it.category to it.zone }) {
            var current = mutableListOf<StaffIncident>()
            for (incident in group) {
                if (current.isNotEmpty() && incident.reportedAt - current.first().reportedAt > WINDOW_MS) {
                    clusters += current
                    current = mutableListOf()
                }
                current += incident
            }
            if (current.isNotEmpty()) clusters += current
        }
        unlocated.forEach { clusters += mutableListOf(it) }
        return clusters.map(::IncidentCluster)
            .sortedWith(compareByDescending<IncidentCluster> { it.severity }.thenByDescending { it.latestAt })
    }

    fun matches(cluster: IncidentCluster, filter: StatusFilter): Boolean = when (filter) {
        StatusFilter.ALL -> true
        StatusFilter.OPEN -> cluster.status <= AckStatus.RECEIVED
        StatusFilter.TAKEN -> cluster.status == AckStatus.ACKNOWLEDGED
        StatusFilter.ACTIVE -> cluster.status < AckStatus.RESOLVED
        StatusFilter.RESOLVED -> cluster.status >= AckStatus.RESOLVED
    }
}
