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

    override val summary: StateFlow<GnssSummary> = location.diagnostics.summary
    override val l5Device: StateFlow<Boolean?> = location.diagnostics.l5Device
    override val mode: StateFlow<Long?> = location.shareMaxMs
    override val searching: StateFlow<Boolean> = location.searching
    override val online: StateFlow<Boolean> = container.gnssPrewarm.online

    override fun setMode(intervalMs: Long?) {
        location.share(intervalMs)
        MeshService.shareLocation(context, intervalMs != null)
    }

    override fun writeCsv(dir: Path, nowMs: Long): Path =
        location.diagnostics.writeCsv(dir.toFile(), nowMs, TimeZone.currentSystemDefault()).toOkioPath()
}
