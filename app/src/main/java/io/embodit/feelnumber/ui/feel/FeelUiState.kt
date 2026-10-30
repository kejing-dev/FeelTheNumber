package io.embodit.feelnumber.ui.feel

import io.embodit.feelnumber.domain.model.Best
import io.embodit.feelnumber.domain.model.Body
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.Question
import io.embodit.feelnumber.domain.model.QuestionBank
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.RoomSource
import io.embodit.feelnumber.domain.model.Vec3

enum class Lang { ZH, EN }

/** Main phase (design `stateModel.rule`). */
enum class Phase { FINDING, GUESS, REVEALING, RESULT }

/** What the question card shows. SETTINGS (v0.1.6, taste D26): language, sound, and [Quit] at the bottom with a confirm. */
enum class Page { MAIN, DECK, ABOUT, ROOM, SETTINGS }

/** One-off notes on the hint line (they win over the state hint for a few seconds). */
enum class Note { SCANNED_ROOM, NO_CEILING }

/** The guess body while sizing (S03 - S06). */
data class Guess(
    val size: Float = Placement.START_SIZE_M,
    val yawDeg: Float = 0f,
    /** Its base is on the floor (true) or lifted (tray = the lifted base); set by a drag release (10 cm hysteresis) or [Move to marker]. */
    val onFloor: Boolean = false,
    val tray: Vec3? = null,
    /**
     * The anchor the guess stands on (v0.1.1: the user's marker after "move to marker", a drag, or the default anchor
     * beside the panel before any marker), and the actual spot after the clearance push. Kept across questions.
     */
    val userSpot: Vec3? = null,
    val spot: Vec3? = null,
    val resizing: Boolean = false,
    val dragging: Boolean = false,
    /** Unused since v0.1.5 (no wall snap); kept for the dump format. */
    val snapped: Boolean = false,
    /** The user has touched the guess (S02 -> S03). */
    val touched: Boolean = false,
)

/**
 * The revealed truth and the result (S07 - S10). v0.1.6 (taste D1 "揭晓 / 对比同一基准"): the true body and the guess
 * wireframe are one pair generated from the same anchor - [base] is the bottom centre of both, [yawDeg] the turn of
 * both; only colour and line style differ. [lift] is the base's height above the floor (0 = the pair stands on the
 * floor; the guess left in the air keeps the pair in the air).
 */
data class Reveal(
    val trueSize: Float,
    /** Bottom centre of the true body AND of the guess wireframe. */
    val base: Vec3,
    /** Height of [base] above the floor; 0 = on the floor. */
    val lift: Float,
    val guessSize: Float,
    val yawDeg: Float,
    /** `elapsedRealtime` of the reveal start; [frozenT] holds the growth at that progress (debug `reveal t`). */
    val startedAtMs: Long,
    val frozenT: Float? = null,
    val ratio: Double,
    val stars: Int,
    val visible: Float = 1f,
    val cut: Boolean = false,
    val inside: Boolean = false,
    val wrapped: Boolean = false,
    val beforeWrap: Vec3? = null,
    /** The anchor the pair was put at (the marker it was moved to, before the clearance push). */
    val anchor: Vec3? = null,
    /** The pair (both bodies together) was moved sideways to keep the true body clear of the viewer (D14): the card says so. */
    val movedBack: Boolean = false,
    val hidden: Boolean = false,
) {
    /** The pair stands in the air (on the stand above its spot), not on the floor. */
    val lifted: Boolean
        get() = lift > 0f

    /** The floor spot under the pair. */
    fun spotOnFloor(floorY: Float): Vec3 = Vec3(base.x, floorY, base.z)
}

data class FeelUiState(
    val lang: Lang = Lang.ZH,
    val muted: Boolean = false,
    val questionId: String = QuestionBank.FIRST,
    val deckSet: String = "co2",
    val phase: Phase = Phase.FINDING,
    val page: Page = Page.MAIN,
    val room: RoomBox? = null,
    val roomSource: RoomSource? = null,
    /** The room has been resized / turned / moved by hand (the scan no longer moves it). */
    val roomEdited: Boolean = false,
    /** A scanned room is available (room page offers "use the scanned room"). */
    val scanAvailable: Boolean = false,
    val guess: Guess = Guess(),
    /** The floor marker the user put down with the controller ray or gaze + pinch (taste D1 "move to marker"). */
    val marker: Vec3? = null,
    val reveal: Reveal? = null,
    val best: Map<String, Best> = emptyMap(),
    val note: Note? = null,
    /** The controller ray is over the floor while sizing (S04, ghost ring). */
    val aimingFloor: Boolean = false,
    val sceneReady: Boolean = false,
    val head: Vec3 = Vec3.ZERO,
    val facing: Vec3 = Vec3.AHEAD,
    /** Settings page: [Quit] was pressed once, the confirm row is open (taste D26). */
    val exitAsked: Boolean = false,
    /** [Quit] confirmed: the screen finishes the task and ends the process. */
    val exiting: Boolean = false,
) {
    val en: Boolean
        get() = lang == Lang.EN

    val question: Question
        get() = QuestionBank.get(questionId)

    val floorY: Float
        get() = room?.floorY ?: (head.y - RoomBox.EYE_OVER_FLOOR_M)

    /** The guess body as it stands now (null before a room exists). */
    val guessBody: Body?
        get() {
            val g = guess
            val base = (if (g.onFloor) g.spot else g.tray) ?: return null
            return Body(question.shape, Placement.center(base, g.size), g.size, g.yawDeg)
        }

    /** The true body: same base and turn as [wireBody] (D1). */
    val trueBody: Body?
        get() = reveal?.let { Body(question.shape, Placement.center(it.base, it.trueSize), it.trueSize, it.yawDeg) }

    /** The guess wireframe: same base and turn as [trueBody] (D1). */
    val wireBody: Body?
        get() = reveal?.let { Body(question.shape, Placement.center(it.base, it.guessSize), it.guessSize, it.yawDeg) }

    /** The design's state id (S01 - S14; S15 settings, S16 quit confirm), "+S13" in English. */
    val stateId: String
        get() {
            val base =
                when {
                    page == Page.DECK -> "S11"
                    page == Page.ABOUT -> "S12"
                    page == Page.ROOM -> "S14"
                    page == Page.SETTINGS -> if (exitAsked) "S16" else "S15"
                    phase == Phase.FINDING -> "S01"
                    phase == Phase.REVEALING -> "S07"
                    phase == Phase.RESULT -> {
                        val r = reveal
                        when {
                            r?.inside == true -> "S10"
                            r?.cut == true -> "S09"
                            else -> "S08"
                        }
                    }
                    guess.dragging -> "S06"
                    guess.resizing -> "S05"
                    aimingFloor -> "S04"
                    roomSource == RoomSource.VIRTUAL && !guess.touched -> "S02"
                    else -> "S03"
                }
            return if (en) "$base+S13" else base
        }

    /** The content stands on the marker already (nothing to move). */
    val atMarker: Boolean
        get() {
            val m = marker ?: return false
            val at = if (phase == Phase.RESULT || phase == Phase.REVEALING) reveal?.anchor else guess.userSpot
            return at != null && Vec3(at.x - m.x, 0f, at.z - m.z).length < 0.05f
        }

    /** The card turns to the result page once the growth has ended (S08 - S10); during S07 the guess page stays, greyed. */
    val showsResult: Boolean
        get() = phase == Phase.RESULT
}

/** Everything that changes the model: panel controls, scene reports, debug hooks. */
sealed interface FeelEvent {
    // ---- scene
    data object SceneReady : FeelEvent

    data class HeadSample(val position: Vec3, val forward: Vec3) : FeelEvent

    /** Plane detection changed: the scanned room (null = no floor yet). */
    data class ScanChanged(val room: RoomBox?) : FeelEvent

    data object ScanTimeout : FeelEvent

    data class FloorTapped(val point: Vec3) : FeelEvent

    data class AimingFloor(val on: Boolean) : FeelEvent

    data object ResizeStarted : FeelEvent

    data class ResizeTo(val size: Float) : FeelEvent

    data object ResizeEnded : FeelEvent

    data object DragStarted : FeelEvent

    /** The guess dragged to this floor spot (the snap applied by the ViewModel). */
    data class DragTo(val spot: Vec3) : FeelEvent

    data object DragEnded : FeelEvent

    data class Turn(val deltaDeg: Float) : FeelEvent

    data object RevealDone : FeelEvent

    data class InsideChanged(val inside: Boolean) : FeelEvent

    /** Result: the pair dragged by this level offset. */
    data class PairMoved(val delta: Vec3) : FeelEvent

    // ---- question card
    data class Slider(val t: Float) : FeelEvent

    data object SliderEnded : FeelEvent

    data object Reveal : FeelEvent

    /** [Move to marker]: the guess (S03) or the revealed pair (S08 - S10) goes to the marker. */
    data object BringToMarker : FeelEvent

    /** Where the question card is (the scene reports it when the card settles): the default anchor keeps clear of it. */
    data class CardAt(val center: Vec3) : FeelEvent

    data object Again : FeelEvent

    data object Next : FeelEvent

    data object ToggleHidden : FeelEvent

    data object ToggleWrap : FeelEvent

    data object OpenDeck : FeelEvent

    data object CloseDeck : FeelEvent

    data class DeckSet(val set: String) : FeelEvent

    data class Pick(val id: String) : FeelEvent

    data object OpenAbout : FeelEvent

    data object OpenRoom : FeelEvent

    data object OpenSettings : FeelEvent

    /** Settings: [Quit] pressed once (the confirm row opens), cancelled, confirmed (taste D26). */
    data object AskExit : FeelEvent

    data object CancelExit : FeelEvent

    data object ConfirmExit : FeelEvent

    data object BackToDeck : FeelEvent

    data class SetLang(val lang: Lang) : FeelEvent

    data object ToggleMute : FeelEvent

    data class RoomResize(val dl: Float, val dw: Float, val dh: Float) : FeelEvent

    data object RoomAlign : FeelEvent

    data object RoomUseScan : FeelEvent

    data class RoomTurn(val deg: Float) : FeelEvent

    data class RoomShift(val delta: Vec3) : FeelEvent

    // ---- lifecycle and debug
    data object WentBackground : FeelEvent

    data object CameForeground : FeelEvent

    /** Debug: guess straight to this size, on the tray or the floor by the 0.30 m threshold. */
    data class DebugGuess(val size: Float) : FeelEvent

    data class DebugSpot(val x: Float, val z: Float) : FeelEvent

    data class DebugReveal(val t: Float?) : FeelEvent

    data class DebugYaw(val deg: Float) : FeelEvent

    /** Debug `drag_pose`: the drag events of a real drag; [wall] = to the wall ahead, else by [offset] from the base. */
    data class DebugDragPose(val on: Boolean, val wall: Boolean, val offset: Vec3 = Vec3.ZERO) : FeelEvent

    data class DebugBest(val seed: Boolean) : FeelEvent

    data class DebugRoom(val l: Float?, val w: Float?, val h: Float?, val virtual: Boolean) : FeelEvent

    data class DebugGoto(val state: String, val question: String?, val set: String?) : FeelEvent

    data class DebugWrap(val on: Boolean) : FeelEvent

    data class DebugMute(val on: Boolean) : FeelEvent
}

/** One-shot effects. */
sealed interface FeelEffect {
    data object Pop : FeelEffect

    data object Chime : FeelEffect

    data object Tick : FeelEffect

    /** Quit confirmed: finish the task and end the process (the screen does it; the ViewModel only decides). */
    data object Exit : FeelEffect
}
