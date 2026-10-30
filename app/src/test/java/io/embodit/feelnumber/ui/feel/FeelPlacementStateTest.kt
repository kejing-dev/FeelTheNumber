package io.embodit.feelnumber.ui.feel

import io.embodit.feelnumber.data.FeelRepository
import io.embodit.feelnumber.data.Saved
import io.embodit.feelnumber.domain.model.Best
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.ui.feel.scene.yawRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The placement state machine, driven through [FeelEvent]s like the scene and the card drive it (taste D1 / D10, critic
 * B4): where the guess and the truth stand after each user step, and that a new body faces the user from all four sides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeelPlacementStateTest {
    private val floor = 0f
    private val eye = Vec3(0f, 1.6f, 0f)

    private class FakeRepo : FeelRepository {
        override fun load() = Saved(Lang.EN, muted = true, best = emptyMap(), roomL = RoomBox.DEFAULT_L, roomW = RoomBox.DEFAULT_W, roomH = RoomBox.DEFAULT_H)

        override fun saveLang(lang: Lang) = Unit

        override fun saveMuted(muted: Boolean) = Unit

        override fun saveBest(best: Map<String, Best>) = Unit

        override fun saveRoomSize(l: Float, w: Float, h: Float) = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A session in the virtual room, the viewer at [at] looking along [facing], first question on screen. */
    private fun session(at: Vec3 = eye, facing: Vec3 = Vec3.AHEAD): FeelViewModel {
        val vm = FeelViewModel(FakeRepo())
        vm.onEvent(FeelEvent.HeadSample(at, facing))
        vm.onEvent(FeelEvent.ScanTimeout)
        assertEquals(Phase.GUESS, vm.state.value.phase)
        return vm
    }

    private val FeelViewModel.s get() = state.value
    private val FeelViewModel.g get() = state.value.guess

    private fun assertNear(msg: String, want: Vec3?, got: Vec3?, tol: Float = 0.01f) {
        assertNotNull(msg, want)
        assertNotNull(msg, got)
        assertTrue("$msg: want $want got $got", want!!.distanceTo(got!!) < tol)
    }

    private fun drag(vm: FeelViewModel, to: Vec3) {
        vm.onEvent(FeelEvent.DragStarted)
        vm.onEvent(FeelEvent.DragTo(to))
        vm.onEvent(FeelEvent.DragEnded)
    }

    private fun resize(vm: FeelViewModel, size: Float) {
        vm.onEvent(FeelEvent.ResizeStarted)
        vm.onEvent(FeelEvent.ResizeTo(size))
        vm.onEvent(FeelEvent.ResizeEnded)
    }

    private fun revealToResult(vm: FeelViewModel) {
        vm.onEvent(FeelEvent.Reveal)
        vm.onEvent(FeelEvent.RevealDone)
        assertEquals(Phase.RESULT, vm.s.phase)
    }

    @Test
    fun resizeReleaseKeepsBaseAndHeight() {
        val vm = session()
        val spot = vm.g.spot
        val tray = vm.g.tray
        for (size in listOf(1.2f, 3.0f, 0.1f)) {
            resize(vm, size)
            assertEquals(size, vm.g.size, 1e-4f)
            assertFalse(vm.g.resizing)
            assertNear("spot after resize $size", spot, vm.g.spot)
            assertNear("tray after resize $size", tray, vm.g.tray)
        }
    }

    @Test
    fun dragReleaseStaysWhereLetGo() {
        val vm = session()
        // left in the air
        drag(vm, Vec3(1.2f, floor + 0.6f, -2.4f))
        assertNear("air spot", Vec3(1.2f, floor, -2.4f), vm.g.spot)
        assertNear("air base", Vec3(1.2f, floor + 0.6f, -2.4f), vm.g.tray)
        assertFalse(vm.g.onFloor)
        assertFalse(vm.g.dragging)
        // M3: within 10 cm of the floor it stands on the floor
        drag(vm, Vec3(-0.8f, floor + 0.08f, -2.0f))
        assertTrue(vm.g.onFloor)
        assertNull(vm.g.tray)
        assertNear("floor spot", Vec3(-0.8f, floor, -2.0f), vm.g.spot)
        // M2: right next to a wall nothing snaps
        drag(vm, Vec3(1.9f, floor + 0.3f, -3.6f))
        assertNear("near the wall", Vec3(1.9f, floor + 0.3f, -3.6f), vm.g.tray)
        assertFalse(vm.g.snapped)
    }

    @Test
    fun nextAndAgainStartWhereTheGuessWasLeft() {
        val vm = session()
        drag(vm, Vec3(1.0f, floor + 0.5f, -2.2f))
        val spot = vm.g.spot
        val tray = vm.g.tray
        vm.onEvent(FeelEvent.Next)
        assertNear("next spot", spot, vm.g.spot)
        assertNear("next base", tray, vm.g.tray)
        revealToResult(vm)
        vm.onEvent(FeelEvent.Again)
        assertEquals(Phase.GUESS, vm.s.phase)
        assertNear("again spot", spot, vm.g.spot)
        assertNear("again base", tray, vm.g.tray)
    }

    @Test
    fun moveToMarkerInGuessPutsTheGuessOnTheMarker() {
        val vm = session()
        val marker = Vec3(2.0f, floor, -2.6f)
        vm.onEvent(FeelEvent.FloorTapped(marker))
        assertFalse(vm.s.atMarker)
        vm.onEvent(FeelEvent.BringToMarker)
        assertNear("anchor", marker, vm.g.userSpot)
        assertNear("spot", marker, vm.g.spot, 0.05f)
        assertTrue(vm.s.atMarker)
    }

    /** Critic B3: result page, marker, [Move to marker], [Next]: the new guess stands on the marker. */
    @Test
    fun moveToMarkerOnResultCarriesTheNextGuess() {
        val vm = session()
        drag(vm, Vec3(-1.0f, floor + 0.7f, -2.0f))
        revealToResult(vm)
        val marker = Vec3(1.8f, floor, -2.8f)
        vm.onEvent(FeelEvent.FloorTapped(marker))
        vm.onEvent(FeelEvent.BringToMarker)
        assertNear("pair anchor", marker, vm.s.reveal?.anchor)
        vm.onEvent(FeelEvent.Next)
        assertEquals(Phase.GUESS, vm.s.phase)
        assertNear("next guess on the marker", marker, vm.g.spot, 0.05f)
        assertNear("next anchor", marker, vm.g.userSpot)
        // the guess is on the marker: nothing more to move, the button may rest
        assertTrue(vm.s.atMarker)
        // and [Again] keeps it there as well
        revealToResult(vm)
        vm.onEvent(FeelEvent.Again)
        assertNear("again on the marker", marker, vm.g.spot, 0.05f)
    }

    @Test
    fun roomUpdateOnlyFollowsTheFloor() {
        val vm = session()
        drag(vm, Vec3(0.9f, floor + 0.6f, -2.3f))
        val newFloor = floor - 0.05f
        vm.onEvent(FeelEvent.ScanChanged(RoomBox.around(eye, Vec3.AHEAD, 4.2f, 5.1f, 2.6f, newFloor)))
        assertNear("spot on the new floor", Vec3(0.9f, newFloor, -2.3f), vm.g.spot)
        assertNear("base keeps its lift", Vec3(0.9f, newFloor + 0.6f, -2.3f), vm.g.tray)
    }

    /** The two revealed bodies: bottom centres within 1 cm, turns within 1 degree (taste D1, v0.1.6). */
    private fun assertOneBase(msg: String, vm: FeelViewModel) {
        val tb = vm.s.trueBody!!
        val wb = vm.s.wireBody!!
        val tBottom = Vec3(tb.center.x, tb.bottomY, tb.center.z)
        val wBottom = Vec3(wb.center.x, wb.bottomY, wb.center.z)
        assertTrue("$msg: bottoms $tBottom vs $wBottom", tBottom.distanceTo(wBottom) < 0.01f)
        var d = kotlin.math.abs(tb.yawDeg - wb.yawDeg) % 360f
        if (d > 180f) d = 360f - d
        assertTrue("$msg: yaw ${tb.yawDeg} vs ${wb.yawDeg}", d < 1f)
        assertEquals("$msg: real size", vm.s.question.sizeM, vm.s.reveal!!.trueSize, 1e-6f)
    }

    /**
     * Taste D1 "揭晓 / 对比同一基准" (user on v0.1.5: 「绿色在下，蓝色在上，怎么不 consistent」): in the air, on the floor, right
     * at a wall, turned - the truth grows from the guess's own base with the guess's own turn; both move together.
     */
    @Test
    fun truthAndGuessShareOneBaseInEveryPose() {
        data class Pose(val name: String, val base: Vec3, val yaw: Float)
        val poses =
            listOf(
                Pose("in the air", Vec3(1.0f, floor + 0.6f, -2.5f), 0f),
                Pose("on the floor", Vec3(-0.8f, floor + 0.05f, -2.2f), 0f),
                Pose("at the wall", Vec3(1.9f, floor + 0.3f, -3.6f), 0f),
                Pose("turned 37", Vec3(0.4f, floor + 1.1f, -2.0f), 37f),
                Pose("turned -125 on the floor", Vec3(-1.2f, floor, -1.5f), -125f),
            )
        for (p in poses) {
            val vm = session()
            drag(vm, p.base)
            if (p.yaw != 0f) vm.onEvent(FeelEvent.Turn(p.yaw - vm.g.yawDeg))
            val guessBase = (if (vm.g.onFloor) vm.g.spot else vm.g.tray)!!
            vm.onEvent(FeelEvent.Reveal)
            val r = vm.s.reveal!!
            assertOneBase("${p.name}: reveal", vm)
            assertEquals("${p.name}: lifted iff the guess was", !vm.g.onFloor, r.lifted)
            assertEquals("${p.name}: turn kept", vm.g.yawDeg, r.yawDeg, 1e-4f)
            if (!r.movedBack) assertNear("${p.name}: the pair stands on the guess's base", guessBase, r.base)
            else assertEquals("${p.name}: a shift keeps the height", guessBase.y, r.base.y, 1e-4f)
            vm.onEvent(FeelEvent.RevealDone)
            assertOneBase("${p.name}: result", vm)
            // dragged as a pair, moved to a marker, wrapped and back: still one base
            vm.onEvent(FeelEvent.PairMoved(Vec3(0.3f, 0f, -0.2f)))
            assertOneBase("${p.name}: pair drag", vm)
            assertEquals("${p.name}: pair drag keeps the lift", r.lift, vm.s.reveal!!.lift, 1e-6f)
            vm.onEvent(FeelEvent.FloorTapped(Vec3(0.5f, floor, -2.8f)))
            vm.onEvent(FeelEvent.BringToMarker)
            assertOneBase("${p.name}: move to marker", vm)
            assertEquals("${p.name}: marker keeps the lift", r.lift, vm.s.reveal!!.lift, 1e-6f)
            vm.onEvent(FeelEvent.ScanChanged(RoomBox.around(eye, Vec3.AHEAD, 4.2f, 5.1f, 2.6f, floor - 0.05f)))
            assertOneBase("${p.name}: room update", vm)
            assertEquals("${p.name}: room update keeps the lift", floor - 0.05f + r.lift, vm.s.reveal!!.base.y, 1e-4f)
        }
    }

    /** A truth that would reach the viewer moves back together with the guess (D14 within D1), and the card says so. */
    @Test
    fun aBigTruthMovesBackTogetherWithTheGuess() {
        val vm = session()
        vm.onEvent(FeelEvent.Pick("co2-1t")) // 10 m ball
        drag(vm, Vec3(0f, floor + 0.5f, -1.5f))
        val guessBase = vm.g.tray!!
        vm.onEvent(FeelEvent.Reveal)
        val r = vm.s.reveal!!
        assertTrue(r.movedBack)
        assertOneBase("shifted pair", vm)
        assertEquals("same height as the guess", guessBase.y, r.base.y, 1e-4f)
        assertTrue("further out than the guess", Vec3(r.base.x - eye.x, 0f, r.base.z - eye.z).length > Vec3(guessBase.x - eye.x, 0f, guessBase.z - eye.z).length + 1f)
        // a small truth on a floor guess stays exactly there (no stand, no push)
        vm.onEvent(FeelEvent.RevealDone)
        vm.onEvent(FeelEvent.Pick("kg-gold"))
        drag(vm, Vec3(0.6f, floor + 0.02f, -2.0f))
        vm.onEvent(FeelEvent.Reveal)
        val small = vm.s.reveal!!
        assertFalse(small.lifted)
        assertFalse(small.movedBack)
        assertNear("gold on the floor spot", Vec3(0.6f, floor, -2.0f), small.base)
        assertOneBase("small pair", vm)
    }

    /** Taste D26: settings page, [Quit] asks once, [Cancel] closes the ask, the second press quits (effect to the screen). */
    @Test
    fun quitNeedsAConfirm() {
        val vm = session()
        vm.onEvent(FeelEvent.OpenDeck)
        vm.onEvent(FeelEvent.OpenSettings)
        assertEquals("S15", vm.s.stateId.substringBefore("+"))
        // a confirm without the ask does nothing
        vm.onEvent(FeelEvent.ConfirmExit)
        assertFalse(vm.s.exiting)
        vm.onEvent(FeelEvent.AskExit)
        assertEquals("S16", vm.s.stateId.substringBefore("+"))
        vm.onEvent(FeelEvent.CancelExit)
        assertEquals("S15", vm.s.stateId.substringBefore("+"))
        assertFalse(vm.s.exiting)
        vm.onEvent(FeelEvent.AskExit)
        vm.onEvent(FeelEvent.ConfirmExit)
        assertTrue(vm.s.exiting)
        // the debug goto reaches the same two states through the same events
        val d = session()
        d.onEvent(FeelEvent.DebugGoto("S16", null, null))
        assertEquals("S16", d.s.stateId.substringBefore("+"))
        assertFalse(d.s.exiting)
    }

    /** Debug `drag_pose --ef dy 0.6` is a real drag (M1): the guess is left in the air like a hand would leave it. */
    @Test
    fun debugDragOffsetIsARealDrag() {
        val a = session()
        val b = session()
        val base = a.g.tray!!
        drag(a, base + Vec3(0.2f, -0.3f, -0.1f))
        b.onEvent(FeelEvent.DebugDragPose(true, false, Vec3(0.2f, -0.3f, -0.1f)))
        b.onEvent(FeelEvent.DebugDragPose(false, false))
        assertNear("same spot", a.g.spot, b.g.spot, 1e-4f)
        assertNear("same base", a.g.tray, b.g.tray, 1e-4f)
        assertEquals(a.g.onFloor, b.g.onFloor)
        assertFalse(b.g.dragging)
    }

    /** Critic M1: the debug `guess` command goes through the same resize events (no onFloor shortcut). */
    @Test
    fun debugGuessIsTheSameAsAResize() {
        val a = session()
        val b = session()
        resize(a, 2.0f)
        b.onEvent(FeelEvent.DebugGuess(2.0f))
        assertEquals(a.g.size, b.g.size, 1e-5f)
        assertEquals(a.g.onFloor, b.g.onFloor)
        assertNear("same spot", a.g.spot, b.g.spot, 1e-4f)
        assertEquals(a.g.tray == null, b.g.tray == null)
    }

    /** D10: from all four sides the new body's front faces the viewer, and the default anchor is 2.5 m out to the right. */
    @Test
    fun fourDirectionsFaceTheViewer() {
        val heads = listOf(Vec3(0f, 1.6f, 0f), Vec3(1.3f, 1.6f, -0.4f), Vec3(-2f, 1.6f, 1.1f), Vec3(0.5f, 1.6f, 2.5f))
        val dirs = listOf("N" to Vec3(0f, 0f, -1f), "E" to Vec3(1f, 0f, 0f), "S" to Vec3(0f, 0f, 1f), "W" to Vec3(-1f, 0f, 0f))
        for ((i, d) in dirs.withIndex()) {
            val (name, f) = d
            val at = heads[i]
            val vm = session(at, f)
            val spot = vm.g.spot!!
            val toViewer = RoomBox.level(at - spot)
            assertFrontFaces("$name start", vm.g.yawDeg, toViewer)
            // out ahead and to the right of the view, about 2.5 m
            val off = Vec3(spot.x - at.x, 0f, spot.z - at.z)
            assertTrue("$name ahead", off.dot(f) > 0.5f)
            assertTrue("$name right", f.cross(Vec3.UP).dot(off) > 0.5f)
            assertTrue("$name distance ${off.length}", off.length in 2.0f..3.2f)
            // a marker to the viewer's left: after [Move to marker] the body faces the viewer again
            val left = Vec3.UP.cross(f)
            vm.onEvent(FeelEvent.FloorTapped(Vec3(at.x, floor, at.z) + f * 2.2f + left * 1.2f))
            vm.onEvent(FeelEvent.BringToMarker)
            assertFrontFaces("$name marker", vm.g.yawDeg, RoomBox.level(at - vm.g.spot!!))
            // a new question after the viewer walked round: it faces the new place (M6)
            val moved = at + left * 1.5f - f * 0.5f
            vm.onEvent(FeelEvent.HeadSample(moved, f))
            vm.onEvent(FeelEvent.Next)
            assertFrontFaces("$name next", vm.g.yawDeg, RoomBox.level(moved - vm.g.spot!!))
        }
    }

    private fun assertFrontFaces(msg: String, yawDeg: Float, toViewer: Vec3) {
        val front = yawRotation(yawDeg).rotate(Vec3.AHEAD)
        assertTrue("$msg: front $front, viewer $toViewer", front.dot(toViewer) > 0.99f)
    }
}
