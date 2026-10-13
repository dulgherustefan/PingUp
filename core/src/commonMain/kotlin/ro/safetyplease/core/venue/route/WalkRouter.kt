package ro.safetyplease.core.venue.route

import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.distanceMeters
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Walking routes on the site's [WalkGrid], offline: A* over eight neighbours, never through water, a fence (but at a
 * gate) or a blocked area. The console's [MapState] shuts closed zones, except the one the walk starts in, and makes
 * crowded ones cost [CROWD_COST] times as much. The grids are built on first use, one for the public and one for staff.
 */
class WalkRouter(val venue: Venue) {
    private val publicGrid by lazy { WalkGrid(venue, staff = false) }
    private val staffGrid by lazy { WalkGrid(venue, staff = true) }
    private val landmarks = venue.pois.filter { it.number in Venue.LANDMARK_NUMBERS }

    fun grid(staff: Boolean): WalkGrid = if (staff) staffGrid else publicGrid

    /** Whether [point] is on the routes' map: off it, a phone is outside the site. */
    fun covers(point: GeoPoint): Boolean = publicGrid.cellAt(point) >= 0

    /** The walk from [from] to [to], each moved to the nearest walkable cell within [SNAP_M]; null when none joins them. */
    fun route(from: GeoPoint, to: GeoPoint, state: MapState = MapState.OPEN, staff: Boolean = false): Route? {
        val grid = grid(staff)
        val costs = Costs(grid, state, grid.cellAt(from))
        val start = snap(grid, costs, from)
        val goal = snap(grid, costs, to)
        if (start < 0 || goal < 0) return null
        val search = Search(grid, costs)
        if (search.run(start, goal, null) < 0) return null
        return build(grid, costs, search.path(goal), from, to)
    }

    /**
     * The place of [type] nearest by walking that isn't closed: neither in a closed zone nor, for a numbered point, in
     * [closedPoints] (the points staff marked closed or out of service). Water, wc, medical, exit, charge, dea, meeting...
     */
    fun nearestOpen(
        from: GeoPoint,
        type: String,
        state: MapState = MapState.OPEN,
        staff: Boolean = false,
        closedPoints: Set<Int> = emptySet(),
    ): Destination? {
        val grid = grid(staff)
        val costs = Costs(grid, state, grid.cellAt(from))
        val start = snap(grid, costs, from)
        if (start < 0) return null
        val candidates = candidates(type, state, closedPoints)
        val goals = IntArray(grid.size) { -1 }
        candidates.forEachIndexed { index, candidate ->
            val cell = snap(grid, costs, candidate.point)
            if (cell >= 0 && goals[cell] < 0) goals[cell] = index
        }
        val search = Search(grid, costs)
        val reached = search.run(start, -1, goals)
        if (reached < 0) return null
        val place = candidates[goals[reached]]
        return Destination(type, place.name, place.number, place.point, build(grid, costs, search.path(reached), from, place.point))
    }

    /**
     * Metres of walking, with the map state and the access read at each call; in a straight line when a point is off
     * the grid, null when no walk joins the two.
     */
    fun distance(state: () -> MapState, staff: () -> Boolean): WalkDistance = WalkDistance { from, to ->
        if (covers(from) && covers(to)) route(from, to, state(), staff())?.meters else StraightLine.meters(from, to)
    }

    private class Candidate(val name: String, val number: Int, val point: GeoPoint)

    private fun candidates(type: String, state: MapState, closedPoints: Set<Int>): List<Candidate> {
        fun closedAt(point: GeoPoint) = venue.zoneAt(point.lat, point.lon)?.let { state.state(it.id) == ZoneState.CLOSED } == true
        val points = if (type == Venue.MEETING) {
            listOfNotNull(venue.meetingPoint?.let { Candidate("", 0, it) })
        } else {
            venue.pois.filter { it.type == type && (it.number == 0 || it.number !in closedPoints) }.map { Candidate(it.name, it.number, it.point) }
        }
        val zones = venue.zones.filter { it.type == type && state.state(it.id) != ZoneState.CLOSED }.map { Candidate(it.name, 0, it.center) }
        return zones + points.filterNot { closedAt(it.point) }
    }

    /** The nearest cell to [point] that can be walked, within [SNAP_M]; -1 for none, or off the grid. */
    private fun snap(grid: WalkGrid, costs: Costs, point: GeoPoint): Int {
        val cell = grid.cellAt(point)
        if (cell < 0 || costs.of(cell) < SHUT) return cell
        val px = grid.x(point)
        val py = grid.y(point)
        val reach = SNAP_M / grid.cellM
        val col = cell % grid.cols
        val row = cell / grid.cols
        val span = ceil(reach).toInt()
        var best = -1
        var bestDistance = reach * reach
        for (r in max(0, row - span)..min(grid.rows - 1, row + span)) {
            for (c in max(0, col - span)..min(grid.cols - 1, col + span)) {
                val k = r * grid.cols + c
                if (costs.of(k) == SHUT) continue
                val dx = c + 0.5 - px
                val dy = r + 0.5 - py
                val d = dx * dx + dy * dy
                if (d <= bestDistance) {
                    best = k
                    bestDistance = d
                }
            }
        }
        return best
    }

    /** The path of cells as a route: its ends at the real points, smoothed by line of sight, with its steps. */
    private fun build(grid: WalkGrid, costs: Costs, cells: IntArray, from: GeoPoint, to: GeoPoint): Route {
        // a walk within one cell still goes from one point to the other
        val path = if (cells.size == 1) intArrayOf(cells[0], cells[0]) else cells
        val n = path.size
        val xs = DoubleArray(n) { path[it] % grid.cols + 0.5 }
        val ys = DoubleArray(n) { path[it] / grid.cols + 0.5 }
        if (grid.cellAt(from) == path[0]) {
            xs[0] = grid.x(from)
            ys[0] = grid.y(from)
        }
        if (grid.cellAt(to) == path[n - 1]) {
            xs[n - 1] = grid.x(to)
            ys[n - 1] = grid.y(to)
        }
        val gateAt = gateCrossings(grid, path, xs, ys)
        val keep = mutableListOf(0)
        var anchor = 0
        for (j in 1 until n - 1) {
            if (gateAt[j] >= 0 || !clear(grid, costs, xs[anchor], ys[anchor], xs[j + 1], ys[j + 1])) {
                keep += j
                anchor = j
            }
        }
        keep += n - 1
        val wx = DoubleArray(keep.size) { xs[keep[it]] }
        val wy = DoubleArray(keep.size) { ys[keep[it]] }
        val along = DoubleArray(keep.size)
        var weighted = 0.0
        for (i in 1 until keep.size) {
            val length = hypot(wx[i] - wx[i - 1], wy[i] - wy[i - 1]) * grid.cellM
            along[i] = along[i - 1] + length
            weighted += length * meanCost(grid, costs, wx[i - 1], wy[i - 1], wx[i], wy[i])
        }
        val waypoints = Waypoints(grid, wx, wy, IntArray(keep.size) { gateAt[keep[it]] }, along)
        return Route(
            points = List(keep.size) { grid.point(wx[it], wy[it]) },
            meters = along.last().roundToInt(),
            seconds = (weighted / WALK_MPS).roundToInt(),
            steps = steps(waypoints),
        )
    }

    /** Where the path crosses a fence at a gate: the cell of each crossing nearest its gate, by gate index; -1 elsewhere. */
    private fun gateCrossings(grid: WalkGrid, path: IntArray, xs: DoubleArray, ys: DoubleArray): IntArray {
        val gateAt = IntArray(path.size) { -1 }
        var i = 0
        while (i < path.size) {
            val gate = grid.gateOf[path[i]]
            if (gate < 0) {
                i++
                continue
            }
            val point = venue.gates[gate].point
            val gx = grid.x(point)
            val gy = grid.y(point)
            var best = i
            var j = i
            while (j < path.size && grid.gateOf[path[j]] == gate) {
                if (hypot(xs[j] - gx, ys[j] - gy) < hypot(xs[best] - gx, ys[best] - gy)) best = j
                j++
            }
            gateAt[best] = gate
            i = j
        }
        return gateAt
    }

    private class Waypoints(val grid: WalkGrid, val x: DoubleArray, val y: DoubleArray, val gate: IntArray, val along: DoubleArray) {
        val size: Int get() = x.size

        fun bearing(from: Int, to: Int): Double = (atan2(x[to] - x[from], y[to] - y[from]) * DEGREES + 360) % 360

        /** The bend at [i], clockwise positive, in degrees. */
        fun bend(i: Int): Double = signedAngle(bearing(i - 1, i), bearing(i, i + 1))

        fun point(i: Int): GeoPoint = grid.point(x[i], y[i])
    }

    /** A step at each real turn and each gate, bends a few metres apart told as one; then the arrival. */
    private fun steps(w: Waypoints): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()
        val last = w.size - 1
        var i = 1
        while (i < last) {
            val gate = w.gate[i]
            if (gate < 0 && abs(w.bend(i)) < STRAIGHT_DEG) {
                i++
                continue
            }
            var j = i
            if (gate < 0) {
                while (j + 1 < last && w.gate[j + 1] < 0 && w.along[j + 1] - w.along[j] < MERGE_M && abs(w.bend(j + 1)) >= STRAIGHT_DEG) j++
            }
            val turn = turnOf(signedAngle(w.bearing(i - 1, i), w.bearing(j, j + 1)))
            if (gate >= 0 || turn != Turn.STRAIGHT) {
                val point = w.point(i)
                val anchor = if (gate >= 0) venue.gates[gate].let { Anchor(AnchorKind.GATE, it.number, it.name) } else anchorAt(point)
                steps += RouteStep(point, w.along[i].roundToInt(), turn, anchor)
            }
            i = j + 1
        }
        val end = w.point(last)
        steps += RouteStep(end, w.along[last].roundToInt(), Turn.ARRIVE, anchorAt(end))
        return steps
    }

    /** The numbered landmark within [LANDMARK_M], else the zone, else nothing. */
    private fun anchorAt(point: GeoPoint): Anchor {
        val landmark = landmarks.map { it to distanceMeters(point, it.point) }.filter { it.second <= LANDMARK_M }.minByOrNull { it.second }?.first
        if (landmark != null) return Anchor(AnchorKind.LANDMARK, landmark.number, landmark.name)
        val zone = venue.zoneAt(point.lat, point.lon) ?: return Anchor.NONE
        return Anchor(AnchorKind.ZONE, name = zone.name)
    }

    companion object {
        /** How much more a step through a crowded zone costs, and how much slower it is walked. */
        const val CROWD_COST = 3f

        /** A walking pace inside a festival, in metres per second. */
        const val WALK_MPS = 1.0

        /** How far a point is moved onto a walkable cell: a fix in the water by the shore, a place behind a fence. */
        const val SNAP_M = 25.0

        /** A turn is told by a numbered landmark this close; farther, by the zone. */
        const val LANDMARK_M = 40

        /** Bends under this are going straight on; over [BACK_DEG], turning back. */
        const val STRAIGHT_DEG = 30.0
        const val BACK_DEG = 150.0

        /** Bends closer than this are one turn. */
        const val MERGE_M = 10.0

        internal const val SHUT = Float.POSITIVE_INFINITY
        private const val DEGREES = 57.29577951308232
    }
}

/** What stepping into each cell costs under a map state: [WalkRouter.SHUT] for a cell nobody may enter. */
internal class Costs(private val grid: WalkGrid, state: MapState, startCell: Int) {
    private val zoneCost = FloatArray(grid.venue.zones.size) { index ->
        when (state.state(grid.venue.zones[index].id)) {
            ZoneState.CLOSED -> WalkRouter.SHUT
            ZoneState.CROWDED -> WalkRouter.CROWD_COST
            ZoneState.OPEN, ZoneState.EXIT_ONLY -> 1f
        }
    }

    init {
        // someone inside a closed zone still has to walk out of it
        val startZone = if (startCell >= 0) grid.zoneOf[startCell] else -1
        if (startZone >= 0 && zoneCost[startZone] == WalkRouter.SHUT) zoneCost[startZone] = 1f
    }

    fun of(cell: Int): Float {
        if (!grid.isOpen(cell)) return WalkRouter.SHUT
        val zone = grid.zoneOf[cell]
        return if (zone < 0) 1f else zoneCost[zone]
    }
}

/**
 * A* (with a goal) or Dijkstra to the nearest cell marked in `goals`, over eight neighbours; a diagonal step needs both
 * cells beside it open, so no corner of a wall is cut. Costs are in cells, a diagonal √2.
 */
private class Search(private val grid: WalkGrid, private val costs: Costs) {
    private val g = FloatArray(grid.size) { WalkRouter.SHUT }
    private val parent = IntArray(grid.size) { -1 }
    private val done = BooleanArray(grid.size)
    private var heap = LongArray(1024)
    private var count = 0

    /** The cell reached, -1 when none can be. */
    fun run(start: Int, goal: Int, goals: IntArray?): Int {
        val cols = grid.cols
        val rows = grid.rows
        g[start] = 0f
        push(heuristic(start, goal), start)
        while (count > 0) {
            val cell = pop()
            if (done[cell]) continue
            done[cell] = true
            if (cell == goal || (goals != null && goals[cell] >= 0)) return cell
            val col = cell % cols
            val row = cell / cols
            for (k in 0 until 8) {
                val nc = col + DC[k]
                val nr = row + DR[k]
                if (nc < 0 || nc >= cols || nr < 0 || nr >= rows) continue
                val next = nr * cols + nc
                if (done[next]) continue
                val cost = costs.of(next)
                if (cost == WalkRouter.SHUT) continue
                if (k >= 4 && (costs.of(row * cols + nc) == WalkRouter.SHUT || costs.of(nr * cols + col) == WalkRouter.SHUT)) continue
                val ng = g[cell] + STEP[k] * cost
                if (ng < g[next]) {
                    g[next] = ng
                    parent[next] = cell
                    push(ng + heuristic(next, goal), next)
                }
            }
        }
        return -1
    }

    fun path(end: Int): IntArray {
        val cells = ArrayList<Int>()
        var cell = end
        while (cell >= 0) {
            cells += cell
            cell = parent[cell]
        }
        return IntArray(cells.size) { cells[cells.size - 1 - it] }
    }

    /** Octile distance in cells: never more than the walk, since no step costs less than its length. */
    private fun heuristic(cell: Int, goal: Int): Float {
        if (goal < 0) return 0f
        val dx = abs(cell % grid.cols - goal % grid.cols)
        val dy = abs(cell / grid.cols - goal / grid.cols)
        return max(dx, dy) - min(dx, dy) + SQRT2 * min(dx, dy)
    }

    // a binary heap of (priority, cell) packed in a Long: a non-negative float's bits sort as the float does
    private fun push(priority: Float, cell: Int) {
        if (count == heap.size) heap = heap.copyOf(count * 2)
        val key = (priority.toRawBits().toLong() shl 32) or cell.toLong()
        var i = count++
        while (i > 0) {
            val up = (i - 1) / 2
            if (heap[up] <= key) break
            heap[i] = heap[up]
            i = up
        }
        heap[i] = key
    }

    private fun pop(): Int {
        val top = heap[0]
        val last = heap[--count]
        var i = 0
        while (true) {
            var child = 2 * i + 1
            if (child >= count) break
            if (child + 1 < count && heap[child + 1] < heap[child]) child++
            if (heap[child] >= last) break
            heap[i] = heap[child]
            i = child
        }
        heap[i] = last
        return (top and 0xFFFFFFFFL).toInt()
    }

    private companion object {
        val SQRT2 = sqrt(2.0).toFloat()
        val DC = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val DR = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        val STEP = floatArrayOf(1f, 1f, 1f, 1f, SQRT2, SQRT2, SQRT2, SQRT2)
    }
}

/**
 * Whether the straight line between two points of the grid crosses only cells no dearer than the dearer of its ends:
 * every cell it touches, and both cells beside a corner it passes through.
 */
private fun clear(grid: WalkGrid, costs: Costs, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
    fun cost(c: Int, r: Int) = if (c in 0 until grid.cols && r in 0 until grid.rows) costs.of(r * grid.cols + c) else WalkRouter.SHUT
    var cx = floor(x0).toInt()
    var cy = floor(y0).toInt()
    val ex = floor(x1).toInt()
    val ey = floor(y1).toInt()
    val limit = max(cost(cx, cy), cost(ex, ey))
    if (limit == WalkRouter.SHUT) return false
    val dx = x1 - x0
    val dy = y1 - y0
    val sx = if (dx > 0) 1 else -1
    val sy = if (dy > 0) 1 else -1
    val tdx = if (dx != 0.0) 1.0 / abs(dx) else Double.POSITIVE_INFINITY
    val tdy = if (dy != 0.0) 1.0 / abs(dy) else Double.POSITIVE_INFINITY
    var tx = if (dx > 0) (cx + 1 - x0) * tdx else if (dx < 0) (x0 - cx) * tdx else Double.POSITIVE_INFINITY
    var ty = if (dy > 0) (cy + 1 - y0) * tdy else if (dy < 0) (y0 - cy) * tdy else Double.POSITIVE_INFINITY
    var left = abs(ex - cx) + abs(ey - cy)
    while (left > 0) {
        if (abs(tx - ty) < 1e-9) {
            if (cost(cx + sx, cy) > limit || cost(cx, cy + sy) > limit) return false
            cx += sx
            cy += sy
            tx += tdx
            ty += tdy
            left -= 2
        } else if (tx < ty) {
            cx += sx
            tx += tdx
            left--
        } else {
            cy += sy
            ty += tdy
            left--
        }
        if (cost(cx, cy) > limit) return false
    }
    return true
}

/** The cost per metre along a segment, sampled about every metre. */
private fun meanCost(grid: WalkGrid, costs: Costs, x0: Double, y0: Double, x1: Double, y1: Double): Double {
    val samples = max(1, ceil(hypot(x1 - x0, y1 - y0) * grid.cellM).toInt())
    var sum = 0.0
    for (s in 0 until samples) {
        val t = (s + 0.5) / samples
        val c = floor(x0 + (x1 - x0) * t).toInt().coerceIn(0, grid.cols - 1)
        val r = floor(y0 + (y1 - y0) * t).toInt().coerceIn(0, grid.rows - 1)
        val cost = costs.of(r * grid.cols + c)
        sum += if (cost == WalkRouter.SHUT) 1.0 else cost.toDouble()
    }
    return sum / samples
}

/** From one bearing to another, in degrees from -180 to 180, clockwise positive. */
internal fun signedAngle(fromDeg: Double, toDeg: Double): Double = ((toDeg - fromDeg + 540) % 360) - 180

internal fun turnOf(bend: Double): Turn = when {
    abs(bend) < WalkRouter.STRAIGHT_DEG -> Turn.STRAIGHT
    abs(bend) > WalkRouter.BACK_DEG -> Turn.BACK
    bend > 0 -> Turn.RIGHT
    else -> Turn.LEFT
}
