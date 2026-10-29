package io.embodit.feelnumber.ui.feel.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import io.embodit.feelnumber.domain.model.Body
import io.embodit.feelnumber.domain.model.Clip
import io.embodit.feelnumber.domain.model.MeshData
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.Quat4
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.Shape
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.platform.FeelLog

/** Turn of a body's yaw (degrees, room convention) as a rotation about the vertical. */
internal fun yawRotation(yawDeg: Float): Quat4 = Quat4.axisAngle(Vec3.UP, Math.toRadians(-yawDeg.toDouble()).toFloat())

/** An entity that is switched on and off without re-adding it (a disabled entity keeps its components). */
internal class Toggle(val entity: Entity) {
    var on = true
        private set

    fun set(value: Boolean) {
        if (value == on) return
        on = value
        entity.enabled = value
    }

    fun destroy() = runCatching { entity.destroy(true) }
}

/** A model entity whose mesh is rebuilt from [MeshData] in Stage space (it stays at the identity). */
internal class WorldMesh(parent: Entity, private val material: Material, name: String) {
    private var mesh: MeshResource? = null
    private var entity: Entity? = null
    private val holder = Entity().named(name).also { parent.addChild(it) }
    var visible = false
        private set

    fun show(data: MeshData) {
        if (data.indices.isEmpty()) {
            hide()
            return
        }
        val m = mesh
        if (m == null || !MeshFactory.replace(m, data)) {
            runCatching { entity?.destroy(true) }
            val fresh = MeshFactory.of(data, "mesh:${holder.getName()}")
            mesh = fresh
            entity = ModelEntity(fresh, material).also { holder.addChild(it) }
        }
        setVisible(true)
    }

    /** Swaps in a ready mesh (the reveal's pre-built growth levels). */
    fun showMesh(ready: MeshResource) {
        val e = entity
        if (e == null) {
            entity = ModelEntity(ready, material).also { holder.addChild(it) }
        } else {
            e.components[ModelComponent::class.java]?.mesh = ready
        }
        mesh = null
        setVisible(true)
    }

    fun setOffset(offset: Vec3) = holder.setLocal(position = offset)

    fun setOpacity(k: Float) = holder.setOpacity(k)

    fun setVisible(on: Boolean) {
        if (on == visible) return
        visible = on
        holder.enabled = on
    }

    fun hide() = setVisible(false)

    fun destroy() {
        runCatching { holder.destroy(true) }
        mesh = null
        entity = null
    }
}

/**
 * The guess (design `entities3d.guessBody` + `sizeHandle` + `tray` + `floorMark`): a unit ball or cube
 * scaled to the guessed size while it changes, swapped for a room-clipped mesh when it pokes out of the
 * room and stands still; the white handle on top (or on the side above 2.2 m, with a rod); the tray
 * ring; the dashed floor mark at the actual spot.
 */
internal class GuessView(private val root: Entity, private val m: SceneMaterials, private val shared: SharedResources) {
    private val unitSphere: MeshResource = shared.keep(MeshFactory.of(Clip.sphere(Body(Shape.SPHERE, Vec3.ZERO, 1f), null), "unitSphere"))
    private val unitCube: MeshResource = shared.keep(MeshFactory.of(Clip.cube(Body(Shape.CUBE, Vec3.ZERO, 1f), null, n = 2), "unitCube"))
    private val sphereLines: MeshResource = shared.keep(MeshFactory.of(unitLines(Shape.SPHERE), "unitSphereLines"))
    private val cubeLines: MeshResource = shared.keep(MeshFactory.of(unitLines(Shape.CUBE), "unitCubeLines"))

    /** Pivot: position = body centre, rotation = yaw, scale = size. */
    private val pivot = Entity().named("guessPivot").also { root.addChild(it) }
    private var bodyEntity: Entity = ModelEntity(unitSphere, m.guess).named("guessBody").also { pivot.addChild(it) }
    private var linesEntity: Entity = ModelEntity(sphereLines, m.guessLines).named("guessLines").also { pivot.addChild(it) }
    private var shape = Shape.SPHERE
    private val clipped = WorldMesh(root, m.guess, "guessClipped")
    private val clippedLines = WorldMesh(root, m.guessLines, "guessClippedLines")

    private val handle: Entity =
        ModelEntity(shared.keep(MeshResource.createSphere(0.5f)), m.handle).named("guessHandle").also {
            root.addChild(it)
            it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(0.75f)), physicsMaterial = PhysicsMaterialResource()))
            it.components.set(InteractableComponent())
            runCatching { it.components.set(HoverEffectComponent()) }
        }
    private val rod = Strip(root, MeshFactory.unitBand("handleRod"), m.handleRod, "handleRod")
    private val tray = Toggle(ModelEntity(shared.keep(MeshFactory.ring(0.145f, 0.15f, "trayRing")), m.tray).named("tray").also { root.addChild(it) })
    private val floorMark = Toggle(Entity().named("floorMark").also { root.addChild(it) })

    private var visible = true
    var center: Vec3 = Vec3.ZERO
        private set
    var size = 0.25f
        private set
    var yawDeg = 0f
        private set
    private var clipOn = false
    private var hot = false

    /** Where the handle is (Stage) and its diameter, for the controller's hit test. */
    var handleAt: Vec3 = Vec3.ZERO
        private set
    var handleD: Float = 0.05f
        private set

    init {
        bodyEntity.components.set(InteractableComponent())
        runCatching { bodyEntity.components.set(HoverEffectComponent()) }
        setCollider(Shape.SPHERE)
        val cross = MeshFactory.box(Vec3(0.20f, 0.002f, 0.012f))
        floorMark.entity.addChild(ModelEntity(shared.keep(MeshFactory.ring(0.115f, 0.125f, "floorMarkRing", dashes = 12)), m.floorMark))
        floorMark.entity.addChild(ModelEntity(shared.keep(cross), m.floorMark))
        floorMark.entity.addChild(ModelEntity(shared.keep(MeshFactory.box(Vec3(0.012f, 0.002f, 0.20f))), m.floorMark))
        clipped.hide()
        clippedLines.hide()
    }

    fun isBody(e: Entity): Boolean = e === bodyEntity || e === pivot

    fun isHandle(e: Entity): Boolean = e === handle

    private fun setCollider(s: Shape) {
        val shapeRes = if (s == Shape.SPHERE) ShapeResource.createSphere(0.5f) else ShapeResource.createBox(Vec3(1f, 1f, 1f).sdk())
        bodyEntity.components.set(CollisionComponent(collisionShape = listOf(shapeRes), physicsMaterial = PhysicsMaterialResource()))
    }

    fun setShape(s: Shape) {
        if (s == shape) return
        shape = s
        bodyEntity.components[ModelComponent::class.java]?.mesh = if (s == Shape.SPHERE) unitSphere else unitCube
        linesEntity.components[ModelComponent::class.java]?.mesh = if (s == Shape.SPHERE) sphereLines else cubeLines
        setCollider(s)
    }

    /** Places the scaled body (centre, size, yaw). */
    fun place(center: Vec3, size: Float, yawDeg: Float) {
        this.center = center
        this.size = size
        this.yawDeg = yawDeg
        pivot.setLocal(position = center, rotation = yawRotation(yawDeg), scale = Vec3(size, size, size))
    }

    /** Uses a room-clipped mesh instead of the scaled one (null = back to the scaled body). */
    fun setClipped(body: Body?, room: RoomBox?) {
        if (body == null || room == null) {
            if (clipOn) {
                clipOn = false
                clipped.hide()
                clippedLines.hide()
                pivot.enabled = visible
            }
            return
        }
        clipped.show(Clip.body(body, room))
        clippedLines.show(MeshFactory.ribbons(Clip.wire(body, room), (body.size * 0.006f).coerceIn(0.003f, 0.025f)) { p -> (p - body.center).normalized() })
        clipOn = true
        // the scaled body stays as the (invisible) tap target
        bodyEntity.setVisible(false)
        linesEntity.setVisible(false)
        FeelLog.i("GUESS clipped size=${FeelLog.f(body.size, 3)}")
    }

    fun unclip() {
        if (!clipOn) return
        clipOn = false
        clipped.hide()
        clippedLines.hide()
        bodyEntity.setVisible(true)
        linesEntity.setVisible(true)
    }

    fun setVisible(on: Boolean) {
        visible = on
        pivot.enabled = on
        if (!on) {
            clipped.hide()
            clippedLines.hide()
            handle.enabled = false
            rod.hide()
        } else if (clipOn) {
            clipped.setVisible(true)
            clippedLines.setVisible(true)
        }
    }

    /** The body takes no taps while the head is inside it (it would swallow every pinch). */
    fun setTappable(on: Boolean) {
        bodyEntity.components[InteractableComponent::class.java]?.let { if (!on) bodyEntity.components.remove(InteractableComponent::class.java) }
        if (on && bodyEntity.components[InteractableComponent::class.java] == null) bodyEntity.components.set(InteractableComponent())
    }

    fun setHot(on: Boolean) {
        if (on == hot) return
        hot = on
        bodyEntity.setMaterial(if (on) m.guessHot else m.guess)
    }

    /** The handle, every frame: on top up to 2.2 m, else on the side towards the viewer, 1.2 m above the floor. */
    fun placeHandle(show: Boolean, head: Vec3, floorY: Float) {
        if (!show || !visible) {
            handle.enabled = false
            rod.hide()
            return
        }
        val top = Vec3(center.x, center.y + size / 2f, center.z)
        val tall = size > Placement.HANDLE_SIDE_ABOVE_M
        val at: Vec3
        if (!tall) {
            val d = Placement.handleDiameter(head.distanceTo(top))
            at = top + Vec3(0f, 0.03f + d / 2f, 0f)
            rod.hide()
        } else {
            val y = floorY + Placement.HANDLE_SIDE_Y_M
            val toHead = RoomBox.level(Vec3(head.x - center.x, 0f, head.z - center.z))
            // out of the body's level outline (ball: radius at that height; cube: half diagonal) plus the gap
            val reach =
                if (shape == Shape.SPHERE) {
                    val dy = y - center.y
                    kotlin.math.sqrt((size * size / 4f - dy * dy).coerceAtLeast(0f))
                } else size * 0.7072f
            val surface = Vec3(center.x, y, center.z) + toHead * reach
            val d = Placement.handleDiameter(head.distanceTo(surface))
            at = surface + toHead * (Placement.HANDLE_SIDE_GAP_M + d / 2f)
            rod.draw(surface, at, 0.005f, toHead)
        }
        val d = Placement.handleDiameter(head.distanceTo(at))
        handleAt = at
        handleD = d
        handle.setLocal(position = at, scale = Vec3(d, d, d))
        handle.enabled = true
    }

    /** The tray: a ring 1.0 m above the anchor on a thin stand down to the floor (v0.1.1: never in front of the head). */
    fun showTray(at: Vec3?, floorY: Float) {
        tray.set(at != null)
        if (at != null) {
            tray.entity.setLocal(position = at + Vec3(0f, 0.001f, 0f))
            stand.draw(Vec3(at.x, floorY, at.z), at, 0.012f, Vec3(1f, 0f, 0f))
        } else stand.hide()
    }

    /** Taste D20: see-through while the guess sits between the eyes and the question card. */
    fun setFade(on: Boolean) {
        if (on == faded) return
        faded = on
        val k = if (on) FADE else 1f
        pivot.setOpacity(k)
        clipped.setOpacity(k)
        clippedLines.setOpacity(k)
        handle.setOpacity(k)
    }

    private var faded = false
    private val stand = Strip(root, MeshFactory.unitBand("trayStand"), m.tray, "trayStand")

    fun showFloorMark(at: Vec3?) {
        floorMark.set(at != null)
        if (at != null) floorMark.entity.setLocal(position = at + Vec3(0f, 0.004f, 0f))
    }

    fun destroy() {
        runCatching { pivot.destroy(true) }
        runCatching { handle.destroy(true) }
        rod.destroy()
        tray.destroy()
        stand.destroy()
        floorMark.destroy()
        clipped.destroy()
        clippedLines.destroy()
    }

    private companion object {
        /** 55 % x 0.5 = 27.5 %: at most 30 % while in the view cone to the card (D20). */
        const val FADE = 0.5f

        fun unitLines(s: Shape): MeshData {
            val b = Body(s, Vec3.ZERO, 1f)
            return MeshFactory.ribbons(Clip.wire(b, null), 0.007f) { p -> p.normalized() }
        }
    }
}

/** Small floor things: the ray ghost ring (S04) and the wall-snap frame (S06). */
internal class MarksView(root: Entity, m: SceneMaterials, shared: SharedResources) {
    private val ghost = Toggle(ModelEntity(shared.keep(MeshFactory.ring(0.11f, 0.125f, "ghostRing", dashes = 16)), m.ghost).named("ghost").also { root.addChild(it) })
    private val snap = WorldMesh(root, m.snapFrame, "snapFrame")

    init {
        ghost.set(false)
    }

    /**
     * The user's floor marker (taste D1): a solid ring and a short post, interaction blue; content goes there on
     * [Move to marker].
     */
    private val marker = Toggle(Entity().named("marker").also { root.addChild(it) })
    private val markerPost = Strip(root, MeshFactory.unitBand("markerPost"), m.floorMark, "markerPost")

    init {
        marker.entity.addChild(ModelEntity(shared.keep(MeshFactory.ring(0.14f, 0.17f, "markerRing")), m.floorMark))
        marker.entity.addChild(ModelEntity(shared.keep(MeshFactory.disc(0.035f, "markerDot")), m.floorMark))
        marker.set(false)
    }

    fun marker(at: Vec3?) {
        marker.set(at != null)
        if (at == null) {
            markerPost.hide()
            return
        }
        marker.entity.setLocal(position = at + Vec3(0f, 0.008f, 0f))
        markerPost.draw(at, at + Vec3(0f, 0.35f, 0f), 0.02f, Vec3(1f, 0f, 0f))
    }

    fun ghost(at: Vec3?) {
        ghost.set(at != null)
        if (at != null) ghost.entity.setLocal(position = at + Vec3(0f, 0.006f, 0f))
    }

    /** Dashed footprint of the snapped body on the floor. */
    fun snapFrame(body: Body?, floorY: Float) {
        if (body == null) {
            snap.hide()
            return
        }
        val lines = ArrayList<List<Vec3>>()
        val y = floorY + 0.005f
        // drawn 6 cm outside the footprint: the body's own base would hide it otherwise
        val grow = 0.06f
        val outline: List<Vec3> =
            if (body.shape == Shape.SPHERE) {
                (0..48).map { i ->
                    val a = 2 * Math.PI * i / 48
                    Vec3(body.center.x + (kotlin.math.cos(a) * (body.half + grow)).toFloat(), y, body.center.z + (kotlin.math.sin(a) * (body.half + grow)).toFloat())
                }
            } else {
                val h = body.half + grow
                listOf(Vec3(-h, 0f, -h), Vec3(h, 0f, -h), Vec3(h, 0f, h), Vec3(-h, 0f, h), Vec3(-h, 0f, -h)).map { body.world(it).let { p -> Vec3(p.x, y, p.z) } }
            }
        // dashes: every other 8 cm piece
        for (i in 0 until outline.size - 1) {
            val a = outline[i]
            val b = outline[i + 1]
            val n = maxOf(1, (a.distanceTo(b) / 0.08f).toInt())
            for (k in 0 until n step 2) lines += listOf(a.lerp(b, k.toFloat() / n), a.lerp(b, (k + 1).toFloat() / n))
        }
        snap.show(MeshFactory.ribbons(lines, 0.02f) { Vec3.UP })
    }

    fun destroy() {
        ghost.destroy()
        marker.destroy()
        markerPost.destroy()
        snap.destroy()
    }
}

/** The room box drawn as thin edges (design `entities3d.virtualRoomFrame`); hot while the room page is open. */
internal class RoomView(root: Entity, private val m: SceneMaterials) {
    private val frame = WorldMesh(root, m.roomEdge, "roomFrame")
    private val hotFrame = WorldMesh(root, m.roomEdgeHot, "roomFrameHot")
    private var key = ""

    fun show(room: RoomBox?, visible: Boolean, hot: Boolean) {
        if (room == null || !visible) {
            frame.hide()
            hotFrame.hide()
            return
        }
        val k = "$room:$hot"
        if (k == key && (frame.visible || hotFrame.visible)) return
        key = k
        val c = room.corners()
        val up = Vec3(0f, room.height, 0f)
        val lines = ArrayList<List<Vec3>>()
        for (i in 0 until 4) {
            lines += listOf(c[i] + Vec3(0f, 0.004f, 0f), c[(i + 1) % 4] + Vec3(0f, 0.004f, 0f))
            lines += listOf(c[i], c[i] + up)
            if (room.hasCeiling) lines += listOf(c[i] + up, c[(i + 1) % 4] + up)
        }
        val data = MeshFactory.ribbons(lines, 0.01f) { p -> if (kotlin.math.abs(p.y - room.floorY) < 0.01f || kotlin.math.abs(p.y - room.ceilingY) < 0.01f) Vec3.UP else (Vec3(p.x - room.center.x, 0f, p.z - room.center.z)).normalized() }
        if (hot) {
            frame.hide()
            hotFrame.show(data)
        } else {
            hotFrame.hide()
            frame.show(data)
        }
    }

    fun destroy() {
        frame.destroy()
        hotFrame.destroy()
    }
}

/**
 * The truth (design `entities3d.trueBody` + `cutRings`) and the guess wireframe beside it. The growth
 * uses 8 pre-built levels (each clipped by the room, with its own rings), swapped in turn; the final
 * level gets the shape lines and, while the viewer is inside, the inner surface (25 %).
 */
internal class TruthView(root: Entity, m: SceneMaterials) {
    val outer = WorldMesh(root, m.trueOuter, "trueOuter")
    val inner = WorldMesh(root, m.trueInner, "trueInner")
    val lines = WorldMesh(root, m.trueLines, "trueLines")
    val rings = WorldMesh(root, m.ring, "cutRings")
    val wire = WorldMesh(root, m.wire, "guessWire")

    /** A tap / drag target the size of the true body (outer mesh has no collider). */
    val target: Entity = Entity().named("trueTarget").also { root.addChild(it) }

    init {
        listOf(outer, inner, lines, rings, wire).forEach { it.hide() }
        target.enabled = false
    }

    fun setTarget(body: Body?) {
        if (body == null) {
            target.enabled = false
            return
        }
        target.setLocal(position = body.center, rotation = yawRotation(body.yawDeg))
        val shape = if (body.shape == Shape.SPHERE) ShapeResource.createSphere(body.half) else ShapeResource.createBox(Vec3(body.size, body.size, body.size).sdk())
        target.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = PhysicsMaterialResource()))
        if (target.components[InteractableComponent::class.java] == null) target.components.set(InteractableComponent())
        target.enabled = true
    }

    private var faded = false

    /** Taste D20: see-through (70 % x 0.4 = 28 %) while the truth sits between the eyes and the question card. */
    fun setFade(on: Boolean) {
        if (on == faded) return
        faded = on
        val k = if (on) 0.4f else 1f
        listOf(outer, lines, wire).forEach { it.setOpacity(k) }
    }

    fun hideAll() {
        listOf(outer, inner, lines, rings, wire).forEach { it.hide() }
        target.enabled = false
    }

    fun destroy() {
        listOf(outer, inner, lines, rings, wire).forEach { it.destroy() }
        runCatching { target.destroy(true) }
    }
}
