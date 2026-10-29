package io.embodit.feelnumber.domain.model

import kotlin.math.ln
import kotlin.math.pow

/**
 * Every number of the design's guess / reveal placement (design `guessModel`), as pure functions.
 * Positions are Stage metres. "Spot" = a point on the floor; a body stands on it, its vertical axis
 * through the spot, its bottom on the floor.
 */
object Placement {
    /** Clearance between the head and a body's level outline (taste D14). */
    const val CLEARANCE_M = 0.8f

    /** Bodies smaller than this sit on the tray in front of you; from this size on they stand on the floor. */
    const val FLOOR_MIN_M = 0.30f

    const val START_SIZE_M = 0.25f
    const val MIN_SIZE_M = 0.02f
    const val MAX_SIZE_M = 30f

    /**
     * Tray (v0.1.1): a stand 1.0 m above the anchor on the floor. Small bodies sit there, at the same place as
     * everything else: nothing is put in front of the head any more (taste D1 "move to marker", D20).
     */
    const val TRAY_HEIGHT_M = 1.0f
    const val TRAY_RING_M = 0.30f

    /** Default anchor before the user has put a marker down: beside the panel, far away (taste D20). */
    const val DEFAULT_ANCHOR_M = 2.5f
    const val DEFAULT_ANCHOR_OFF_PANEL_DEG = 55f

    /** View cone head -> panel (D20): content nearer than panel distance + 0.5 m must stay out of it. */
    const val CONE_BEYOND_PANEL_M = 0.5f

    /** Handle pull: x1.08 per centimetre of vertical hand travel. */
    const val HANDLE_GAIN_PER_CM = 1.08f

    /** Thumbstick: full push = x2 per second; dead zone; one axis must lead by 1.5x. */
    const val STICK_DEAD = 0.15f
    const val STICK_LEAD = 1.5f
    const val STICK_YAW_DEG_S = 90f

    /** Guess bodies taller than this get their handle on the side, 1.2 m above the floor. */
    const val HANDLE_SIDE_ABOVE_M = 2.2f
    const val HANDLE_SIDE_Y_M = 1.2f
    const val HANDLE_SIDE_GAP_M = 0.08f

    /** [Wrap me] only on questions whose true size is at least this. */
    const val WRAP_MIN_M = 2.0f

    /** Wall snap: within 0.15 m it snaps, beyond 0.25 m it lets go. */
    const val SNAP_IN_M = 0.15f
    const val SNAP_OUT_M = 0.25f

    fun clampSize(s: Float): Float = s.coerceIn(MIN_SIZE_M, MAX_SIZE_M)

    /** Slider position 0..1 <-> size, logarithmic over 0.02 - 30 m. */
    fun sliderOf(size: Float): Float = (ln(clampSize(size) / MIN_SIZE_M) / ln(MAX_SIZE_M / MIN_SIZE_M))

    fun sizeOfSlider(t: Float): Float = clampSize(MIN_SIZE_M * (MAX_SIZE_M / MIN_SIZE_M).pow(t.coerceIn(0f, 1f)))

    fun handleScale(size: Float, dyM: Float): Float = clampSize(size * HANDLE_GAIN_PER_CM.pow(dyM * 100f))

    fun stickScale(size: Float, stickY: Float, dtS: Float): Float = clampSize(size * 2f.pow(stickY * dtS))

    /** Which thumbstick axis leads (0 = none, 1 = x / turn, 2 = y / size). */
    fun stickAxis(x: Float, y: Float): Int {
        val ax = kotlin.math.abs(x)
        val ay = kotlin.math.abs(y)
        return when {
            ax > STICK_DEAD && ax > STICK_LEAD * ay -> 1
            ay > STICK_DEAD && ay > STICK_LEAD * ax -> 2
            else -> 0
        }
    }

    fun onFloor(size: Float): Boolean = size >= FLOOR_MIN_M

    /** The tray point above a floor [spot] (the tray ring's centre; a body there sits on it). */
    fun trayAt(spot: Vec3): Vec3 = Vec3(spot.x, spot.y + TRAY_HEIGHT_M, spot.z)

    /**
     * The anchor used until the user puts a marker down: on the floor, [DEFAULT_ANCHOR_M] from the viewer, turned
     * [DEFAULT_ANCHOR_OFF_PANEL_DEG] to the right of the direction of the question card (the card sits at the left, so
     * this is right of straight ahead and well clear of the view cone to the card). Placed once; never follows the head.
     */
    fun defaultAnchor(head: Vec3, cardCenter: Vec3, floorY: Float): Vec3 {
        val toCard = RoomBox.level(Vec3(cardCenter.x - head.x, 0f, cardCenter.z - head.z))
        val right = Vec3(-toCard.z, 0f, toCard.x)
        val a = Math.toRadians(DEFAULT_ANCHOR_OFF_PANEL_DEG.toDouble())
        val dir = (toCard * kotlin.math.cos(a).toFloat() + right * kotlin.math.sin(a).toFloat()).normalized()
        return Vec3(head.x, floorY, head.z) + dir * DEFAULT_ANCHOR_M
    }

    /**
     * D20: does a body (centre, bounding radius) block the view from [eye] to a panel at [panel] of half diagonal
     * [panelHalfDiag]? Only the part of the cone nearer than the panel + 0.5 m counts (behind that, the panel is drawn
     * in front of it). A head inside the body counts as blocking.
     */
    fun blocksPanel(eye: Vec3, panel: Vec3, panelHalfDiag: Float, center: Vec3, radius: Float): Boolean {
        val v = center - eye
        val dist = v.length
        if (dist <= radius) return true
        val toPanel = panel - eye
        val dPanel = toPanel.length
        if (dPanel < 1e-3f) return false
        if (dist - radius > dPanel + CONE_BEYOND_PANEL_M) return false
        val cosA = (v.dot(toPanel) / (dist * dPanel)).coerceIn(-1f, 1f)
        val ang = kotlin.math.acos(cosA)
        val panelAng = kotlin.math.atan(panelHalfDiag / dPanel)
        val bodyAng = kotlin.math.asin((radius / dist).coerceAtMost(1f))
        return ang < panelAng + bodyAng
    }

    /**
     * The actual spot: the user's spot (the marker) pushed away along head -> spot until the level distance to the
     * head is at least the body's level radius + 0.8 m; it stops at the room's wall (the axis stays in
     * the room). Only recomputed on resize, drag and floor taps, never because the head moved.
     */
    fun actualSpot(user: Vec3, head: Vec3, shape: Shape, size: Float, room: RoomBox?): Vec3 {
        val flat = Vec3(user.x - head.x, 0f, user.z - head.z)
        val dir = if (flat.length < 0.05f) (room?.forward ?: Vec3.AHEAD) else flat.normalized()
        val need = Geometry.levelRadius(shape, size) + CLEARANCE_M
        var dist = maxOf(flat.length, need)
        val origin = Vec3(head.x, user.y, head.z)
        room?.exitDistance(origin, dir)?.let { wall -> if (flat.length <= wall) dist = minOf(dist, wall) else dist = flat.length }
        return origin + dir * dist
    }

    /**
     * Where the revealed pair stands: at [spot], unless the true body's near surface would come within
     * 0.8 m of the head, then pushed away along head -> spot (through walls if need be: the room cuts it).
     */
    fun revealSpot(spot: Vec3, head: Vec3, shape: Shape, trueSize: Float, room: RoomBox? = null): Vec3 {
        val flat = Vec3(spot.x - head.x, 0f, spot.z - head.z)
        val dir = if (flat.length < 0.05f) (room?.forward ?: Vec3.AHEAD) else flat.normalized()
        val need = Geometry.levelRadius(shape, trueSize) + CLEARANCE_M
        return Vec3(head.x, spot.y, head.z) + dir * maxOf(flat.length, need)
    }

    /** [Wrap me]: the pair's axis right below the head. */
    fun wrapSpot(head: Vec3, floorY: Float): Vec3 = Vec3(head.x, floorY, head.z)

    /** Centre of a body of [size] standing on [base] (a floor spot or the tray point). */
    fun center(base: Vec3, size: Float): Vec3 = Vec3(base.x, base.y + size / 2f, base.z)

    /** Handle diameter: at least 5 cm, else 3 degrees of view. */
    fun handleDiameter(distanceM: Float): Float = maxOf(0.05f, distanceM * 0.0524f)

    /**
     * Wall snap during a drag (design I6). Returns the snapped spot and yaw (cube face parallel to the
     * wall, ball tangent), or null when no wall is within [threshold] of the body's outer surface.
     */
    fun snap(room: RoomBox, spot: Vec3, shape: Shape, size: Float, yawDeg: Float, threshold: Float): Pair<Vec3, Float>? {
        val l = room.toLocal(spot)
        val half = size / 2f
        // the body's extent along the room axes (a turned cube is wider)
        val reach =
            if (shape == Shape.SPHERE) half
            else {
                val d = Math.toRadians((yawDeg - room.yawDeg).toDouble())
                half * (kotlin.math.abs(kotlin.math.cos(d)) + kotlin.math.abs(kotlin.math.sin(d))).toFloat()
            }
        data class Wall(val gap: Float, val local: Vec3, val yaw: Float)
        val walls =
            listOf(
                Wall(room.width / 2f - (l.x + reach), Vec3(room.width / 2f - half, 0f, l.z), room.yawDeg),
                Wall((l.x - reach) + room.width / 2f, Vec3(-room.width / 2f + half, 0f, l.z), room.yawDeg),
                Wall(room.length / 2f - (l.z + reach), Vec3(l.x, 0f, room.length / 2f - half), room.yawDeg),
                Wall((l.z - reach) + room.length / 2f, Vec3(l.x, 0f, -room.length / 2f + half), room.yawDeg),
            )
        val best = walls.filter { kotlin.math.abs(it.gap) <= threshold }.minByOrNull { kotlin.math.abs(it.gap) } ?: return null
        // a cube turns to the nearest yaw that is parallel to the wall (multiples of 90 degrees from the room)
        val yaw =
            if (shape == Shape.CUBE) {
                val rel = yawDeg - room.yawDeg
                room.yawDeg + Math.round(rel / 90f) * 90f
            } else yawDeg
        return room.fromLocal(best.local).let { Vec3(it.x, spot.y, it.z) } to yaw
    }
}
