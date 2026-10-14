package ro.safetyplease.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How a zone shows its name on the map: icon over name, icon beside name, just the icon, just the name, or nothing. */
enum class ZoneLabelMode { Stacked, Beside, IconOnly, TextOnly, None }

/** A zone name as measured for the room it has: its size, and whether it fit whole, with no ellipsis and no word cut in two. */
class MeasuredName(val width: Float, val height: Float, val clean: Boolean)

/** Below this much room a zone gets only its icon: a name squeezed into a narrow zone wraps badly and reads worse than none. */
val ZoneLabelMinWidth: Dp = 44.dp

/**
 * Picks what a zone shows for [roomWidth] by [roomHeight]; [icon] is the badge's diameter (0 without one) and [gap] the
 * space between it and the name. A name that doesn't fit whole is never shown cut: the zone falls back to its icon.
 */
fun chooseZoneLabel(
    roomWidth: Float,
    roomHeight: Float,
    icon: Float,
    gap: Float,
    minWidth: Float,
    stacked: MeasuredName,
    beside: MeasuredName,
): ZoneLabelMode {
    val hasIcon = icon > 0f
    val fallback = if (hasIcon) ZoneLabelMode.IconOnly else ZoneLabelMode.None
    return when {
        roomWidth < minWidth -> fallback
        hasIcon && stacked.clean && icon + gap + stacked.height <= roomHeight -> ZoneLabelMode.Stacked
        hasIcon && beside.clean && maxOf(icon, beside.height) <= roomHeight -> ZoneLabelMode.Beside
        !hasIcon && stacked.clean && stacked.height <= roomHeight -> ZoneLabelMode.TextOnly
        else -> fallback
    }
}

/** True when a line of the wrapped [text] ends inside a word; [lineEnds] are the end offsets of its lines. */
fun breaksWord(text: String, lineEnds: List<Int>): Boolean =
    lineEnds.dropLast(1).any { end -> end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() && text[end - 1] != '-' }

/**
 * Where a block of [size] goes: as close to [preferred] as it can, inside [within] and clear of every rectangle in [avoid];
 * null if there is no such place.
 */
fun placeClear(size: Size, preferred: Offset, within: Rect, avoid: List<Rect>): Offset? {
    if (size.width > within.width || size.height > within.height) return null
    fun fit(o: Offset) = Offset(o.x.coerceIn(within.left, within.right - size.width), o.y.coerceIn(within.top, within.bottom - size.height))
    fun clear(o: Offset) = avoid.none { Rect(o, size).overlaps(it) }
    val start = fit(preferred)
    if (clear(start)) return start
    return avoid.filter { Rect(start, size).overlaps(it) }
        .flatMap { r ->
            listOf(Offset(start.x, r.top - size.height), Offset(start.x, r.bottom), Offset(r.left - size.width, start.y), Offset(r.right, start.y))
        }
        .map(::fit)
        .filter(::clear)
        .minByOrNull { (it - start).getDistance() }
}

enum class MapCorner(val atEnd: Boolean, val atBottom: Boolean) {
    TopStart(false, false),
    BottomStart(false, true),
    BottomEnd(true, true),
}

/** A corner of the map taken by a control drawn over it, so the map keeps names and its scale out from under it. */
class MapReserve(val corner: MapCorner, val width: Dp, val height: Dp)

/** The rectangle of [width] by [height] in [corner] of a [canvas], [marginX] and [marginY] in from the edges. */
fun cornerRect(corner: MapCorner, canvas: Size, width: Float, height: Float, marginX: Float = 0f, marginY: Float = 0f): Rect {
    val left = if (corner.atEnd) canvas.width - marginX - width else marginX
    val top = if (corner.atBottom) canvas.height - marginY - height else marginY
    return Rect(left, top, left + width, top + height)
}

/**
 * The first of [candidates] that overlaps none of [avoid]; if all do, the free spot within [within] nearest to the first
 * one; failing that, the first one.
 */
fun firstFree(candidates: List<Rect>, avoid: List<Rect>, within: Rect? = null): Rect {
    candidates.firstOrNull { c -> avoid.none { c.overlaps(it) } }?.let { return it }
    val first = candidates.first()
    return within?.let { placeClear(first.size, first.topLeft, it, avoid) }?.let { Rect(it, first.size) } ?: first
}
