package io.embodit.feelnumber.domain.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the room box comes from (factory taste profile D7). */
enum class RoomSource {
    /** Built from detected FLOOR / WALL / CEILING planes. */
    SCAN,

    /** Nothing detected within 5 s (or the user chose it): 5 x 4 x 2.6 m around the viewer. */
    VIRTUAL,
}

/**
 * The room as one convex, level, oriented box (design `clipModel.room`): floor at [floorY], [width]
 * across, [length] ahead along [yawDeg] (0 = Stage -Z), [height] up. [hasCeiling] false = no CEILING
 * was found: the top is not cut. Everything the room cuts (guess, truth) is clamped to these six planes.
 */
data class RoomBox(
    val center: Vec3,
    val yawDeg: Float,
    val width: Float = DEFAULT_W,
    val length: Float = DEFAULT_L,
    val height: Float = DEFAULT_H,
    val floorY: Float = 0f,
    val hasCeiling: Boolean = true,
) {
    /** Unit level direction of the length axis. */
    val forward: Vec3
        get() {
            val r = Math.toRadians(yawDeg.toDouble())
            return Vec3(sin(r).toFloat(), 0f, -cos(r).toFloat())
        }

    val right: Vec3
        get() = forward.let { Vec3(-it.z, 0f, it.x) }

    val ceilingY: Float
        get() = floorY + height

    /** Room-local coordinates: x across (along [right]), y above the floor, z along [forward]. */
    fun toLocal(p: Vec3): Vec3 {
        val d = Vec3(p.x - center.x, 0f, p.z - center.z)
        return Vec3(d.dot(right), p.y - floorY, d.dot(forward))
    }

    fun fromLocal(l: Vec3): Vec3 = Vec3(center.x, floorY + l.y, center.z) + right * l.x + forward * l.z

    /** Clamps a Stage point into the box (the top only when there is a ceiling). */
    fun clamp(p: Vec3): Vec3 {
        val l = toLocal(p)
        val hx = width / 2f
        val hz = length / 2f
        val y = if (hasCeiling) l.y.coerceIn(0f, height) else l.y.coerceAtLeast(0f)
        return fromLocal(Vec3(l.x.coerceIn(-hx, hx), y, l.z.coerceIn(-hz, hz)))
    }

    fun contains(p: Vec3, margin: Float = 0f): Boolean {
        val l = toLocal(p)
        val top = if (hasCeiling) height + margin else Float.MAX_VALUE
        return abs(l.x) <= width / 2f + margin && abs(l.z) <= length / 2f + margin && l.y >= -margin && l.y <= top
    }

    fun containsFlat(p: Vec3, margin: Float = 0f): Boolean {
        val l = toLocal(p)
        return abs(l.x) <= width / 2f + margin && abs(l.z) <= length / 2f + margin
    }

    /**
     * Largest t >= 0 such that `from + dir * t` stays inside the floor outline (level), for a [from]
     * inside it; null when [from] is outside (nothing to stop at).
     */
    fun exitDistance(from: Vec3, dir: Vec3): Float? {
        if (!containsFlat(from)) return null
        val l = toLocal(from)
        val dx = dir.dot(right)
        val dz = dir.dot(forward)
        var t = Float.MAX_VALUE
        if (abs(dx) > 1e-6f) t = minOf(t, ((if (dx > 0) width / 2f else -width / 2f) - l.x) / dx)
        if (abs(dz) > 1e-6f) t = minOf(t, ((if (dz > 0) length / 2f else -length / 2f) - l.z) / dz)
        return t.coerceAtLeast(0f)
    }

    /** The six faces: point on the face, inward normal, the face's two in-plane axes and half extents. */
    fun faces(): List<Face> {
        val c = Vec3(center.x, floorY + height / 2f, center.z)
        val up = Vec3.UP
        val list =
            mutableListOf(
                Face("front", c + forward * (length / 2f), -forward, right, up, width / 2f, height / 2f),
                Face("back", c - forward * (length / 2f), forward, right, up, width / 2f, height / 2f),
                Face("right", c + right * (width / 2f), -right, forward, up, length / 2f, height / 2f),
                Face("left", c - right * (width / 2f), right, forward, up, length / 2f, height / 2f),
                Face("floor", Vec3(c.x, floorY, c.z), up, right, forward, width / 2f, length / 2f),
            )
        if (hasCeiling) list += Face("ceiling", Vec3(c.x, ceilingY, c.z), -up, right, forward, width / 2f, length / 2f)
        return list
    }

    /** Floor corners (front-left, front-right, back-right, back-left). */
    fun corners(): List<Vec3> {
        val c = Vec3(center.x, floorY, center.z)
        val f = forward * (length / 2f)
        val r = right * (width / 2f)
        return listOf(c + f - r, c + f + r, c - f + r, c - f - r)
    }

    /** New size, the centre kept (the room page's steppers). */
    fun sized(width: Float = this.width, length: Float = this.length, height: Float = this.height): RoomBox =
        copy(width = width.coerceIn(MIN_WL, MAX_WL).round1(), length = length.coerceIn(MIN_WL, MAX_WL).round1(), height = height.coerceIn(MIN_H, MAX_H).round1(), hasCeiling = true)

    fun turned(deg: Float): RoomBox {
        var y = yawDeg + deg
        while (y > 180f) y -= 360f
        while (y <= -180f) y += 360f
        return copy(yawDeg = y)
    }

    fun moved(delta: Vec3): RoomBox = copy(center = Vec3(center.x + delta.x, center.y, center.z + delta.z))

    val label: String
        get() = String.format(Locale.US, "%.1f × %.1f × %.1f m", length, width, height)

    private fun Float.round1(): Float = Math.round(this * 10f) / 10f

    data class Face(val name: String, val point: Vec3, val normal: Vec3, val u: Vec3, val v: Vec3, val hu: Float, val hv: Float)

    companion object {
        /** Virtual room (design `virtualRoom.default`): 5 m long, 4 m wide, 2.6 m high. */
        const val DEFAULT_L = 5.0f
        const val DEFAULT_W = 4.0f
        const val DEFAULT_H = 2.6f
        const val MIN_WL = 2.0f
        const val MAX_WL = 12.0f
        const val MIN_H = 2.0f
        const val MAX_H = 5.0f
        const val STEP_M = 0.1f

        /** The viewer stands 1.0 m from the back wall, centred across, facing along the length. */
        private const val BEHIND_M = 1.0f

        /** Floor of a virtual room: head height - 1.6 m. */
        const val EYE_OVER_FLOOR_M = 1.6f

        fun level(v: Vec3): Vec3 = Vec3(v.x, 0f, v.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }

        fun yawOf(dir: Vec3): Float {
            val f = level(dir)
            return Math.toDegrees(atan2(f.x.toDouble(), -f.z.toDouble())).toFloat()
        }

        /** The virtual room around a viewer at [head] looking along [facing]. */
        fun around(head: Vec3, facing: Vec3, width: Float = DEFAULT_W, length: Float = DEFAULT_L, height: Float = DEFAULT_H, floorY: Float = head.y - EYE_OVER_FLOOR_M): RoomBox {
            val f = level(facing)
            val ahead = (length / 2f - BEHIND_M).coerceAtLeast(0f)
            val c = Vec3(head.x, floorY, head.z) + f * ahead
            return RoomBox(c, yawOf(f), width, length, height, floorY, hasCeiling = true)
        }
    }
}

/**
 * The scanned room (design `clipModel.room`): the floor height from the lowest FLOOR plane, the ceiling
 * from the lowest CEILING plane well above it; with two or more WALL planes the box is turned to the
 * widest wall and spans every wall and floor vertex; without walls it is the smallest rectangle around
 * the floor polygon. Missing pieces come from the virtual room's size (design `virtualRoom.rule`).
 */
object RoomFit {
    fun fromPlanes(planes: Collection<PlaneSurface>, head: Vec3, facing: Vec3, fallback: Vec3 = Vec3(RoomBox.DEFAULT_W, RoomBox.DEFAULT_H, RoomBox.DEFAULT_L)): RoomBox? {
        val floors = planes.filter { it.kind == SurfaceKind.FLOOR && it.horizontal }
        if (floors.isEmpty()) return null
        val floorY = floors.minOf { it.centroid.y }
        val ceilings = planes.filter { it.kind == SurfaceKind.CEILING && it.horizontal && it.centroid.y > floorY + 1.8f }
        val ceilingY = ceilings.minOfOrNull { it.centroid.y }
        val walls = planes.filter { it.kind == SurfaceKind.WALL && !it.horizontal }
        val points = (floors.flatMap { it.vertices } + walls.flatMap { it.vertices }).map { Vec3(it.x, 0f, it.z) }
        val yaw: Float =
            if (walls.size >= 2) {
                val widest = walls.maxBy { horizontalSpan(it) }
                val n = RoomBox.level(widest.normal)
                pickAxis(n, facing)
            } else {
                pickAxis(minAreaDirection(floors.flatMap { it.vertices }), facing)
            }
        val probe = RoomBox(Vec3.ZERO, yaw, 1f, 1f, 1f, floorY)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (p in points + Vec3(head.x, 0f, head.z)) {
            val l = probe.toLocal(Vec3(p.x, floorY, p.z))
            minX = minOf(minX, l.x)
            maxX = maxOf(maxX, l.x)
            minZ = minOf(minZ, l.z)
            maxZ = maxOf(maxZ, l.z)
        }
        // a floor patch smaller than a room (a partly scanned floor) grows to the virtual size around the viewer
        val hl = probe.toLocal(Vec3(head.x, floorY, head.z))
        if (maxX - minX < RoomBox.MIN_WL) {
            minX = minOf(minX, hl.x - fallback.x / 2f)
            maxX = maxOf(maxX, hl.x + fallback.x / 2f)
        }
        if (maxZ - minZ < RoomBox.MIN_WL) {
            minZ = minOf(minZ, hl.z - fallback.z / 2f)
            maxZ = maxOf(maxZ, hl.z + fallback.z / 2f)
        }
        val center = probe.fromLocal(Vec3((minX + maxX) / 2f, 0f, (minZ + maxZ) / 2f))
        val height = ceilingY?.let { it - floorY } ?: fallback.y
        return RoomBox(
            Vec3(center.x, floorY, center.z), yaw,
            (maxX - minX).coerceAtMost(RoomBox.MAX_WL * 2f), (maxZ - minZ).coerceAtMost(RoomBox.MAX_WL * 2f),
            height, floorY, hasCeiling = ceilingY != null,
        )
    }

    private fun horizontalSpan(p: PlaneSurface): Float {
        var best = 0f
        for (a in p.vertices) for (b in p.vertices) best = maxOf(best, Vec3(a.x - b.x, 0f, a.z - b.z).length)
        return best
    }

    /** Of the four level directions [axis] rotated by 0 / 90 / 180 / 270 degrees, the one closest to the viewer's heading. */
    private fun pickAxis(axis: Vec3, facing: Vec3): Float {
        val f = RoomBox.level(facing)
        val a = RoomBox.level(axis)
        val options = listOf(a, Vec3(-a.z, 0f, a.x), -a, Vec3(a.z, 0f, -a.x))
        return RoomBox.yawOf(options.maxBy { it.dot(f) })
    }

    /** Direction of the smallest-area bounding rectangle of the floor vertices (1 degree steps). */
    private fun minAreaDirection(vertices: List<Vec3>): Vec3 {
        var bestArea = Float.MAX_VALUE
        var best = Vec3.AHEAD
        for (deg in 0 until 90) {
            val r = Math.toRadians(deg.toDouble())
            val f = Vec3(sin(r).toFloat(), 0f, -cos(r).toFloat())
            val rt = Vec3(-f.z, 0f, f.x)
            var a0 = Float.MAX_VALUE
            var a1 = -Float.MAX_VALUE
            var b0 = Float.MAX_VALUE
            var b1 = -Float.MAX_VALUE
            for (v in vertices) {
                val a = v.x * rt.x + v.z * rt.z
                val b = v.x * f.x + v.z * f.z
                a0 = minOf(a0, a)
                a1 = maxOf(a1, a)
                b0 = minOf(b0, b)
                b1 = maxOf(b1, b)
            }
            val area = (a1 - a0) * (b1 - b0)
            if (area < bestArea) {
                bestArea = area
                best = f
            }
        }
        return best
    }
}
