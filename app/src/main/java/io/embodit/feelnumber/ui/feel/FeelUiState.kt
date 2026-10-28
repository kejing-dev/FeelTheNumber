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

/** What the question card shows. */
enum class Page { MAIN, DECK, ABOUT, ROOM }

/** One-off notes on the hint line (they win over the state hint for a few seconds). */
enum class Note { SCANNED_ROOM, NO_CEILING }

/** The guess body while sizing (S03 - S06). */
data class Guess(
    val size: Float = Placement.START_SIZE_M,
    val yawDeg: Float = 0f,
    /** Where it stands now: the floor (true) or the tray; only changes when a resize ends. */
    val onFloor: Boolean = false,
    val tray: Vec3? = null,
    /** The user's spot (taps, drags, "back in front"), and the actual spot after the clearance push. */
    val userSpot: Vec3? = null,
    val spot: Vec3? = null,
    val resizing: Boolean = false,
    val dragging: Boolean = false,
    /** Wall snap engaged while dragging. */
    val snapped: Boolean = false,
    /** The user has touched the guess (S02 -> S03). */
    val touched: Boolean = false,
)

/** The revealed truth and the result (S07 - S10). */
data class Reveal(
    val trueSize: Float,
    /** Floor spot (or the tray point) of the true body. */
    val spot: Vec3,
    val onTray: Boolean,
    /** Where the guess wireframe stands (same axis as the truth, except a big guess over a tray truth). */
    val guessBase: Vec3,
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
    val hidden: Boolean = false,
)

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
    val reveal: Reveal? = null,
    val best: Map<String, Best> = emptyMap(),
    val note: Note? = null,
    /** The controller ray is over the floor while sizing (S04, ghost ring). */
    val aimingFloor: Boolean = false,
    val sceneReady: Boolean = false,
    val head: Vec3 = Vec3.ZERO,
    val facing: Vec3 = Vec3.AHEAD,
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

    val trueBody: Body?
        get() = reveal?.let { Body(question.shape, Placement.center(it.spot, it.trueSize), it.trueSize, it.yawDeg) }

    val wireBody: Body?
        get() = reveal?.let { Body(question.shape, Placement.center(it.guessBase, it.guessSize), it.guessSize, it.yawDeg) }

    /** The design's state id (S01 - S14), "+S13" in English. */
    val stateId: String
        get() {
            val base =
                when {
                    page == Page.DECK -> "S11"
                    page == Page.ABOUT -> "S12"
                    page == Page.ROOM -> "S14"
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

    /** The page the card shows on its front: the guess page or the result page. */
    val showsResult: Boolean
        get() = phase == Phase.RESULT || phase == Phase.REVEALING
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

    data object BackInFront : FeelEvent

    data object Again : FeelEvent

    data object Next : FeelEvent

    data object MoveToFront : FeelEvent

    data object ToggleHidden : FeelEvent

    data object ToggleWrap : FeelEvent

    data object OpenDeck : FeelEvent

    data object CloseDeck : FeelEvent

    data class DeckSet(val set: String) : FeelEvent

    data class Pick(val id: String) : FeelEvent

    data object OpenAbout : FeelEvent

    data object OpenRoom : FeelEvent

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

    data class DebugDragPose(val on: Boolean, val wall: Boolean) : FeelEvent

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
}
