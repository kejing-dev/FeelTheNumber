package io.embodit.feelnumber.ui.feel.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.feelnumber.domain.model.Clip
import io.embodit.feelnumber.domain.model.MeshData
import io.embodit.feelnumber.domain.model.Quat4
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.platform.FeelLog
import io.embodit.feelnumber.ui.theme.FeelColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (same approach as the Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children (fades). */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f)
    else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity {
    runCatching { setName(name) }
    return this
}

/** A box collider of [size] (local metres) that the system pointer can tap. */
internal fun Entity.tapBox(size: Vec3, offset: Vec3 = Vec3.ZERO) {
    val shape = ShapeResource.createBox(size.sdk()).let { if (offset == Vec3.ZERO) it else it.offsetByTranslation(offset.sdk()) }
    components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = PhysicsMaterialResource()))
    components.set(InteractableComponent())
}

/**
 * Resources shared by entities that come and go (materials, meshes). The SDK releases a resource when
 * its last user entity is destroyed; shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Scene-lifetime materials, one per appearance. All unlit, tone mapping off so the design colours are exact. */
internal class SceneMaterials(private val shared: SharedResources) {
    /** Guess body: guessBlue 55 %; its wireframe after the reveal 90 %. */
    val guess: Material = sheer(FeelColors.GuessBlue, 0.55f)
    val guessHot: Material = sheer(FeelColors.GuessBlue, 0.75f)
    val guessLines: Material = sheer(FeelColors.HandleWhite, 0.35f)
    val wire: Material = sheer(FeelColors.GuessBlue, 0.90f)

    /** True body: trueTeal 70 % outside (back faces culled), 25 % inner surface (taste D14), shape lines. */
    val trueOuter: Material = sheer(FeelColors.TrueTeal, 0.70f, culling = MaterialCullingMode.BACK)
    val trueInner: Material = sheer(FeelColors.TrueTeal, 0.25f, culling = MaterialCullingMode.BACK)
    val trueLines: Material = sheer(FeelColors.TrueTealDeep, 0.55f)

    /** Cut rings: cutAmber, opaque. */
    val ring: Material = unlit(FeelColors.CutAmber, twoSided = true)

    val handle: Material = unlit(FeelColors.HandleWhite, twoSided = true)
    val handleRod: Material = sheer(FeelColors.HandleWhite, 0.8f)
    val tray: Material = sheer(FeelColors.HandleWhite, 0.50f)
    val floorMark: Material = sheer(FeelColors.Interaction, 0.90f)
    val ghost: Material = sheer(FeelColors.Interaction, 0.60f)
    val snapFrame: Material = sheer(FeelColors.Interaction, 0.85f)
    val roomEdge: Material = sheer(FeelColors.HandleWhite, 0.35f)
    val roomEdgeHot: Material = sheer(FeelColors.Interaction, 0.70f)

    private fun unlit(color: Color, twoSided: Boolean = false, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
                if (!depthTest) {
                    setDepthTest(false)
                    setDepthWrite(false)
                }
            }
        )

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float, depthTest: Boolean = true, culling: MaterialCullingMode = MaterialCullingMode.NONE): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(culling)
                setDepthWrite(false)
                if (!depthTest) setDepthTest(false)
            }
        )
}

/** Programmatic meshes (`MeshModel`: positions, indices, normals, optional uv). */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)
    private val FRONT = Vector3(0f, 0f, 1f)

    /** Solid disc in the local XZ plane, facing +Y. */
    fun disc(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * radius).toFloat(), 0f, (sin(a) * radius).toFloat())
            normals += UP
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, 0.001f, radius)),
            name = name,
        )
    }

    /** Flat ring in the local XZ plane, facing +Y; with [dashes] > 0 only that many arcs are drawn. */
    fun ring(inner: Float, outer: Float, name: String, dashes: Int = 0, dashFill: Float = 0.6f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun arc(a0: Double, a1: Double, steps: Int) {
            for (i in 0 until steps) {
                val s = a0 + (a1 - a0) * i / steps
                val e = a0 + (a1 - a0) * (i + 1) / steps
                val base = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
                positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                repeat(4) { normals += UP }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
        }
        if (dashes <= 0) {
            arc(0.0, 2 * PI, segments)
        } else {
            val slot = 2 * PI / dashes
            for (d in 0 until dashes) arc(d * slot, d * slot + slot * dashFill, 3)
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(outer, 0.001f, outer)),
            name = name,
        )
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z: scaled to any length and width (needle, leaders). */
    fun unitBand(name: String): MeshResource {
        val positions = listOf(Vector3(0f, -0.5f, 0f), Vector3(1f, -0.5f, 0f), Vector3(1f, 0.5f, 0f), Vector3(0f, 0.5f, 0f))
        val normals = List(4) { FRONT }
        val indices = listOf(0, 1, 2, 0, 2, 3)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)),
            name = name,
        )
    }

    /** Closed cylinder of [radius] and [height] along +Y from y = 0. */
    fun cylinder(radius: Float, height: Float, name: String, segments: Int = 20): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        // side
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val n0 = Vector3(cos(a0).toFloat(), 0f, sin(a0).toFloat())
            val n1 = Vector3(cos(a1).toFloat(), 0f, sin(a1).toFloat())
            val base = positions.size
            positions += Vector3(n0.x * radius, 0f, n0.z * radius)
            positions += Vector3(n1.x * radius, 0f, n1.z * radius)
            positions += Vector3(n1.x * radius, height, n1.z * radius)
            positions += Vector3(n0.x * radius, height, n0.z * radius)
            normals += n0
            normals += n1
            normals += n1
            normals += n0
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        // caps
        fun cap(y: Float, up: Boolean) {
            val center = positions.size
            positions += Vector3(0f, y, 0f)
            normals += if (up) UP else Vector3(0f, -1f, 0f)
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * radius).toFloat(), y, (sin(a) * radius).toFloat())
                normals += if (up) UP else Vector3(0f, -1f, 0f)
            }
            for (i in 0 until segments) {
                val a = center + 1 + i
                val b = center + 1 + (i + 1) % segments
                if (up) {
                    indices += center
                    indices += b
                    indices += a
                } else {
                    indices += center
                    indices += a
                    indices += b
                }
            }
        }
        cap(height, true)
        cap(0f, false)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, height / 2f, 0f), halfExtent = Vector3(radius, height / 2f, radius)),
            name = name,
        )
    }

    /** A mesh of [data] (Stage metres; the entity stays at the identity). */
    fun of(data: MeshData, name: String): MeshResource =
        MeshResource.createWithMeshModel(model = model(data), bounds = bounds(data), name = name)

    /** Swaps [mesh]'s geometry for [data] (no new resource). */
    fun replace(mesh: MeshResource, data: MeshData): Boolean =
        runCatching { mesh.replaceWithMeshModel(model(data), bounds(data)) }.onFailure { FeelLog.w("MESH replace failed: $it") }.getOrDefault(false)

    private fun model(d: MeshData): MeshModel =
        MeshModel(positions = d.positions.map { it.sdk() }, triangleIndices = d.indices, normals = d.normals.map { it.sdk() })

    private fun bounds(d: MeshData): BoundingBox {
        if (d.positions.isEmpty()) return BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.001f, 0.001f, 0.001f))
        val lo = d.boundsMin()
        val hi = d.boundsMax()
        return BoundingBox(center = ((lo + hi) * 0.5f).sdk(), halfExtent = ((hi - lo) * 0.5f).let { Vec3(maxOf(it.x, 0.001f), maxOf(it.y, 0.001f), maxOf(it.z, 0.001f)) }.sdk())
    }

    /**
     * Ribbons along polylines (wireframes, cut rings, frames): each segment a flat quad of [width]
     * across, lying in the plane spanned by the segment and [faceOf] (a surface normal at that point).
     */
    fun ribbons(lines: List<List<Vec3>>, width: Float, faceOf: (Vec3) -> Vec3): MeshData {
        val pos = ArrayList<Vec3>()
        val nrm = ArrayList<Vec3>()
        val idx = ArrayList<Int>()
        for (line in lines) for (i in 0 until line.size - 1) addQuad(pos, nrm, idx, line[i], line[i + 1], faceOf(line[i]), width)
        return MeshData(pos, nrm, idx)
    }

    fun segments(segs: List<Clip.Segment>, width: Float): MeshData {
        val pos = ArrayList<Vec3>()
        val nrm = ArrayList<Vec3>()
        val idx = ArrayList<Int>()
        for (s in segs) addQuad(pos, nrm, idx, s.a, s.b, s.normal, width)
        return MeshData(pos, nrm, idx)
    }

    private fun addQuad(pos: MutableList<Vec3>, nrm: MutableList<Vec3>, idx: MutableList<Int>, a: Vec3, b: Vec3, face: Vec3, width: Float) {
        val d = b - a
        if (d.length < 1e-5f) return
        var side = face.cross(d)
        if (side.length < 1e-6f) side = Vec3.UP.cross(d)
        if (side.length < 1e-6f) side = Vec3.RIGHT.cross(d)
        side = side.normalized() * (width / 2f)
        val base = pos.size
        pos += a - side
        pos += a + side
        pos += b + side
        pos += b - side
        repeat(4) { nrm += face }
        idx += base
        idx += base + 1
        idx += base + 2
        idx += base
        idx += base + 2
        idx += base + 3
    }

    /** A box mesh (corner radius 0). */
    fun box(size: Vec3, corner: Float = 0f): MeshResource = MeshResource.createBox(size.sdk(), corner)
}

/** A straight strip between two Stage points that turns its face to [faceNormal] (needle, leaders). */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var on = true

    init {
        entity.named(name)
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun setMaterial(value: Material) = entity.setMaterial(value)

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** Time-based easing helpers. */
internal object Ease {
    fun inOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    fun outCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }
}
