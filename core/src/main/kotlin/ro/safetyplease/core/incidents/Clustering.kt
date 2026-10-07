package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus

/** Mai multe rapoarte despre acelasi lucru: aceeasi categorie si zona, la cel mult [Clustering.WINDOW_MS] distanta. */
class IncidentCluster(val incidents: List<StaffIncident>) {
    val lead: StaffIncident = incidents.maxWith(compareBy<StaffIncident> { it.severity }.thenBy { it.reportedAt })
    val count: Int get() = incidents.size
    val severity: Int = incidents.maxOf { it.severity }

    /** Cardul arata starea celui mai putin avansat raport: atat timp cat unul e nepreluat, grupul cere atentie. */
    val status: Int = incidents.minOf { it.status }
    val latestAt: Long = incidents.maxOf { it.reportedAt }
}

/** [ACTIVE]: tot ce nu e inchis inca (nepreluat sau preluat), ce are staff-ul de facut. */
enum class StatusFilter { ALL, OPEN, TAKEN, ACTIVE, RESOLVED }

object Clustering {
    const val WINDOW_MS = 5 * 60_000L

    fun cluster(incidents: List<StaffIncident>): List<IncidentCluster> {
        val clusters = mutableListOf<MutableList<StaffIncident>>()
        val sorted = incidents.sortedBy { it.reportedAt }
        // fara zona nu stim daca e acelasi loc, deci raportul ramane singur
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
