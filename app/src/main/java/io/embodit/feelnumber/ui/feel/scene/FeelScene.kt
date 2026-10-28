package io.embodit.feelnumber.ui.feel.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.feelnumber.domain.model.Body
import io.embodit.feelnumber.domain.model.Clip
import io.embodit.feelnumber.domain.model.MeshData
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.PointerRay
import io.embodit.feelnumber.domain.model.Quat4
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.RoomFit
import io.embodit.feelnumber.domain.model.RoomSource
import io.embodit.feelnumber.domain.model.Shape
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.platform.FeelGraph
import io.embodit.feelnumber.platform.FeelLog
import io.embodit.feelnumber.platform.tracking.HeadPose
import io.embodit.feelnumber.ui.feel.FeelEvent
import io.embodit.feelnumber.ui.feel.FeelUiState
import io.embodit.feelnumber.ui.feel.Page
import io.embodit.feelnumber.ui.feel.Phase
import io.embodit.feelnumber.ui.feel.debug.FeelProbe
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: FeelScene? = null
}

/** The one per-frame ECS system of the app. */
class FeelFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the guess (body, handle, tray, floor mark), the truth (8-level growth,
 * cut rings, inner surface), the guess wireframe, the room frame, the floor catcher and the two pooled
 * panels. Compose state comes in through [render] (idempotent); per-frame work (handle sizing, slides,
 * growth, inside check, controller ray and thumbstick, panel follow) lives in [onFrame] only. Head and
 * planes reach the ViewModel at 10 Hz / 2 Hz. Everything started in [attach] stops in [detach].
 */
class FeelScene(private val sink: (FeelEvent) -> Unit) : FeelProbe.SceneProbe {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = FeelGraph.planes
    private val headTracker = FeelGraph.head
    private val controller = FeelGraph.controller

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var shared: SharedResources? = null
    private var materials: SceneMaterials? = null
    private var rig: PanelRig? = null
    private var guessView: GuessView? = null
    private var truth: TruthView? = null
    private var marks: MarksView? = null
    private var roomView: RoomView? = null
    private var floorCatcher: Entity? = null
    private var attached = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null
    private var truthJob: Job? = null

    private var ui = FeelUiState()
    private var head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var lastHeadSentMs = 0L
    private var lastPlaneRev = -1
    private var lastPlanesEmitMs = 0L

    // guess display: slides between placements (0.4 s) unless resizing / dragging
    private var shownCenter: Vec3? = null
    private var slideFrom: Vec3? = null
    private var slideTo: Vec3? = null
    private var slideStartMs = 0L
    private var guessKey = ""
    private var clipKey = ""

    // truth: growth levels, the final key, the wireframe slide
    private var levels: List<MeshResource> = emptyList()
    private var levelRings: List<MeshData> = emptyList()
    private var levelShown = -1
    private var truthKey = ""
    private var truthReady = false
    private var finalKey = ""
    private var wireFrom: Vec3? = null
    private var wireStartMs = 0L
    private var revealDoneSent = false
    private var inside = false

    // input
    private enum class DragMode { RESIZE, MOVE_PENDING, MOVE, PAIR, ROOM }

    private class Drag(val mode0: DragMode, val baseSize: Float, val startSpot: Vec3?, val gain: Float) {
        var mode = mode0
        var totalPx = Vec3.ZERO
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
    }

    private var drag: Drag? = null
    private var stickResizing = false
    private var stickSize = 0f
    private var lastFrameMs = 0L
    private var aimingSent = false
    private var debugRay: PointerRay? = null

    // panels
    private var cardYaw = 0f
    private var cardPos: Vec3? = null
    private var cardOutSinceMs = NEVER
    private var cardSlideStartMs = NEVER
    private var cardFromPos = Vec3.ZERO
    private var cardFromYaw = 0f
    private var panelPressed = false
    private var lastPanelPressMs = NEVER

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin: move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        FeelLog.i("STAGE root offset=${FeelLog.pos(offset.domain())}")
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h)
        }
        headTracker.start()
        controller.start()
        planeTracker.start()
        ActiveScene.scene = this
        bindPanels(attachments)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) FeelLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        guessView = GuessView(root, m, sh)
        yield()
        truth = TruthView(root, m)
        marks = MarksView(root, m, sh)
        roomView = RoomView(root, m)
        floorCatcher =
            Entity().named("floorCatcher").also {
                root.addChild(it)
                it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(30f, 0.02f, 30f))), physicsMaterial = PhysicsMaterialResource()))
                it.components.set(InteractableComponent())
                it.enabled = false
            }
        attached = true
        FeelLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(FeelEvent.SceneReady)
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        truthJob?.cancel()
        buildJob = null
        truthJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        guessView?.destroy()
        truth?.destroy()
        marks?.destroy()
        roomView?.destroy()
        runCatching { floorCatcher?.destroy(true) }
        levels.forEach { runCatching { it.close() } }
        levels = emptyList()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        rig = null
        guessView = null
        truth = null
        marks = null
        roomView = null
        floorCatcher = null
        hasHead = false
        lastPlaneRev = -1
        truthKey = ""
        finalKey = ""
        guessKey = ""
        clipKey = ""
        shownCenter = null
        cardPos = null
        drag = null
        FeelLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: FeelUiState, force: Boolean = false) {
        ui = state
        if (!attached) return
        val gv = guessView ?: return
        val q = state.question
        val room = state.room
        val guessing = state.phase == Phase.GUESS
        // ---- the guess
        gv.setShape(q.shape)
        val body = state.guessBody
        gv.setVisible(guessing && body != null)
        if (guessing && body != null) {
            val key = "${body.center}:${body.size}:${body.yawDeg}"
            if (force || key != guessKey) {
                guessKey = key
                val smooth = !state.guess.resizing && !state.guess.dragging && shownCenter != null && shownCenter!!.distanceTo(body.center) > 0.03f &&
                    abs((shownCenter!!.y - body.size / 2f) - (body.center.y - body.size / 2f)) + shownCenter!!.distanceTo(body.center) > 0.03f
                if (smooth && sizeKeyStable(body)) {
                    slideFrom = shownCenter
                    slideTo = body.center
                    slideStartMs = SystemClock.elapsedRealtime()
                } else {
                    slideFrom = null
                    shownCenter = body.center
                    gv.place(body.center, body.size, body.yawDeg)
                }
            }
            // a body that pokes out of the room gets the clipped mesh once it stands still
            val cut = room != null && !state.guess.resizing && !state.guess.dragging && Clip.isCut(room, body)
            val ck = if (cut) "$key:$room" else ""
            if (ck != clipKey) {
                clipKey = ck
                if (cut) gv.setClipped(body, room) else gv.unclip()
            }
            gv.showTray(if (!state.guess.onFloor) state.guess.tray else null)
            gv.showFloorMark(state.guess.spot)
        } else {
            gv.showTray(null)
            gv.showFloorMark(null)
            if (clipKey.isNotEmpty()) {
                clipKey = ""
                gv.unclip()
            }
            guessKey = ""
        }
        // ---- the truth
        renderTruth(state)
        // ---- room frame, snap frame, floor catcher
        val frameOn = state.roomSource == RoomSource.VIRTUAL || state.page == Page.ROOM
        roomView?.show(room, frameOn, hot = state.page == Page.ROOM)
        marks?.snapFrame(if (guessing && state.guess.dragging && state.guess.snapped) body else null, state.floorY)
        val catcher = floorCatcher
        if (catcher != null && room != null) {
            catcher.setLocal(position = Vec3(head.position.x, room.floorY - 0.01f, head.position.z))
            catcher.enabled = (guessing && state.page == Page.MAIN) || state.page == Page.ROOM
        }
        syncPanels(state)
    }

    private var lastSize = -1f

    /** A slide only for a pure move (the size did not change since the last frame). */
    private fun sizeKeyStable(body: Body): Boolean {
        val same = abs(body.size - lastSize) < 1e-4f
        lastSize = body.size
        return same || lastSize < 0f
    }

    private fun renderTruth(state: FeelUiState) {
        val t = truth ?: return
        val r = state.reveal
        val room = state.room
        val tb = state.trueBody
        val wb = state.wireBody
        if (r == null || tb == null || wb == null || room == null) {
            if (truthKey.isNotEmpty()) {
                truthKey = ""
                finalKey = ""
                truthJob?.cancel()
                t.hideAll()
                marks?.ghost(null)
                levels.forEach { runCatching { it.close() } }
                levels = emptyList()
                levelRings = emptyList()
                levelShown = -1
                truthReady = false
            }
            return
        }
        // a new reveal: build the 8 growth levels off the main thread, then the meshes
        val revealKey = "${state.questionId}:${r.startedAtMs}"
        if (!truthKey.startsWith(revealKey)) {
            truthKey = revealKey
            finalKey = ""
            truthReady = false
            levelShown = -1
            revealDoneSent = false
            inside = false
            wireFrom = state.guessBody?.center ?: wb.center
            wireStartMs = SystemClock.elapsedRealtime()
            t.wire.show(MeshFactory.ribbons(Clip.wire(wb, room), (wb.size * 0.006f).coerceIn(0.004f, 0.025f)) { p -> (p - wb.center).normalized() })
            t.wire.setOffset((wireFrom ?: wb.center) - wb.center)
            buildLevels(tb, room)
        }
        val fk = "$tb:$wb:$room:${r.hidden}"
        if (truthReady && r.frozenT == null && state.phase == Phase.RESULT && fk != finalKey) {
            val first = finalKey.isEmpty()
            finalKey = fk
            showFinal(tb, wb, room, r.hidden, rebuildWire = !first)
        }
        t.setTarget(if (state.phase == Phase.RESULT && !r.inside) tb else null)
    }

    private fun buildLevels(tb: Body, room: RoomBox) {
        truthJob?.cancel()
        levels.forEach { runCatching { it.close() } }
        levels = emptyList()
        truthJob =
            scope.launch {
                val data =
                    withContext(Dispatchers.Default) {
                        (1..LEVELS).map { k ->
                            val s = tb.size * k / LEVELS
                            val b = Body(tb.shape, Vec3(tb.center.x, tb.bottomY + s / 2f, tb.center.z), s, tb.yawDeg)
                            Clip.body(b, room) to MeshFactory.segments(Clip.rings(room, b), RING_W)
                        }
                    }
                val made = ArrayList<MeshResource>()
                for ((mesh, _) in data) {
                    made += MeshFactory.of(mesh, "trueLevel${made.size}")
                    yield()
                }
                levels = made
                levelRings = data.map { it.second }
                truthReady = true
                FeelLog.i("REVEAL levels ready n=${made.size} tris=${data.last().first.triangles}")
            }
    }

    private fun showFinal(tb: Body, wb: Body, room: RoomBox, hidden: Boolean, rebuildWire: Boolean) {
        val t = truth ?: return
        val data = Clip.body(tb, room)
        if (hidden) t.outer.hide() else t.outer.show(data)
        t.lines.show(MeshFactory.ribbons(Clip.wire(tb, room), (tb.size * 0.003f).coerceIn(0.002f, 0.015f)) { p -> (p - tb.center).normalized() })
        if (hidden) t.lines.hide()
        t.rings.show(MeshFactory.segments(Clip.rings(room, tb), RING_W))
        if (inside && !hidden) t.inner.show(data.reversed()) else t.inner.hide()
        if (rebuildWire) {
            t.wire.show(MeshFactory.ribbons(Clip.wire(wb, room), (wb.size * 0.006f).coerceIn(0.004f, 0.025f)) { p -> (p - wb.center).normalized() })
            t.wire.setOffset(Vec3.ZERO)
            wireFrom = null
        }
        levelShown = LEVELS
    }

    /** The growth (S07), every frame: level by eased progress, the wireframe slide, the end. */
    private fun revealFrame(now: Long) {
        val t = truth ?: return
        val r = ui.reveal ?: return
        if (truthKey.isEmpty()) return
        // wireframe slide (0.3 s) onto the truth's axis
        val wb = ui.wireBody
        val from = wireFrom
        if (wb != null && from != null) {
            val q = ((now - wireStartMs) / WIRE_SLIDE_MS).coerceIn(0f, 1f)
            t.wire.setOffset((from - wb.center) * (1f - Ease.inOutCubic(q)))
            if (q >= 1f) wireFrom = null
        }
        if (!truthReady) return
        if (ui.phase == Phase.RESULT && r.frozenT == null) return
        val raw = r.frozenT ?: ((now - r.startedAtMs) / GROW_MS).coerceIn(0f, 1f)
        val eased = Ease.outCubic(raw)
        val level = (kotlin.math.ceil(eased * LEVELS).toInt()).coerceIn(1, LEVELS)
        if (level != levelShown && levels.size == LEVELS) {
            levelShown = level
            if (!r.hidden) t.outer.showMesh(levels[level - 1])
            t.rings.show(levelRings[level - 1])
            t.lines.hide()
        }
        if (raw >= 1f && r.frozenT == null && !revealDoneSent) {
            revealDoneSent = true
            sink(FeelEvent.RevealDone)
        }
    }

    private fun syncPanels(state: FeelUiState) {
        val rig = rig ?: return
        val r = state.reveal
        val tagOn = state.phase == Phase.RESULT && r != null && !r.inside && !r.hidden && state.page == Page.MAIN
        if (!tagOn) rig[PanelSpec.TAG]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.2f)
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        placeCard(rig, now)
        if (!attached) return
        pollPlanes(now)
        guessFrame(now)
        revealFrame(now)
        insideFrame()
        placeTag(rig)
        controllerFrame(dt)
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(FeelEvent.HeadSample(head.position, head.forward))
        }
        rig.faceViewer(head.position)
    }

    private fun guessFrame(now: Long) {
        val gv = guessView ?: return
        val body = ui.guessBody
        if (ui.phase != Phase.GUESS || body == null) {
            gv.placeHandle(false, head.position, ui.floorY)
            return
        }
        val from = slideFrom
        val to = slideTo
        if (from != null && to != null) {
            val q = ((now - slideStartMs) / GUESS_SLIDE_MS).coerceIn(0f, 1f)
            val c = from.lerp(to, Ease.inOutCubic(q))
            shownCenter = c
            gv.place(c, body.size, body.yawDeg)
            if (q >= 1f) slideFrom = null
        }
        val headInside = body.contains(head.position)
        gv.setTappable(!headInside)
        gv.placeHandle(ui.page == Page.MAIN, head.position, ui.floorY)
    }

    private fun insideFrame() {
        val tb = ui.trueBody ?: return
        if (ui.phase != Phase.RESULT || !hasHead) return
        val now = Clip.headInside(tb, head.position, inside)
        if (now != inside) {
            inside = now
            val room = ui.room
            val t = truth
            if (room != null && t != null && levelShown == LEVELS) {
                if (inside && ui.reveal?.hidden != true) t.inner.show(Clip.body(tb, room).reversed()) else t.inner.hide()
            }
            sink(FeelEvent.InsideChanged(now))
        }
    }

    /** The question card: lower left of the view, lazy follow (out of a 35 degree window for 0.5 s -> slides back in 0.6 s). */
    private fun placeCard(rig: PanelRig, now: Long) {
        if (!hasHead) return
        val slot = rig[PanelSpec.CARD] ?: return
        val headYaw = yawOf(head.forward)
        val (wantPos, wantYaw) = cardPose(headYaw)
        val pos = cardPos
        if (pos == null) {
            cardPos = wantPos
            cardYaw = wantYaw
        } else if (cardSlideStartMs != NEVER) {
            val q = ((now - cardSlideStartMs) / CARD_SLIDE_MS).coerceIn(0f, 1f)
            val e = Ease.inOutCubic(q)
            cardPos = cardFromPos.lerp(wantPos, e)
            cardYaw = cardFromYaw + angleDiff(wantYaw, cardFromYaw) * e
            if (q >= 1f) cardSlideStartMs = NEVER
        } else {
            val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS)
            val off = abs(angleDiff(headYaw, cardYaw)) > FOLLOW_DEG || pos.distanceTo(wantPos) > 0.6f
            if (!off || hold) {
                cardOutSinceMs = NEVER
            } else if (cardOutSinceMs == NEVER) {
                cardOutSinceMs = now
            } else if (now - cardOutSinceMs >= OUT_MS) {
                cardFromPos = pos
                cardFromYaw = cardYaw
                cardSlideStartMs = now
                cardOutSinceMs = NEVER
                FeelLog.i("FOLLOW card slide")
            }
        }
        val p = cardPos ?: return
        // turned about the vertical only, towards the head
        val to = Vec3(head.position.x - p.x, 0f, head.position.z - p.z)
        val faceYaw = atan2(to.x.toDouble(), to.z.toDouble()).toFloat()
        slot.place(p, Quat4.axisAngle(Vec3.UP, faceYaw), PanelSpec.CARD_SCALE)
    }

    /** Designed card pose for a head heading [yaw] (radians, 0 = -Z): left and down of the view. */
    private fun cardPose(yaw: Float): Pair<Vec3, Float> {
        val a = yaw + Math.toRadians(CARD_LEFT_DEG.toDouble()).toFloat()
        val down = Math.toRadians(CARD_DOWN_DEG.toDouble())
        val flat = (CARD_DISTANCE_M * cos(down)).toFloat()
        val dir = Vec3(-sin(a), 0f, -cos(a))
        val p = head.position + dir * flat - Vec3(0f, (CARD_DISTANCE_M * sin(down)).toFloat(), 0f)
        return p to yaw
    }

    /** Heading of a direction, radians: 0 = -Z, positive = turning left (counter-clockwise from above). */
    private fun yawOf(v: Vec3): Float = atan2(-v.x.toDouble(), -v.z.toDouble()).toFloat()

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    /** Size tag above the truth (or under the ceiling), constant visual size (0.45 - 6 m). */
    private fun placeTag(rig: PanelRig) {
        val r = ui.reveal ?: return
        val tb = ui.trueBody ?: return
        val room = ui.room ?: return
        if (ui.phase != Phase.RESULT || r.inside || r.hidden || ui.page != Page.MAIN) return
        val (_, hdp) = PanelSpec.TAG_SIZE
        // above the truth, or above the guess wireframe when that is the taller one on the same axis (tray truths)
        val wb = ui.wireBody
        val top = if (wb != null && Vec3(wb.center.x - tb.center.x, 0f, wb.center.z - tb.center.z).length < 0.05f) maxOf(tb.topY, wb.topY) else tb.topY
        var at = Vec3(tb.center.x, top + 0.15f, tb.center.z)
        if (room.hasCeiling && tb.topY > room.ceilingY - 0.1f) {
            val toHead = RoomBox.level(head.position - tb.center)
            at = Vec3(tb.center.x, room.ceilingY - 0.15f, tb.center.z) + toHead * (tb.half * 0.8f)
        }
        val dist = head.position.distanceTo(at).coerceIn(0.45f, 6f)
        val k = dist / TAG_REFERENCE_M
        rig[PanelSpec.TAG]?.show(at + Vec3(0f, hdp * PanelRig.DP_M * k / 2f, 0f), k)
    }

    private fun pollPlanes(now: Long) {
        val rev = planeTracker.revision
        if (rev == lastPlaneRev || now - lastPlanesEmitMs < PLANES_EMIT_MS || !hasHead) return
        lastPlanesEmitMs = now
        lastPlaneRev = rev
        val planes = planeTracker.detected
        val room = RoomFit.fromPlanes(planes, head.position, head.forward)
        FeelLog.i("PLANES n=${planes.size} floor=${planes.count { it.kind == io.embodit.feelnumber.domain.model.SurfaceKind.FLOOR }} wall=${planes.count { it.kind == io.embodit.feelnumber.domain.model.SurfaceKind.WALL }} ceiling=${planes.count { it.kind == io.embodit.feelnumber.domain.model.SurfaceKind.CEILING }} room=${room?.label ?: "-"}")
        sink(FeelEvent.ScanChanged(room))
    }

    /** Controller: the ghost ring where the ray meets the floor (S04), thumbstick turn / resize (I3, I7). */
    private fun controllerFrame(dt: Float) {
        val sample = if (debugRay != null) null else controller.sample()
        val ray = debugRay ?: sample?.ray
        val room = ui.room
        val gv = guessView
        val sizing = ui.phase == Phase.GUESS && ui.page == Page.MAIN
        var ghostAt: Vec3? = null
        if (sizing && ray != null && room != null && gv != null && drag == null) {
            val overHandle = hitsSphere(ray, gv.handleAt, gv.handleD * 0.75f)
            val overBody = ui.guessBody?.let { hitsSphere(ray, it.center, it.half * 1.1f) } ?: false
            if (!overHandle && !overBody && ray.direction.y < -0.02f) {
                val t = (room.floorY - ray.origin.y) / ray.direction.y
                if (t in 0.2f..15f) ghostAt = ray.origin + ray.direction * t
            }
        }
        marks?.ghost(ghostAt)
        if ((ghostAt != null) != aimingSent) {
            aimingSent = ghostAt != null
            sink(FeelEvent.AimingFloor(aimingSent))
        }
        // thumbstick
        val s = sample
        val axis = if (s == null) 0 else Placement.stickAxis(s.stickX, s.stickY)
        if (axis == 2 && sizing && s != null) {
            if (!stickResizing) {
                stickResizing = true
                stickSize = ui.guess.size
                sink(FeelEvent.ResizeStarted)
            }
            stickSize = Placement.stickScale(stickSize, s.stickY, dt)
            sink(FeelEvent.ResizeTo(stickSize))
        } else if (stickResizing) {
            stickResizing = false
            sink(FeelEvent.ResizeEnded)
        }
        if (axis == 1 && s != null && (sizing || ui.page == Page.ROOM)) {
            // right = clockwise seen from above (yaw grows towards +x)
            sink(FeelEvent.Turn(s.stickX * Placement.STICK_YAW_DEG_S * dt))
        }
    }

    private fun hitsSphere(ray: PointerRay, c: Vec3, r: Float): Boolean {
        val oc = c - ray.origin
        val t = oc.dot(ray.direction)
        if (t < 0f) return false
        val d2 = oc.dot(oc) - t * t
        return d2 <= r * r
    }

    // =========================================================================================
    // system pointer (gaze + pinch, controller trigger, emulator mouse)
    // =========================================================================================

    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        when {
            target == null -> FeelLog.i("TAP none")
            target === floorCatcher && stage != null -> {
                FeelLog.i("TAP floor stage=${FeelLog.pos(stage)} kind=${value.interactionKind}")
                sink(FeelEvent.FloorTapped(stage))
            }
            else -> FeelLog.i("TAP other target=$name stage=${FeelLog.pos(stage)}")
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity ?: return
                val gv = guessView ?: return
                val name = runCatching { target.getName() }.getOrNull() ?: "none"
                val s = ui
                val stage = start?.let { toStage(it.x, it.y, it.z) }
                val d: Drag? =
                    when {
                        s.phase == Phase.GUESS && s.page == Page.MAIN && (gv.isHandle(target) || (gv.isBody(target) && s.guess.size < 0.1f)) ->
                            Drag(DragMode.RESIZE, s.guess.size, null, 1f).also { sink(FeelEvent.ResizeStarted) }
                        s.phase == Phase.GUESS && s.page == Page.MAIN && gv.isBody(target) && s.guess.onFloor ->
                            Drag(DragMode.MOVE_PENDING, s.guess.size, s.guess.spot, gainFor(s.guess.spot))
                        s.phase == Phase.RESULT && target === truth?.target ->
                            Drag(DragMode.PAIR, 0f, s.reveal?.spot, gainFor(s.reveal?.spot))
                        s.page == Page.ROOM && target === floorCatcher ->
                            Drag(DragMode.ROOM, 0f, stage, gainFor(stage))
                        else -> null
                    }
                FeelLog.i("DRAG start target=$name mode=${d?.mode ?: "none"}")
                drag = d
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                when (d.mode) {
                    DragMode.RESIZE -> sink(FeelEvent.ResizeTo(Placement.handleScale(d.baseSize, d.hand.y)))
                    DragMode.MOVE_PENDING -> {
                        if (d.hand.length >= MOVE_START_M) {
                            d.mode = DragMode.MOVE
                            sink(FeelEvent.DragStarted)
                        }
                    }
                    DragMode.MOVE -> d.startSpot?.let { sp -> sink(FeelEvent.DragTo(sp + Vec3(d.hand.x, 0f, d.hand.z) * d.gain)) }
                    DragMode.PAIR -> {
                        val step = (d.hand - d.prevHand)
                        sink(FeelEvent.PairMoved(Vec3(step.x, 0f, step.z) * d.gain))
                    }
                    DragMode.ROOM -> {
                        val step = (d.hand - d.prevHand)
                        sink(FeelEvent.RoomShift(Vec3(step.x, 0f, step.z) * d.gain))
                    }
                }
                d.prevHand = d.hand
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                FeelLog.i("DRAG $phase mode=${d.mode} hand=${FeelLog.pos(d.hand)}")
                when (d.mode) {
                    DragMode.RESIZE -> sink(FeelEvent.ResizeEnded)
                    DragMode.MOVE -> sink(FeelEvent.DragEnded)
                    else -> Unit
                }
            }
        }
    }

    /** Hand travel -> object travel: the object is further than the hand, so the move is scaled by distance / arm. */
    private fun gainFor(at: Vec3?): Float = if (at == null) 1f else (Vec3(at.x - head.position.x, 0f, at.z - head.position.z).length / ARM_M).coerceIn(1f, 12f)

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { FeelLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${FeelLog.pos5(head.position)} ${FeelLog.quat5(head.rotation)} fwd=${FeelLog.pos5(head.forward)} valid=$hasHead"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f", slot.id, FeelLog.pos5(slot.position), FeelLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp)
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.detected.map { p -> "PLANE ${p.label} kind=${p.kind} center=${FeelLog.pos(p.centroid)} normal=${FeelLog.pos(p.normal)} verts=${p.vertices.size}" }

    override fun parkPanel(): String {
        cardPos = null
        return "FOLLOW card reset"
    }

    override fun setNoScan(on: Boolean): String {
        planeTracker.ignoreDetected = on
        return "NOSCAN $on"
    }

    override fun setDebugRay(origin: Vec3?, direction: Vec3?): String {
        debugRay = if (origin != null && direction != null && direction.length > 1e-3f) PointerRay(origin, direction.normalized()) else null
        return "RAY ${debugRay?.let { "${FeelLog.pos(it.origin)} -> ${FeelLog.pos(it.direction)}" } ?: "off"}"
    }

    override fun headPose(): Pair<Vec3, Vec3> = head.position to head.forward

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val HEAD_SAMPLE_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val LEVELS = 8
        private const val GROW_MS = 1600f
        private const val WIRE_SLIDE_MS = 300f
        private const val GUESS_SLIDE_MS = 400f
        private const val RING_W = 0.02f
        private const val MOVE_START_M = 0.02f
        private const val ARM_M = 0.45f
        private const val TAG_REFERENCE_M = 1.0f

        /** Card pose (design `panels.questionCard`): 30 degrees left, 20 degrees down, 1.0 m. */
        private const val CARD_LEFT_DEG = 30f
        private const val CARD_DOWN_DEG = 20f
        private const val CARD_DISTANCE_M = 1.0f
        private const val FOLLOW_DEG = 0.61f // 35 degrees
        private const val OUT_MS = 500L
        private const val CARD_SLIDE_MS = 600f
    }
}
