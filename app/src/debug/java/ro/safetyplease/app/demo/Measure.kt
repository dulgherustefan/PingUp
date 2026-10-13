package ro.safetyplease.app.demo

import android.bluetooth.BluetoothDevice
import ro.safetyplease.core.ble.Advert
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.util.toHex

/**
 * One row of the field CSV, in long format: every row has all the columns and fills the ones its event uses.
 * Events: info (the phone, at the start), adv (one raw scan result), phy (a link's PHY), test_sent, test_ping and
 * test_echo (TEST packets), stat (one counter: battery, scan responses, the engine's counters).
 */
class FieldRow(
    val event: String,
    val peer: String = "",
    val address: String = "",
    val rssi: Int? = null,
    val phyTx: String = "",
    val phyRx: String = "",
    val seq: Long? = null,
    val hops: Int? = null,
    val hopsBack: Int? = null,
    val rttMs: Long? = null,
    val name: String = "",
    val value: String = "",
) {
    fun csv(wallMs: Long, elapsedMs: Long): String = listOf(
        wallMs, elapsedMs, event, peer, address, rssi ?: "", phyTx, phyRx, seq ?: "", hops ?: "", hopsBack ?: "", rttMs ?: "",
        name, value.replace(',', ' ').replace('\n', ' '),
    ).joinToString(",")

    companion object {
        const val HEADER = "wall_ms,t_ms,event,peer,address,rssi,phy_tx,phy_rx,seq,hops,hops_back,rtt_ms,name,value"

        fun stat(name: String, value: Any) = FieldRow("stat", name = name, value = value.toString())

        fun info(name: String, value: Any) = FieldRow("info", name = name, value = value.toString())
    }
}

fun phyName(phy: Int): String = when (phy) {
    BluetoothDevice.PHY_LE_1M -> "1M"
    BluetoothDevice.PHY_LE_2M -> "2M"
    BluetoothDevice.PHY_LE_CODED -> "coded"
    else -> phy.toString()
}

/** The engine's counters, written in every stat round. New MeshState counters go here. */
fun meshStats(state: MeshState): List<FieldRow> = listOf(
    FieldRow.stat("mesh_sent", state.sent),
    FieldRow.stat("mesh_received", state.received),
    FieldRow.stat("mesh_relayed", state.relayed),
    FieldRow.stat("mesh_dropped", state.dropped),
    FieldRow.stat("mesh_queue_full", state.queueFull),
    FieldRow.stat("mesh_refused_banned", state.counters.refusedBanned),
    FieldRow.stat("mesh_store_and_forward", state.counters.storeAndForward),
    FieldRow.stat("mesh_links_opened", state.counters.linksOpened),
    FieldRow.stat("mesh_links_closed", state.counters.linksClosed),
    FieldRow.stat("cached_reports", state.cachedReports),
    FieldRow.stat("links", state.readyLinks),
    FieldRow.stat("visible_peers", state.visiblePeers),
) + state.counters.drops.entries.sortedBy { it.key }.map { (reason, n) -> FieldRow.stat("drop_" + reason.name.lowercase(), n) } +
    state.counters.reportHops.entries.sortedBy { it.key }.map { (hops, n) -> FieldRow.stat("report_hops_$hops", n) } +
    state.counters.ackHops.entries.sortedBy { it.key }.map { (hops, n) -> FieldRow.stat("ack_hops_$hops", n) }

/** The field recording's side of the scan, fed from the callback thread: raw results and the scan response rate. */
class FieldScan {
    private val prefixes = HashMap<String, Int>()
    private var results = 0
    private var withoutResponse = 0

    /** A result without scan response gets the prefix its address showed last. */
    @Synchronized
    fun row(advert: Advert): FieldRow {
        advert.prefix?.let { prefixes[advert.address] = it }
        if (!advert.coded) {
            results++
            if (advert.prefix == null) withoutResponse++
        }
        val peer = (advert.prefix ?: prefixes[advert.address])?.toHex().orEmpty()
        return FieldRow("adv", peer = peer, address = advert.address, rssi = advert.rssi, phyRx = if (advert.coded) "coded" else "1M")
    }

    /** Legacy results since the last call and how many of them came without a scan response, like BleRadio's log. */
    @Synchronized
    fun drain(): List<FieldRow> {
        val rows = listOf(FieldRow.stat("scan_results", results), FieldRow.stat("scan_without_response", withoutResponse))
        results = 0
        withoutResponse = 0
        return rows
    }
}

/**
 * What an unfiltered scan hears in one window (a second): distinct advertisers, advertisements, and their RSSI
 * in 10 dB buckets. Fed from the scan callback thread.
 */
class CensusCounter {
    private val addresses = HashSet<String>()
    private var adverts = 0
    private var histogram = IntArray(BUCKETS)

    @Synchronized
    fun heard(address: String, rssi: Int) {
        addresses += address
        adverts++
        histogram[bucket(rssi)]++
    }

    /** The window's cells after the two timestamps; the next window starts empty. */
    @Synchronized
    fun drain(): String {
        val cells = (listOf(addresses.size, adverts) + histogram.toList()).joinToString(",")
        addresses.clear()
        adverts = 0
        histogram = IntArray(BUCKETS)
        return cells
    }

    companion object {
        private const val BUCKETS = 9

        /** rssi_X counts X <= RSSI < X + 10, with an open bucket at each end. */
        val HEADER = "wall_ms,t_ms,advertisers,adverts,rssi_lt_-100," + (-100..-40 step 10).joinToString(",") { "rssi_$it" } + ",rssi_ge_-30"

        fun bucket(rssi: Int): Int = when {
            rssi < -100 -> 0
            rssi >= -30 -> BUCKETS - 1
            else -> 1 + (rssi + 100) / 10
        }
    }
}
