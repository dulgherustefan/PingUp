package ro.safetyplease.ui.ios

import okio.FileSystem
import okio.Path
import platform.Foundation.NSData
import platform.Foundation.writeToFile
import platform.MetricKit.MXDiagnosticPayload
import platform.MetricKit.MXMetricManager
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricPayload
import platform.darwin.NSObject
import ro.safetyplease.ui.text.wallNowMs
import kotlin.concurrent.Volatile

/**
 * What iOS itself reports on the app (MetricKit): crashes, hangs and the app ended in the background, with their
 * stacks, and once a day its energy, launch and memory figures. They stay on the phone, the latest [KEEP] of them, and
 * leave only with the pilot's metrics, exported by the person ([IosPhoneUi.diagnosticFiles]): nothing goes to us.
 */
object IosDiagnostics {
    private const val KEEP = 30

    @Volatile private var dir: Path? = null

    // held here as long as the process lives
    private var subscriber: Subscriber? = null

    /** Once per process, with the app's private folder: MetricKit hands over what it has soon after. */
    fun start(filesDir: Path) {
        if (subscriber != null) return
        val folder = filesDir / "diagnostics"
        dir = folder
        subscriber = Subscriber { kind, reports -> keep(folder, kind, reports) }.also { MXMetricManager.sharedManager.addSubscriber(it) }
    }

    /** The reports kept, oldest first. */
    fun files(): List<Path> = dir?.let { files(it) }.orEmpty()

    /** Called by MetricKit on a thread of its own. */
    private fun keep(folder: Path, kind: String, reports: List<NSData>) {
        if (reports.isEmpty()) return
        val fs = FileSystem.SYSTEM
        fs.createDirectories(folder)
        val stamp = wallNowMs()
        reports.forEachIndexed { i, report -> report.writeToFile((folder / "ios-$stamp-$kind-$i.json").toString(), atomically = true) }
        files(folder).dropLast(KEEP).forEach { fs.delete(it) }
    }

    private fun files(folder: Path): List<Path> =
        FileSystem.SYSTEM.listOrNull(folder).orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }

    private class Subscriber(private val keep: (String, List<NSData>) -> Unit) : NSObject(), MXMetricManagerSubscriberProtocol {
        override fun didReceiveMetricPayloads(payloads: List<*>) =
            keep("metrics", payloads.mapNotNull { (it as? MXMetricPayload)?.JSONRepresentation() })

        override fun didReceiveDiagnosticPayloads(payloads: List<*>) =
            keep("diagnostics", payloads.mapNotNull { (it as? MXDiagnosticPayload)?.JSONRepresentation() })
    }
}
