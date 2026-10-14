package ro.safetyplease.app.platform.location

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import okio.Path
import okio.Path.Companion.toOkioPath
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.service.MeshService
import ro.safetyplease.core.location.GnssSummary
import ro.safetyplease.ui.me.GnssView

/** The location source's diagnostics for the hidden panel; a measuring mode keeps the GPS on as a share does. */
class AndroidGnss(private val context: Context, container: AppContainer) : GnssView {
    private val location = container.location
    private val prewarm = container.gnssPrewarm

    override val summary: StateFlow<GnssSummary> = location.diagnostics.summary
    override val l5Device: StateFlow<Boolean?> = location.diagnostics.l5Device
    override val mode: StateFlow<Long?> = location.shareMaxMs
    override val searching: StateFlow<Boolean> = location.searching
    override val online: StateFlow<Boolean> = prewarm.online

    override fun setMode(intervalMs: Long?) {
        location.share(intervalMs)
        MeshService.shareLocation(context, intervalMs != null)
    }

    /** The orbits and the time now, whatever the last time was, and the chip on without a break for a while. */
    override fun prepareOffline() {
        prewarm.inject(force = true)
        location.warm(PREPARE_MS)
    }

    override fun writeCsv(dir: Path, nowMs: Long): Path =
        location.diagnostics.writeCsv(dir.toFile(), nowMs, TimeZone.currentSystemDefault()).toOkioPath()

    private companion object {
        /** A satellite sends its own orbit every 30 s: three rounds of it, while the predicted ones come down. */
        const val PREPARE_MS = 90_000L
    }
}
