package ro.safetyplease.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.protocol.IncidentCategory

/** Pictogramele aplicatiei: un singur set, desenat din linii cu capete rotunjite, aceeasi grosime peste tot. */
object AppIcons {
    private fun icon(name: String, vararg paths: String): Lazy<ImageVector> = lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (path in paths) {
                addPath(
                    pathData = addPathNodes(path), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    private const val RING = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0"

    val Chat by icon("chat", "M20.5 11.5a8 8 0 0 1-11.7 7.1L4 20l1.3-4.4A8 8 0 1 1 20.5 11.5Z")
    val Warning by icon("warning", "M12 4.2 3 19.5h18L12 4.2Z", "M12 10v4.3", "M12 17v.2")
    val Map by icon("map", "M9 4.5 3.5 6.5v13L9 17.5l6 2 5.5-2v-13L15 6.5l-6-2Z", "M9 4.5v13", "M15 6.5v13")
    val Person by icon("person", "M8.4 8a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0-7.2 0", "M4.8 20a7.2 7.2 0 0 1 14.4 0")
    val People by icon(
        "people", "M5.6 8a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0-6.8 0", "M2.8 19.5a6.2 6.2 0 0 1 12.4 0",
        "M16 4.8a3.4 3.4 0 0 1 0 6.4", "M17.8 14a6.2 6.2 0 0 1 3.4 5.5",
    )
    val Shield by icon("shield", "M12 3 4.5 6v5.5c0 4.6 3.1 8.3 7.5 9.5 4.4-1.2 7.5-4.9 7.5-9.5V6L12 3Z")
    val Back by icon("back", "M19 12H5.5", "M11 6l-6 6 6 6")
    val Send by icon("send", "M4 12 20 4.5l-5 15.5-3.4-6.6L4 12Z")
    val Place by icon("place", "M12 21s6.5-5.7 6.5-11.2a6.5 6.5 0 1 0-13 0C5.5 15.3 12 21 12 21Z", "M9.7 9.8a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0-4.6 0")
    val Check by icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val CheckCircle by icon("check_circle", RING, "M8.2 12.3l2.6 2.6 5-5.2")
    val Clock by icon("clock", RING, "M12 7.5V12l3 2")
    val Question by icon("question", RING, "M9.6 9.6a2.5 2.5 0 1 1 3.6 2.2c-.8.5-1.2 1-1.2 1.9", "M12 16.9v.2")
    val Info by icon("info", RING, "M12 11v5", "M12 7.8v.2")
    val Close by icon("close", "M6 6l12 12", "M18 6 6 18")
    val Add by icon("add", "M12 5v14", "M5 12h14")
    val QrCode by icon(
        "qr", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h2.5", "M20 14v2.5", "M14 17.5V20h2.5", "M20 19.9v.1",
    )
    val Camera by icon(
        "camera",
        "M4 8.5A1.5 1.5 0 0 1 5.5 7h2l1.5-2h6l1.5 2h2A1.5 1.5 0 0 1 20 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5v-9Z",
        "M8.8 13a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0-6.4 0",
    )
    val Bluetooth by icon("bluetooth", "M7 7.5l10 9-5 4.5V3l5 4.5-10 9")
    val Signal by icon(
        "signal", "M5 4.5h6A1.5 1.5 0 0 1 12.5 6v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z",
        "M15.5 9.5a4 4 0 0 1 0 5", "M18 7a7.5 7.5 0 0 1 0 10",
    )
    val Medical by icon("medical", "M9.5 4h5v5.5H20v5h-5.5V20h-5v-5.5H4v-5h5.5V4Z")
    val Fire by icon("fire", "M12 3c.8 3 4.8 4.9 4.8 9.3a4.8 4.8 0 0 1-9.6 0c0-1.7.7-3 1.6-4 .3 1.2 1 2 1.8 2.4C10.3 8.4 11 5.4 12 3Z")
    val Search by icon("search", "M4.8 11a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0-12.4 0", "M20 20l-4.4-4.4")
    val Report by icon("report", "M8.6 3.5h6.8l4.9 4.9v7.2l-4.9 4.9H8.6l-4.9-4.9V8.4l4.9-4.9Z", "M12 8v5", "M12 16v.2")
    val Block by icon("block", RING, "M6 6l12 12")
    val More by icon("more", RING, "M8 12v.01", "M12 12v.01", "M16 12v.01")
    val Anchor by icon("anchor", "M10 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M12 7v14", "M8 11h8", "M4.5 13.5c0 4 3.4 7.5 7.5 7.5s7.5-3.5 7.5-7.5")
    val Share by icon("share", "M12 15V4", "M8 8l4-4 4 4", "M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13")
    val Bug by icon(
        "bug", "M8.5 9a3.5 3.5 0 0 1 7 0v6a3.5 3.5 0 0 1-7 0V9Z", "M5 9l3.5 1.5", "M19 9l-3.5 1.5", "M4.5 14h4", "M15.5 14h4",
        "M5.5 19l3.3-2", "M18.5 19l-3.3-2",
    )
    val Delete by icon("delete", "M5 7h14", "M9.5 7V4.5h5V7", "M7 7l.8 12a1.5 1.5 0 0 0 1.5 1.4h5.4a1.5 1.5 0 0 0 1.5-1.4L17 7")
    val MyLocation by icon(
        "my_location", "M4.5 12a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0-15 0", "M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        "M12 2.5v2", "M12 19.5v2", "M2.5 12h2", "M19.5 12h2",
    )
    val Copy by icon(
        "copy", "M10.5 9h7A1.5 1.5 0 0 1 19 10.5v8a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 9 18.5v-8A1.5 1.5 0 0 1 10.5 9Z",
        "M15 6.5v-1A1.5 1.5 0 0 0 13.5 4h-7A1.5 1.5 0 0 0 5 5.5v8A1.5 1.5 0 0 0 6.5 15H7",
    )
    val Retry by icon("retry", "M19.5 12a7.5 7.5 0 1 1-2.6-5.7", "M19.5 4.5v4h-4")
    val ChevronRight by icon("chevron_right", "M9 6l6 6-6 6")
    val ChevronDown by icon("chevron_down", "M6 9l6 6 6-6")
    val Edit by icon("edit", "M4.5 19.5l1-4L16 5l3 3L8.5 18.5l-4 1Z", "M14 7l3 3")
    val Battery by icon(
        "battery", "M4 9.5A1.5 1.5 0 0 1 5.5 8h11A1.5 1.5 0 0 1 18 9.5v5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 14.5v-5Z",
        "M21 11v2", "M7.5 11v2",
    )
    val Bell by icon("bell", "M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 2h-14l1.5-2Z", "M10 20.5a2 2 0 0 0 4 0")
    val Flag by icon("flag", "M6 21V4", "M6 4.5h11l-2.2 4 2.2 4H6")
    val Leave by icon("leave", "M14 5h3.5A1.5 1.5 0 0 1 19 6.5v11a1.5 1.5 0 0 1-1.5 1.5H14", "M10 8l-4 4 4 4", "M6 12h9")

    fun category(category: Int): ImageVector = when (category) {
        IncidentCategory.MEDICAL -> Medical
        IncidentCategory.VIOLENCE -> Report
        IncidentCategory.LOST_PERSON -> Search
        IncidentCategory.HARASSMENT -> Block
        IncidentCategory.CROWD -> People
        IncidentCategory.FIRE -> Fire
        else -> More
    }
}
