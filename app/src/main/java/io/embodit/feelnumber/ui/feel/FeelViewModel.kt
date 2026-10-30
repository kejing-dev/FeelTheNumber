package io.embodit.feelnumber.ui.feel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.feelnumber.data.FeelRepository
import io.embodit.feelnumber.domain.model.Best
import io.embodit.feelnumber.domain.model.Body
import io.embodit.feelnumber.domain.model.Clip
import io.embodit.feelnumber.domain.model.Geometry
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.QuestionBank
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.RoomSource
import io.embodit.feelnumber.domain.model.Scoring
import io.embodit.feelnumber.domain.model.Shape
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.platform.FeelGraph
import io.embodit.feelnumber.platform.FeelLog
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states` / `interactions`). Scene reports, panel controls
 * and debug hooks come in through [onEvent]; the scene renders [state]. Placement numbers live in the
 * domain ([Placement], [Clip], [Scoring]); this class only decides when they are applied.
 */
class FeelViewModel(private val repo: FeelRepository) : ViewModel() {

    private val _state: MutableStateFlow<FeelUiState>
    val state: StateFlow<FeelUiState>
        get() = _state.asStateFlow()

    private val _effects = Channel<FeelEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Head pose, kept out of the UI state (it changes ten times a second). */
    private var head = Vec3(0f, 1.6f, 0f)
    private var facing = Vec3.AHEAD
    private var hasHead = false

    /** Where the question card is (scene report): the default anchor keeps clear of it (taste D20). */
    private var cardCenter: Vec3? = null

    private var scanRoom: RoomBox? = null
    private var scanJob: Job? = null
    private var noteJob: Job? = null
    private var noCeilingNoted = false
    private var lastLogged = ""

    /** Debug `freeze on`: plane detection and the scan timeout no longer change the state (QA shots). */
    var frozen = false

    /** Debug `goto S10`: wrap as soon as the growth has ended. */
    private var pendingWrap = false

    /** Length / width / height of the virtual room (kept). */
    private var roomSize: Triple<Float, Float, Float>

    init {
        val saved = repo.load()
        val lang = saved.lang ?: if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN
        _state = MutableStateFlow(FeelUiState(lang = lang, muted = saved.muted, best = saved.best))
        roomSize = Triple(saved.roomL, saved.roomW, saved.roomH)
    }

    private fun set(next: FeelUiState) {
        _state.value = next
        val line = "${next.stateId} q=${next.questionId}"
        if (line != lastLogged) {
            lastLogged = line
            FeelLog.i("STATE $line")
        }
    }

    private inline fun update(f: (FeelUiState) -> FeelUiState) = set(f(_state.value))

    fun onEvent(e: FeelEvent) {
        val s = _state.value
        when (e) {
            FeelEvent.SceneReady -> {
                set(s.copy(sceneReady = true))
                scanJob?.cancel()
                scanJob =
                    viewModelScope.launch {
                        delay(SCAN_TIMEOUT_MS)
                        onEvent(FeelEvent.ScanTimeout)
                    }
            }
            is FeelEvent.HeadSample -> {
                head = e.position
                facing = e.forward
                hasHead = true
                // the side view draws "you": the state follows the head in 15 cm steps only
                if (s.head.distanceTo(e.position) > HEAD_STEP_M) set(s.copy(head = e.position, facing = e.forward))
            }
            is FeelEvent.ScanChanged -> onScan(e.room)
            FeelEvent.ScanTimeout -> {
                if (frozen || _state.value.room != null) return
                FeelLog.i("ROOM scan timeout -> virtual")
                useVirtual()
            }
            is FeelEvent.FloorTapped -> onFloorTap(e.point)
            is FeelEvent.AimingFloor -> if (s.aimingFloor != e.on) set(s.copy(aimingFloor = e.on))
            FeelEvent.ResizeStarted -> if (s.phase == Phase.GUESS) set(s.copy(guess = s.guess.copy(resizing = true, touched = true)))
            is FeelEvent.ResizeTo -> if (s.phase == Phase.GUESS) resizeTo(e.size)
            FeelEvent.ResizeEnded -> if (s.phase == Phase.GUESS) endResize()
            is FeelEvent.Slider -> {
                if (s.phase != Phase.GUESS) return
                if (!s.guess.resizing) set(s.copy(guess = s.guess.copy(resizing = true, touched = true)))
                resizeTo(Placement.sizeOfSlider(e.t))
            }
            FeelEvent.SliderEnded -> if (s.phase == Phase.GUESS) endResize()
            FeelEvent.DragStarted -> if (s.phase == Phase.GUESS) set(s.copy(guess = s.guess.copy(dragging = true, touched = true)))
            is FeelEvent.DragTo -> if (s.phase == Phase.GUESS && s.guess.dragging) dragTo(e.spot)
            FeelEvent.DragEnded -> if (s.guess.dragging) endDrag()
            is FeelEvent.Turn -> {
                if (s.page == Page.ROOM) {
                    s.room?.let { r -> roomChanged(s.copy(room = r.turned(e.deltaDeg), roomEdited = true)) }
                } else if (s.phase == Phase.GUESS) {
                    set(s.copy(guess = s.guess.copy(yawDeg = s.guess.yawDeg + e.deltaDeg, touched = true)))
                }
            }
            FeelEvent.Reveal -> reveal()
            FeelEvent.RevealDone -> {
                if (s.phase != Phase.REVEALING) return
                set(s.copy(phase = Phase.RESULT))
                val r = s.reveal ?: return
                FeelLog.i(resultLine("RESULT", s))
                if (r.stars == 3) effect(FeelEffect.Chime)
                if (pendingWrap) {
                    pendingWrap = false
                    toggleWrap(_state.value, true)
                }
            }
            is FeelEvent.InsideChanged -> {
                val r = s.reveal ?: return
                if (r.inside != e.inside) {
                    FeelLog.i("INSIDE ${e.inside}")
                    set(s.copy(reveal = r.copy(inside = e.inside)))
                }
            }
            is FeelEvent.PairMoved -> {
                val r = s.reveal ?: return
                if (s.phase != Phase.RESULT) return
                movePair(s, r, Vec3(r.base.x + e.delta.x, r.base.y, r.base.z + e.delta.z), unwrap = true)
            }
            FeelEvent.BringToMarker -> bringToMarker(s)
            is FeelEvent.CardAt -> cardCenter = e.center
            FeelEvent.Again -> startQuestion(s.questionId)
            FeelEvent.Next -> startQuestion(QuestionBank.next(s.questionId))
            FeelEvent.ToggleHidden -> s.reveal?.let { set(s.copy(reveal = it.copy(hidden = !it.hidden))) }
            FeelEvent.ToggleWrap -> toggleWrap(s, !(s.reveal?.wrapped ?: false))
            FeelEvent.OpenDeck -> set(s.copy(page = Page.DECK, deckSet = s.question.set))
            FeelEvent.CloseDeck -> set(s.copy(page = Page.MAIN))
            is FeelEvent.DeckSet -> set(s.copy(deckSet = e.set))
            is FeelEvent.Pick -> {
                if (s.phase == Phase.FINDING) set(s.copy(questionId = e.id, page = Page.MAIN)) else startQuestion(e.id, page = Page.MAIN)
            }
            FeelEvent.OpenAbout -> set(s.copy(page = Page.ABOUT))
            FeelEvent.OpenRoom -> set(s.copy(page = Page.ROOM))
            FeelEvent.OpenSettings -> set(s.copy(page = Page.SETTINGS, exitAsked = false))
            FeelEvent.AskExit -> if (s.page == Page.SETTINGS) set(s.copy(exitAsked = true))
            FeelEvent.CancelExit -> set(s.copy(exitAsked = false))
            FeelEvent.ConfirmExit -> {
                // D26: only from the open confirm row; the screen finishes the task and ends the process
                if (s.page != Page.SETTINGS || !s.exitAsked || s.exiting) return
                set(s.copy(exiting = true))
                FeelLog.i("EXIT confirmed")
                _effects.trySend(FeelEffect.Exit)
            }
            FeelEvent.BackToDeck -> set(s.copy(page = Page.DECK, exitAsked = false))
            is FeelEvent.SetLang -> {
                repo.saveLang(e.lang)
                set(s.copy(lang = e.lang))
            }
            FeelEvent.ToggleMute -> setMuted(!s.muted)
            is FeelEvent.RoomResize -> {
                val r = s.room ?: return
                val next = r.sized(length = r.length + e.dl, width = r.width + e.dw, height = r.height + e.dh)
                roomSize = Triple(next.length, next.width, next.height)
                repo.saveRoomSize(next.length, next.width, next.height)
                roomChanged(s.copy(room = next, roomEdited = true))
            }
            FeelEvent.RoomAlign -> {
                val r = s.room ?: return
                roomChanged(s.copy(room = RoomBox.around(head, facing, r.width, r.length, r.height, r.floorY).copy(hasCeiling = r.hasCeiling), roomEdited = true))
            }
            FeelEvent.RoomUseScan -> {
                val scan = scanRoom ?: return
                roomChanged(s.copy(room = scan, roomSource = RoomSource.SCAN, roomEdited = false))
            }
            is FeelEvent.RoomTurn -> s.room?.let { roomChanged(s.copy(room = it.turned(e.deg), roomEdited = true)) }
            is FeelEvent.RoomShift -> s.room?.let { roomChanged(s.copy(room = it.moved(e.delta), roomEdited = true)) }
            FeelEvent.WentBackground -> FeelLog.i("LIFECYCLE background")
            FeelEvent.CameForeground -> FeelLog.i("LIFECYCLE foreground")
            is FeelEvent.DebugGuess -> debugGuess(e.size)
            is FeelEvent.DebugSpot -> {
                // debug: a marker at head + (x, z) (as if the ray had put it there), then [Move to marker]
                val r = s.room ?: return
                set(s.copy(marker = Vec3(head.x + e.x, r.floorY, head.z + e.z)))
                bringToMarker(_state.value)
            }
            is FeelEvent.DebugReveal -> {
                if (s.phase == Phase.GUESS) reveal()
                val now = _state.value
                val r = now.reveal ?: return
                if (e.t == null) {
                    set(now.copy(reveal = r.copy(frozenT = null, startedAtMs = SystemClock.elapsedRealtime())))
                } else if (e.t >= 1f) {
                    // jump to the end of the growth: the scene finishes it on the next frame and reports RevealDone
                    set(now.copy(phase = if (now.phase == Phase.RESULT) Phase.RESULT else Phase.REVEALING, reveal = r.copy(frozenT = null, startedAtMs = SystemClock.elapsedRealtime() - 10_000L)))
                } else {
                    set(now.copy(phase = if (e.t >= 1f) Phase.RESULT else Phase.REVEALING, reveal = r.copy(frozenT = e.t.coerceIn(0f, 1f))))
                }
            }
            is FeelEvent.DebugYaw -> set(s.copy(guess = s.guess.copy(yawDeg = e.deg)))
            is FeelEvent.DebugDragPose -> debugDragPose(e.on, e.wall, e.offset)
            is FeelEvent.DebugBest -> {
                val best =
                    if (e.seed) {
                        mapOf("co2-1kg" to Best(1, 0.269), "kg-gold" to Best(0, 521.6), "kg-water" to Best(3, 1.06), "kg-air" to Best(2, 0.62), "body-water-ton" to Best(3, 0.91))
                    } else emptyMap()
                repo.saveBest(best)
                set(s.copy(best = best))
            }
            is FeelEvent.DebugRoom -> {
                if (e.virtual) {
                    frozen = false
                    scanRoom = null
                    roomSize = Triple(e.l ?: roomSize.first, e.w ?: roomSize.second, e.h ?: roomSize.third)
                    useVirtual(force = true)
                } else {
                    val r = s.room ?: return
                    roomChanged(s.copy(room = r.sized(length = e.l ?: r.length, width = e.w ?: r.width, height = e.h ?: r.height), roomEdited = true))
                }
            }
            is FeelEvent.DebugGoto -> debugGoto(e)
            is FeelEvent.DebugWrap -> toggleWrap(s, e.on)
            is FeelEvent.DebugMute -> setMuted(e.on)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // room
    // ---------------------------------------------------------------------------------------------

    private fun onScan(room: RoomBox?) {
        if (room == null) return
        scanRoom = room
        val s = _state.value
        val adopt = !frozen && !s.roomEdited && (s.room == null || s.roomSource == RoomSource.SCAN || s.roomSource == RoomSource.VIRTUAL)
        if (!adopt) {
            if (!s.scanAvailable) set(s.copy(scanAvailable = true))
            return
        }
        val wasVirtual = s.roomSource == RoomSource.VIRTUAL
        FeelLog.i("ROOM scan ${room.label} floor=${FeelLog.f(room.floorY)} ceiling=${room.hasCeiling} yaw=${FeelLog.f(room.yawDeg, 1)}")
        val next = s.copy(room = room, roomSource = RoomSource.SCAN, scanAvailable = true)
        if (s.phase == Phase.FINDING) {
            set(next)
            startQuestion(s.questionId)
        } else {
            roomChanged(next)
        }
        if (wasVirtual) showNote(Note.SCANNED_ROOM)
        else if (!room.hasCeiling && !noCeilingNoted) {
            noCeilingNoted = true
            showNote(Note.NO_CEILING)
        }
    }

    private fun useVirtual(force: Boolean = false) {
        val s = _state.value
        if (s.room != null && !force) return
        val (l, w, h) = roomSize
        val room = RoomBox.around(head, facing, w, l, h)
        FeelLog.i("ROOM virtual ${room.label} floor=${FeelLog.f(room.floorY)}")
        set(s.copy(room = room, roomSource = RoomSource.VIRTUAL, roomEdited = false, scanAvailable = scanRoom != null))
        if (s.phase == Phase.FINDING || force) startQuestion(s.questionId, page = s.page) else roomChanged(_state.value)
    }

    /** After any room change: the guess's actual spot and the result's cut / visible share follow. */
    private fun roomChanged(s0: FeelUiState) {
        var s = s0
        val room = s.room
        if (room != null && s.phase == Phase.GUESS) {
            val g = s.guess
            // the spots stand on the (new) floor
            val user = g.userSpot?.let { Vec3(it.x, room.floorY, it.z) }
            // v0.1.4: a room update (the scan refines it twice a second) only follows the floor height; it never re-places
            // the guess (it used to re-run the clearance push and put the tray back to 1.0 m)
            val dy = g.spot?.let { room.floorY - it.y } ?: 0f
            s = s.copy(
                guess = g.copy(userSpot = user, spot = g.spot?.let { Vec3(it.x, room.floorY, it.z) }, tray = g.tray?.let { Vec3(it.x, it.y + dy, it.z) }),
                marker = s.marker?.let { Vec3(it.x, room.floorY, it.z) },
            )
        }
        val r = s.reveal
        if (room != null && r != null) {
            // the pair follows the floor height, keeping its lift (D1: both bodies on one base)
            s = s.copy(reveal = withCut(r.copy(base = Vec3(r.base.x, room.floorY + r.lift, r.base.z)), s))
        }
        set(s)
    }

    private fun showNote(note: Note) {
        update { it.copy(note = note) }
        noteJob?.cancel()
        noteJob =
            viewModelScope.launch {
                delay(NOTE_MS)
                update { if (it.note == note) it.copy(note = null) else it }
            }
    }

    // ---------------------------------------------------------------------------------------------
    // guessing
    // ---------------------------------------------------------------------------------------------

    /** A fresh guess for [id]: 0.25 m on the tray, the user's spot kept (or 1.8 m ahead), the room cleared of the truth. */
    private fun startQuestion(id: String, page: Page = Page.MAIN) {
        val s = _state.value
        val room = s.room
        if (room == null) {
            set(s.copy(questionId = id, page = page, phase = Phase.FINDING, reveal = null))
            return
        }
        // the anchor stays where it is for the whole session (taste D1): next / again / deck never move it
        val prev = s.guess
        val user = prev.userSpot?.let { Vec3(it.x, room.floorY, it.z) } ?: defaultAnchor(room)
        val g = Guess(userSpot = user, touched = prev.touched && s.questionId != id)
        val next = s.copy(questionId = id, page = page, phase = Phase.GUESS, reveal = null, guess = g, aimingFloor = false)
        // v0.1.4 ("放在哪就在哪"): a new question / guess again starts exactly where the last guess was left (same spot,
        // same height); only the very first one is put at the default anchor
        val kept =
            if (prev.spot != null) g.copy(spot = Vec3(prev.spot.x, room.floorY, prev.spot.z), onFloor = prev.onFloor, tray = prev.tray)
            else placed(g, next, atAnchor = true)
        // M6 (taste D10): the body faces the user's current position, worked out once per question
        val placed = kept.copy(yawDeg = kept.spot?.let { Placement.faceYaw(it, head) } ?: 0f)
        set(next.copy(guess = placed))
        FeelLog.i("QUESTION $id true=${FeelLog.f(QuestionBank.get(id).sizeM, 3)} tray=${FeelLog.pos(placed.tray)} anchor=${FeelLog.pos(user)}")
    }

    /**
     * The actual spot: the anchor pushed out of the head's clearance (D14). The head is only the clearance reference
     * here; the anchor itself comes from the marker, a drag or the default anchor beside the card, never the head pose.
     */
    private fun actual(g: Guess, s: FeelUiState): Vec3? {
        val user = g.userSpot ?: return null
        return Placement.actualSpot(user, head, s.question.shape, g.size, s.room)
    }

    /**
     * The guess with its actual spot and the tray above it. [atAnchor] = the content is being put at an anchor (a new
     * question, [Move to marker]): only then is it pushed out of the view cone to the card (taste D20). A release, a
     * resize or a drag never moves it for the cone (v0.1.2); the scene fades it instead.
     */
    private fun placed(g: Guess, s: FeelUiState, atAnchor: Boolean = false): Guess {
        var spot = actual(g, s)
        if (atAnchor && spot != null) spot = clearOfCard(spot, s, s.question.shape, g.size, lift = if (Placement.onFloor(g.size)) 0f else Placement.TRAY_HEIGHT_M)
        return g.copy(spot = spot, tray = spot?.let { Placement.trayAt(it) })
    }

    private fun clearOfCard(spot: Vec3, s: FeelUiState, shape: Shape, size: Float, lift: Float): Vec3 {
        val out = Placement.clearOfPanel(spot, head, cardCenter, CARD_HALF_DIAG_M, shape, size, lift, s.room)
        if (out != spot) FeelLog.i("CONE push spot=${FeelLog.pos(spot)} -> ${FeelLog.pos(out)}")
        return out
    }

    /**
     * The anchor before the user has put a marker down: 2.5 m out, 55 degrees right of the question card (taste D20),
     * taken once. Without a card report yet, the card is assumed at its designed place left of the room's length axis.
     */
    private fun defaultAnchor(room: RoomBox): Vec3 {
        val card = cardCenter ?: run {
            val f = room.forward
            val left = Vec3(f.z, 0f, -f.x)
            head + (f * 0.87f + left * 0.5f)
        }
        val a = Placement.defaultAnchor(head, card, room.floorY)
        FeelLog.i("ANCHOR default=${FeelLog.pos(a)} card=${FeelLog.pos(card)}")
        return a
    }

    /** [Move to marker]: the guess or the revealed pair goes to the user's marker (taste D1). */
    private fun bringToMarker(s: FeelUiState) {
        val m = s.marker ?: return
        val room = s.room ?: return
        val anchor = Vec3(m.x, room.floorY, m.z)
        when (s.phase) {
            Phase.GUESS -> {
                // the one placement that moves the guess: it lands on the marker (small bodies on the stand above it)
                val p = placed(s.guess.copy(userSpot = anchor, snapped = false, touched = true, onFloor = Placement.onFloor(s.guess.size)), s, atAnchor = true)
                val g = p.copy(tray = if (p.onFloor) null else p.tray, yawDeg = p.spot?.let { Placement.faceYaw(it, head) } ?: p.yawDeg)
                set(s.copy(guess = g))
                FeelLog.i("MARKER bring guess anchor=${FeelLog.pos(anchor)} spot=${FeelLog.pos(g.spot)}")
            }
            Phase.RESULT -> {
                val r = s.reveal ?: return
                // D1: the pair moves as one - same lift, same turn; pushed out of the viewer's clearance and the card's
                // view cone like any content put at an anchor
                val pose = Placement.pairAt(Placement.PairPose(r.base, r.yawDeg, r.lift, r.movedBack), anchor, room.floorY)
                val pushed = Placement.revealSpot(pose.base, head, s.question.shape, r.trueSize, room)
                val to = clearOfCard(pushed, s, s.question.shape, maxOf(r.trueSize, r.guessSize), lift = r.lift)
                movePair(s, r, to, unwrap = true, anchor = anchor)
                // B3: the next question / guess again starts on the marker too (spot and height, not only the anchor)
                update {
                    val g = placed(it.guess.copy(userSpot = anchor, onFloor = Placement.onFloor(it.guess.size)), it, atAnchor = true)
                    it.copy(guess = g.copy(tray = if (g.onFloor) null else g.tray))
                }
                FeelLog.i("MARKER bring pair anchor=${FeelLog.pos(anchor)} spot=${FeelLog.pos(to)}")
            }
            else -> Unit
        }
    }

    private fun resizeTo(size: Float) {
        val s = _state.value
        // v0.1.4: a resize grows the body in place (its base stays put): no clearance push, no tray <-> floor move
        set(s.copy(guess = s.guess.copy(size = Placement.clampSize(size), touched = true)))
    }

    private fun endResize() {
        val s = _state.value
        // v0.1.4 (user: 「拉那个球之后好像会掉下去」): letting go no longer drops the body from the tray to the floor
        val g = s.guess.copy(resizing = false)
        set(s.copy(guess = g))
        FeelLog.i("GUESS size=${FeelLog.f(g.size, 3)} floor=${g.onFloor} spot=${FeelLog.pos(g.spot)}")
    }

    /** A pinch / trigger on the floor puts the marker down (it does not move anything by itself; taste D1). */
    private fun onFloorTap(point: Vec3) {
        val s = _state.value
        val room = s.room ?: return
        if (s.page != Page.MAIN || s.guess.dragging || s.guess.resizing) return
        if (s.phase != Phase.GUESS && s.phase != Phase.RESULT) return
        val m = Vec3(point.x, room.floorY, point.z)
        set(s.copy(marker = m, aimingFloor = false))
        FeelLog.i("MARKER set at=${FeelLog.pos(m)}")
    }

    /**
     * A drag moves the body's base in 3D (v0.1.4): it may be left in the air, never below the floor; within 10 cm of the
     * floor it stands on it (M3). v0.1.5 (M2): no snapping to walls - it goes exactly where the hand takes it.
     */
    private fun dragTo(raw: Vec3) {
        val s = _state.value
        val room = s.room ?: return
        val g = s.guess
        val lift = Placement.liftOf(raw, room.floorY)
        val spot = Vec3(raw.x, room.floorY, raw.z)
        val onFloor = lift == 0f
        set(s.copy(guess = g.copy(spot = spot, userSpot = spot, snapped = false, onFloor = onFloor, tray = if (onFloor) null else Vec3(spot.x, room.floorY + lift, spot.z))))
    }

    private fun endDrag() {
        val s = _state.value
        val g = s.guess.copy(dragging = false)
        // v0.1.2: the guess stays exactly where it was let go (on the floor below the hand); nothing pushes it away
        val at = g.spot
        set(s.copy(guess = g.copy(spot = at, userSpot = at)))
        FeelLog.i("DRAG end spot=${FeelLog.pos(at)} snapped=${g.snapped}")
    }

    // ---------------------------------------------------------------------------------------------
    // reveal and result
    // ---------------------------------------------------------------------------------------------

    private fun reveal() {
        val s = _state.value
        if (s.phase != Phase.GUESS) return
        val q = s.question
        val g = s.guess
        val room = s.room ?: return
        val trueSize = q.sizeM
        val guessVolume = Geometry.volumeOf(q.shape, g.size.toDouble())
        val r = Scoring.ratio(guessVolume, q.volumeM3)
        val stars = Scoring.stars(r)
        // v0.1.6 (taste D1 "揭晓 / 对比同一基准"; user on v0.1.5: 「绿色在下，蓝色在上，怎么不 consistent」): the truth and the
        // guess wireframe come from ONE anchor - the guess's bottom centre and turn as the user left them (on the floor
        // or in the air). The true size never changes. If the true body would reach the viewer (D14), the pair moves
        // sideways together and the card says so. (v0.1.5 put the truth on the floor while the guess stayed in the air;
        // that split the two bases.)
        val floorSpot = g.spot ?: return
        val guessBase = if (g.onFloor) floorSpot else (g.tray ?: floorSpot)
        val pose = Placement.pairPose(guessBase, g.yawDeg, room.floorY, q.shape, trueSize, head, room)
        val best = s.best[q.id]
        val newBest = best == null || stars > best.stars || (stars == best.stars && kotlin.math.abs(kotlin.math.ln(r)) < kotlin.math.abs(kotlin.math.ln(best.ratio)))
        val bests = if (newBest) s.best + (q.id to Best(stars, r)) else s.best
        if (newBest) repo.saveBest(bests)
        val reveal =
            Reveal(
                trueSize = trueSize, base = pose.base, lift = pose.lift, guessSize = g.size, yawDeg = pose.yawDeg,
                startedAtMs = SystemClock.elapsedRealtime(), ratio = r, stars = stars, anchor = g.userSpot, movedBack = pose.shifted,
            )
        val next = s.copy(phase = Phase.REVEALING, reveal = withCut(reveal, s), best = bests, aimingFloor = false, page = Page.MAIN, guess = g.copy(resizing = false, dragging = false))
        set(next)
        FeelLog.i(resultLine("REVEAL", next))
        effect(FeelEffect.Pop)
    }

    private fun withCut(r: Reveal, s: FeelUiState): Reveal {
        val room = s.room ?: return r
        val body = Body(s.question.shape, Placement.center(r.base, r.trueSize), r.trueSize, r.yawDeg)
        val visible = if (Clip.isCut(room, body)) Clip.visibleFraction(room, body) else 1f
        // "cut" only when something is really hidden (the sampled share rounds to under 100 %)
        val cut = visible < 0.995f
        return r.copy(cut = cut, visible = if (cut) visible else 1f)
    }

    /** The pair (truth + wireframe, one base) goes to [to]; the lift is whatever [to] carries. */
    private fun movePair(s: FeelUiState, r: Reveal, to: Vec3, unwrap: Boolean, anchor: Vec3? = null) {
        val moved = r.copy(base = to, wrapped = if (unwrap) false else r.wrapped, beforeWrap = if (unwrap) null else r.beforeWrap, anchor = anchor ?: r.anchor)
        set(s.copy(reveal = withCut(moved, s)))
    }

    private fun toggleWrap(s: FeelUiState, on: Boolean) {
        val r = s.reveal ?: return
        if (s.phase != Phase.RESULT || r.trueSize < Placement.WRAP_MIN_M) return
        if (on == r.wrapped) return
        if (on) {
            // the pair's axis under the head; its base height is kept (D1)
            val to = Placement.wrapSpot(head, r.base.y)
            set(s.copy(reveal = withCut(r.copy(base = to, wrapped = true, beforeWrap = r.base), s)))
            FeelLog.i("WRAP on at=${FeelLog.pos(to)}")
        } else {
            val back = r.beforeWrap ?: return
            set(s.copy(reveal = withCut(r.copy(base = back, wrapped = false, beforeWrap = null), s)))
            FeelLog.i("WRAP off back=${FeelLog.pos(back)}")
        }
    }

    private fun setMuted(muted: Boolean) {
        repo.saveMuted(muted)
        update { it.copy(muted = muted) }
        FeelLog.i("MUTE $muted")
    }

    private fun effect(e: FeelEffect) {
        if (_state.value.muted) {
            FeelLog.i("SOUND skipped (muted) $e")
            return
        }
        _effects.trySend(e)
    }

    // ---------------------------------------------------------------------------------------------
    // debug
    // ---------------------------------------------------------------------------------------------

    /** Debug `guess`: the same events as pulling the handle (ResizeStarted / ResizeTo / ResizeEnded); no shortcut (M1). */
    private fun debugGuess(size: Float) {
        if (_state.value.phase != Phase.GUESS) startQuestion(_state.value.questionId)
        if (_state.value.phase != Phase.GUESS) return
        onEvent(FeelEvent.ResizeStarted)
        onEvent(FeelEvent.ResizeTo(size))
        onEvent(FeelEvent.ResizeEnded)
        val g = _state.value.guess
        FeelLog.i("GUESS debug size=${FeelLog.f(g.size, 3)} floor=${g.onFloor} spot=${FeelLog.pos(g.spot)} tray=${FeelLog.pos(g.tray)}")
    }

    /**
     * Debug `drag_pose`: the drag events of a real body drag (M1); `wall` = to 0.1 m from the wall ahead (no snap any
     * more); otherwise the base moved by [offset] (v0.1.6: `--ef dx/dy/dz`, so QA can leave the guess in the air or on
     * the floor through the same events a hand would send).
     */
    private fun debugDragPose(on: Boolean, wall: Boolean, offset: Vec3 = Vec3.ZERO) {
        val room = _state.value.room ?: return
        if (!on) {
            onEvent(FeelEvent.DragEnded)
            return
        }
        onEvent(FeelEvent.DragStarted)
        val g = _state.value.guess
        val spot = g.spot ?: return
        val base = if (g.onFloor) spot else (g.tray ?: spot)
        val target =
            if (wall) {
                val dir = RoomBox.level(facing)
                val exit = room.exitDistance(spot, dir) ?: return
                base + dir * (exit - g.size / 2f - 0.1f).coerceAtLeast(0f)
            } else base + offset
        onEvent(FeelEvent.DragTo(target))
    }

    /** Debug `goto`: straight to a designed state with the design's QA setup (`qaCombos`). */
    private fun debugGoto(e: FeelEvent.DebugGoto) {
        val id = e.question?.takeIf { QuestionBank.has(it) }
        val st = e.state.uppercase(Locale.US)
        fun q(default: String) = startQuestion(id ?: default)
        when (st) {
            "S01" -> {
                frozen = true
                update { it.copy(phase = Phase.FINDING, page = Page.MAIN, reveal = null, questionId = id ?: it.questionId) }
            }
            "S02" -> {
                frozen = true
                scanRoom = scanRoom
                useVirtual(force = true)
                q(QuestionBank.FIRST)
                update { it.copy(guess = it.guess.copy(touched = false)) }
            }
            "S03" -> q(QuestionBank.FIRST)
            "S04" -> {
                q(QuestionBank.FIRST)
                onEvent(FeelEvent.AimingFloor(true))
            }
            "S05" -> {
                q(QuestionBank.FIRST)
                // the handle held at 3.0 m (ResizeStarted + ResizeTo, not yet released)
                onEvent(FeelEvent.ResizeStarted)
                onEvent(FeelEvent.ResizeTo(3.0f))
            }
            "S06" -> {
                q("kg-water")
                debugDragPose(on = true, wall = true)
            }
            "S07" -> {
                q(QuestionBank.FIRST)
                debugGuess(0.65f)
                onEvent(FeelEvent.DebugReveal(0.5f))
            }
            "S08", "S09", "S10" -> {
                val qid = id ?: when (st) { "S08" -> "co2-1kg"; "S09" -> "co2-flight"; else -> "co2-1t" }
                startQuestion(qid)
                debugGuess(DEFAULT_GUESS[qid] ?: 1.0f)
                onEvent(FeelEvent.DebugReveal(1f))
                if (st == "S10") pendingWrap = true
            }
            "S11" -> {
                onEvent(FeelEvent.OpenDeck)
                e.set?.let { onEvent(FeelEvent.DeckSet(it)) }
            }
            "S12" -> {
                onEvent(FeelEvent.OpenDeck)
                onEvent(FeelEvent.OpenAbout)
            }
            "S13" -> onEvent(FeelEvent.SetLang(Lang.EN))
            "S14" -> onEvent(FeelEvent.OpenRoom)
            "S15" -> onEvent(FeelEvent.OpenSettings)
            "S16" -> {
                onEvent(FeelEvent.OpenSettings)
                onEvent(FeelEvent.AskExit)
            }
            else -> FeelLog.w("CMD goto unknown state $st")
        }
        if (e.set != null && st != "S11") onEvent(FeelEvent.DeckSet(e.set))
    }

    private fun resultLine(tag: String, s: FeelUiState): String {
        val r = s.reveal ?: return "$tag none"
        return String.format(
            Locale.US, "%s q=%s guess=%.3f true=%.3f r=%.4f stars=%d verdict=%s visible=%.2f cut=%b lifted=%b base=%s yaw=%.1f shifted=%b",
            tag, s.questionId, r.guessSize, r.trueSize, r.ratio, r.stars, Scoring.verdict(r.ratio), r.visible, r.cut, r.lifted, FeelLog.pos(r.base), r.yawDeg, r.movedBack,
        )
    }

    /** One JSON line for the debug `state` command. */
    fun dumpJson(): String {
        val s = _state.value
        val o = JSONObject()
        o.put("state", s.stateId)
        o.put("question", s.questionId)
        o.put("phase", s.phase.name)
        o.put("page", s.page.name)
        o.put("lang", s.lang.name)
        o.put("muted", s.muted)
        s.room?.let { r ->
            o.put("room", JSONObject().put("source", s.roomSource?.name).put("l", r.length).put("w", r.width).put("h", r.height).put("floorY", r.floorY).put("ceiling", r.hasCeiling).put("yaw", r.yawDeg).put("edited", s.roomEdited))
        }
        val q = s.question
        o.put("guess", JSONObject().put("size", s.guess.size).put("onFloor", s.guess.onFloor).put("yaw", s.guess.yawDeg).put("volume", Geometry.volumeOf(q.shape, s.guess.size.toDouble())).put("spot", FeelLog.pos(s.guess.spot)).put("user", FeelLog.pos(s.guess.userSpot)).put("snapped", s.guess.snapped))
        o.put("true", JSONObject().put("size", q.sizeM).put("volume", q.volumeM3).put("shape", q.shape.name))
        s.reveal?.let { r ->
            // D1 check for QA: both bodies share "base" and "yaw" by construction
            o.put("result", JSONObject().put("r", r.ratio).put("stars", r.stars).put("verdict", Scoring.verdict(r.ratio).toString()).put("visible", r.visible).put("cut", r.cut).put("inside", r.inside).put("wrapped", r.wrapped).put("hidden", r.hidden).put("lifted", r.lifted).put("lift", r.lift).put("base", FeelLog.pos(r.base)).put("yaw", r.yawDeg).put("shifted", r.movedBack))
        }
        o.put("exitAsked", s.exitAsked)
        o.put("head", FeelLog.pos(head))
        o.put("best", s.best.size)
        o.put("frozen", frozen)
        return o.toString()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FeelViewModel(FeelGraph.repository(context.applicationContext)) as T
    }

    companion object {
        const val SCAN_TIMEOUT_MS = 5000L
        private const val NOTE_MS = 6000L
        private const val HEAD_STEP_M = 0.15f

        /** Half diagonal of the question card (460 x 640 dp at 0.8 mm) for the view-cone test. */
        private const val CARD_HALF_DIAG_M = 0.315f

        /** The design's QA guesses per question (`stateModel.qaCombos`). */
        val DEFAULT_GUESS = mapOf("co2-1kg" to 0.65f, "kg-gold" to 0.3f, "co2-flight" to 4.0f, "kg-hydrogen" to 1.0f, "co2-1t" to 1.0f, "big-co2-capita" to 2.0f)
    }
}
