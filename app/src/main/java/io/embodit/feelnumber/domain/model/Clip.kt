package io.embodit.feelnumber.domain.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A body in the room: shape, centre (Stage metres), size (diameter / edge) and level turn. */
data class Body(val shape: Shape, val center: Vec3, val size: Float, val yawDeg: Float = 0f) {
    val half: Float
        get() = size / 2f

    private val turn: Quat4
        get() = Quat4.axisAngle(Vec3.UP, Math.toRadians(-yawDeg.toDouble()).toFloat())

    /** Body-local -> Stage. */
    fun world(local: Vec3): Vec3 = center + turn.rotate(local)

    fun local(p: Vec3): Vec3 = turn.conjugate().rotate(p - center)

    fun contains(p: Vec3, margin: Float = 0f): Boolean =
        when (shape) {
            Shape.SPHERE -> p.distanceTo(center) < half + margin
            Shape.CUBE -> local(p).let { abs(it.x) < half + margin && abs(it.y) < half + margin && abs(it.z) < half + margin }
        }

    val bottomY: Float
        get() = center.y - half

    val topY: Float
        get() = center.y + half
}

/** Planar mesh data in Stage metres (positions, normals, triangle indices), SDK-free so it can be tested. */
class MeshData(val positions: List<Vec3>, val normals: List<Vec3>, val indices: List<Int>) {
    val triangles: Int
        get() = indices.size / 3

    /** Same triangles, opposite winding and normals: the inner surface. */
    fun reversed(): MeshData {
        val idx = ArrayList<Int>(indices.size)
        var i = 0
        while (i + 2 < indices.size) {
            idx += indices[i]
            idx += indices[i + 2]
            idx += indices[i + 1]
            i += 3
        }
        return MeshData(positions, normals.map { -it }, idx)
    }

    fun boundsMin(): Vec3 = Vec3(positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z })

    fun boundsMax(): Vec3 = Vec3(positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z })
}

/**
 * The room cuts bodies (design `clipModel`): every vertex of the body mesh is clamped into the room
 * box's six planes, so whatever lies beyond a wall or the ceiling is flattened onto it; the cut line
 * itself is drawn analytically as a ring on each face that the body crosses.
 */
object Clip {
    /**
     * The body pokes out of the room by more than [eps] somewhere (S09). 1 cm, not 1 mm: a body snapped
     * or pushed against a wall only touches it and must not read "cut" (QA round 1).
     */
    fun isCut(room: RoomBox, b: Body, eps: Float = 0.01f): Boolean {
        return when (b.shape) {
            Shape.SPHERE -> {
                val l = room.toLocal(b.center)
                abs(l.x) + b.half > room.width / 2f + eps || abs(l.z) + b.half > room.length / 2f + eps ||
                    (room.hasCeiling && l.y + b.half > room.height + eps) || l.y - b.half < -eps
            }
            Shape.CUBE -> cubeCorners(b).any { !room.contains(it, eps) }
        }
    }

    /**
     * v0.1.2: the guess is drawn clipped by the room only while its axis stands inside the room. A guess whose centre is
     * outside (dragged or pushed beyond a wall, a scanned room smaller than the real one) is drawn whole: clamping it
     * would flatten all of it onto the wall ("松开后球变 2D 贴到边上").
     */
    fun clipsGuess(room: RoomBox, b: Body): Boolean = room.containsFlat(b.center) && isCut(room, b)

    fun cubeCorners(b: Body): List<Vec3> {
        val h = b.half
        val out = ArrayList<Vec3>(8)
        for (x in listOf(-h, h)) for (y in listOf(-h, h)) for (z in listOf(-h, h)) out += b.world(Vec3(x, y, z))
        return out
    }

    /** Share of the body's volume inside the room, by a [n]^3 sample grid (+-2 % at n = 24). */
    fun visibleFraction(room: RoomBox, b: Body, n: Int = 24): Float {
        var inside = 0
        var total = 0
        for (i in 0 until n) for (j in 0 until n) for (k in 0 until n) {
            val l = Vec3(((i + 0.5f) / n * 2f - 1f) * b.half, ((j + 0.5f) / n * 2f - 1f) * b.half, ((k + 0.5f) / n * 2f - 1f) * b.half)
            if (b.shape == Shape.SPHERE && l.length > b.half) continue
            total++
            if (room.contains(b.world(l))) inside++
        }
        return if (total == 0) 1f else inside.toFloat() / total
    }

    /** Head inside the body, with hysteresis: entering needs [enterMargin] inside, leaving [exitMargin] outside. */
    fun headInside(b: Body, head: Vec3, wasInside: Boolean, hysteresis: Float = 0.2f): Boolean =
        if (wasInside) b.contains(head, hysteresis / 2f) else b.contains(head, -hysteresis / 2f)

    // ---------------------------------------------------------------------------------------------
    // meshes
    // ---------------------------------------------------------------------------------------------

    /** Latitude / longitude ball, clamped into [room] when given. */
    fun sphere(b: Body, room: RoomBox?, lon: Int = 64, lat: Int = 32): MeshData {
        val positions = ArrayList<Vec3>((lon + 1) * (lat + 1))
        val normals = ArrayList<Vec3>((lon + 1) * (lat + 1))
        val indices = ArrayList<Int>(lon * lat * 6)
        for (j in 0..lat) {
            val phi = PI * j / lat
            val y = cos(phi).toFloat()
            val r = sin(phi).toFloat()
            for (i in 0..lon) {
                val th = 2 * PI * i / lon
                val n = Vec3((r * cos(th)).toFloat(), y, (r * sin(th)).toFloat())
                val p = b.center + n * b.half
                positions += room?.clamp(p) ?: p
                normals += n
            }
        }
        val row = lon + 1
        for (j in 0 until lat) for (i in 0 until lon) {
            val a = j * row + i
            val c = a + row
            // outward winding (counter-clockwise seen from outside)
            indices += a
            indices += a + 1
            indices += c
            indices += a + 1
            indices += c + 1
            indices += c
        }
        return MeshData(positions, normals, indices)
    }

    /** Cube with [n] x [n] quads per face (so that clamping can bend it along a wall), clamped into [room]. */
    fun cube(b: Body, room: RoomBox?, n: Int = 16): MeshData {
        val positions = ArrayList<Vec3>()
        val normals = ArrayList<Vec3>()
        val indices = ArrayList<Int>()
        val h = b.half
        val faces =
            listOf(
                Triple(Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)),
                Triple(Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
                Triple(Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
                Triple(Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)),
                Triple(Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
                Triple(Vec3(0f, 0f, -1f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f)),
            )
        for ((nrm, u, v) in faces) {
            val base = positions.size
            val wn = b.world(nrm) - b.center
            for (j in 0..n) for (i in 0..n) {
                val su = (i.toFloat() / n * 2f - 1f) * h
                val sv = (j.toFloat() / n * 2f - 1f) * h
                val p = b.world(nrm * h + u * su + v * sv)
                positions += room?.clamp(p) ?: p
                normals += wn
            }
            val row = n + 1
            for (j in 0 until n) for (i in 0 until n) {
                val a = base + j * row + i
                val c = a + row
                indices += a
                indices += a + 1
                indices += c
                indices += a + 1
                indices += c + 1
                indices += c
            }
        }
        return MeshData(positions, normals, indices)
    }

    fun body(b: Body, room: RoomBox?): MeshData = if (b.shape == Shape.SPHERE) sphere(b, room) else cube(b, room)

    /** Polylines of the wireframe (ball: 12 meridians + 5 parallels; cube: 12 edges), clamped into [room]. */
    fun wire(b: Body, room: RoomBox?): List<List<Vec3>> {
        val lines = ArrayList<List<Vec3>>()
        fun keep(p: Vec3) = room?.clamp(p) ?: p
        when (b.shape) {
            Shape.SPHERE -> {
                for (m in 0 until 12) {
                    val th = 2 * PI * m / 12
                    lines += (0..32).map { j ->
                        val phi = PI * j / 32
                        keep(b.center + Vec3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat()) * b.half)
                    }
                }
                for (p in 1..5) {
                    val phi = PI * p / 6
                    lines += (0..48).map { i ->
                        val th = 2 * PI * i / 48
                        keep(b.center + Vec3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat()) * b.half)
                    }
                }
            }
            Shape.CUBE -> {
                val h = b.half
                val c = listOf(-h, h)
                for (y in c) for (z in c) lines += subdivide(b.world(Vec3(-h, y, z)), b.world(Vec3(h, y, z))).map(::keep)
                for (x in c) for (z in c) lines += subdivide(b.world(Vec3(x, -h, z)), b.world(Vec3(x, h, z))).map(::keep)
                for (x in c) for (y in c) lines += subdivide(b.world(Vec3(x, y, -h)), b.world(Vec3(x, y, h))).map(::keep)
            }
        }
        return lines
    }

    private fun subdivide(a: Vec3, b: Vec3, steps: Int = 16): List<Vec3> = (0..steps).map { a.lerp(b, it.toFloat() / steps) }

    // ---------------------------------------------------------------------------------------------
    // cut rings
    // ---------------------------------------------------------------------------------------------

    /** A piece of a cut ring on a face: two Stage points and the face's inward normal. */
    data class Segment(val a: Vec3, val b: Vec3, val normal: Vec3)

    /**
     * The cut lines where the body crosses the room's walls and ceiling (not the floor it stands on):
     * a ball gives a circle per face (centre = the ball centre's projection, radius sqrt(R^2 - d^2)), a
     * cube the polygon of the plane through it; both are kept only inside the face's rectangle and
     * lifted [lift] off it into the room.
     */
    fun rings(room: RoomBox, b: Body, lift: Float = 0.002f): List<Segment> {
        val out = ArrayList<Segment>()
        for (f in room.faces()) {
            if (f.name == "floor") continue
            val d = (b.center - f.point).dot(f.normal)
            val loop: List<Vec3> =
                when (b.shape) {
                    Shape.SPHERE -> {
                        if (abs(d) >= b.half) continue
                        val r = sqrt(b.half * b.half - d * d)
                        val c = b.center - f.normal * d
                        (0..96).map { i ->
                            val a = 2 * PI * i / 96
                            c + f.u * (r * cos(a).toFloat()) + f.v * (r * sin(a).toFloat())
                        }
                    }
                    Shape.CUBE -> cubeSection(b, f.point, f.normal)
                }
            if (loop.size < 2) continue
            for (i in 0 until loop.size - 1) {
                val steps = maxOf(1, (loop[i].distanceTo(loop[i + 1]) / 0.05f).toInt())
                for (s in 0 until steps) {
                    val p = loop[i].lerp(loop[i + 1], s.toFloat() / steps)
                    val q = loop[i].lerp(loop[i + 1], (s + 1).toFloat() / steps)
                    val m = p.lerp(q, 0.5f) - f.point
                    if (abs(m.dot(f.u)) <= f.hu && abs(m.dot(f.v)) <= f.hv) out += Segment(p + f.normal * lift, q + f.normal * lift, f.normal)
                }
            }
        }
        return out
    }

    /** The closed polygon where the plane (point, normal) cuts the cube; empty when it misses. */
    fun cubeSection(b: Body, point: Vec3, normal: Vec3): List<Vec3> {
        val corners = cubeCorners(b)
        val pts = ArrayList<Vec3>()
        // corner index bits: x (4), y (2), z (1) as built by cubeCorners
        for (i in 0 until 8) for (bit in intArrayOf(4, 2, 1)) {
            val j = i or bit
            if (j == i) continue
            val a = corners[i]
            val c = corners[j]
            val da = (a - point).dot(normal)
            val dc = (c - point).dot(normal)
            if ((da < 0f) == (dc < 0f) || abs(da - dc) < 1e-6f) continue
            pts += a.lerp(c, da / (da - dc))
        }
        if (pts.size < 3) return emptyList()
        val centre = pts.fold(Vec3.ZERO) { s, p -> s + p } / pts.size.toFloat()
        var u = (pts[0] - centre).normalized()
        if (u.length < 0.5f) u = Vec3.RIGHT
        val v = normal.cross(u).normalized()
        val sorted = pts.sortedBy { p -> (p - centre).let { atan2(it.dot(v), it.dot(u)) } }
        return sorted + sorted.first()
    }
}
