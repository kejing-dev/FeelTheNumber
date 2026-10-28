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
            FeelEvent.DragStarted -> if (s.phase == Phase.GUESS && s.guess.onFloor) set(s.copy(guess = s.guess.copy(dragging = true, touched = true)))
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
                movePair(s, r, Vec3(r.spot.x + e.delta.x, r.spot.y, r.spot.z + e.delta.z), unwrap = true)
            }
            FeelEvent.BackInFront -> {
                if (s.phase != Phase.GUESS || s.room == null) return
                val user = Placement.defaultSpot(head, facing, s.room.floorY)
                val g = s.guess.copy(tray = Placement.trayPoint(head, facing), userSpot = user, snapped = false, touched = true)
                set(s.copy(guess = g.copy(spot = actual(g, s))))
                FeelLog.i("SPOT back-in-front user=${FeelLog.pos(user)}")
            }
            FeelEvent.Again -> startQuestion(s.questionId)
            FeelEvent.Next -> startQuestion(QuestionBank.next(s.questionId))
            FeelEvent.MoveToFront -> {
                val r = s.reveal ?: return
                val to =
                    if (r.onTray) Placement.trayPoint(head, facing)
                    else Placement.frontSpot(head, facing, s.question.shape, r.trueSize, s.floorY)
                movePair(s, r, to, unwrap = true)
            }
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
            FeelEvent.BackToDeck -> set(s.copy(page = Page.DECK))
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
                val r = s.room ?: return
                val user = Vec3(head.x + e.x, r.floorY, head.z + e.z)
                val g = s.guess.copy(userSpot = user, touched = true)
                set(s.copy(guess = g.copy(spot = actual(g, s))))
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
            is FeelEvent.DebugDragPose -> debugDragPose(e.on, e.wall)
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
            val ng = g.copy(userSpot = user)
            s = s.copy(guess = ng.copy(spot = actual(ng, s)))
        }
        val r = s.reveal
        if (room != null && r != null) {
            val spot = if (r.onTray) r.spot else Vec3(r.spot.x, room.floorY, r.spot.z)
            val base = Vec3(r.guessBase.x, if (r.guessBase.y == r.spot.y) spot.y else r.guessBase.y, r.guessBase.z)
            s = s.copy(reveal = withCut(r.copy(spot = spot, guessBase = base), s))
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
        val user = s.guess.userSpot?.let { Vec3(it.x, room.floorY, it.z) } ?: Placement.defaultSpot(head, facing, room.floorY)
        val g = Guess(tray = Placement.trayPoint(head, facing), userSpot = user, touched = s.guess.touched && s.questionId != id)
        val next = s.copy(questionId = id, page = page, phase = Phase.GUESS, reveal = null, guess = g, aimingFloor = false)
        set(next.copy(guess = g.copy(spot = actual(g, next))))
        FeelLog.i("QUESTION $id true=${FeelLog.f(QuestionBank.get(id).sizeM, 3)} tray=${FeelLog.pos(g.tray)} user=${FeelLog.pos(user)}")
    }

    private fun actual(g: Guess, s: FeelUiState): Vec3? {
        val user = g.userSpot ?: return null
        return Placement.actualSpot(user, head, facing, s.question.shape, g.size, s.room)
    }

    private fun resizeTo(size: Float) {
        val s = _state.value
        val g = s.guess.copy(size = Placement.clampSize(size), touched = true)
        // while resizing the body does not change place; a floor body backs away as it grows (clearance rule)
        val spot = if (g.onFloor) actual(g, s) else g.spot
        set(s.copy(guess = g.copy(spot = spot, snapped = if (spot != g.spot) false else g.snapped)))
    }

    private fun endResize() {
        val s = _state.value
        val g = s.guess.copy(resizing = false, onFloor = Placement.onFloor(s.guess.size))
        set(s.copy(guess = g.copy(spot = actual(g, s))))
        FeelLog.i("GUESS size=${FeelLog.f(g.size, 3)} floor=${g.onFloor} spot=${FeelLog.pos(g.spot)}")
    }

    private fun onFloorTap(point: Vec3) {
        val s = _state.value
        val room = s.room ?: return
        if (s.phase != Phase.GUESS || s.page == Page.ROOM || s.guess.dragging || s.guess.resizing) return
        val user = Vec3(point.x, room.floorY, point.z)
        val g = s.guess.copy(userSpot = user, snapped = false, touched = true)
        set(s.copy(guess = g.copy(spot = actual(g, s)), aimingFloor = false))
        FeelLog.i("SPOT tap user=${FeelLog.pos(user)} actual=${FeelLog.pos(_state.value.guess.spot)} onTray=${!g.onFloor}")
    }

    private fun dragTo(raw: Vec3) {
        val s = _state.value
        val room = s.room ?: return
        val g = s.guess
        var spot = Vec3(raw.x, room.floorY, raw.z)
        var yaw = g.yawDeg
        var snapped = g.snapped
        val threshold = if (snapped) Placement.SNAP_OUT_M else Placement.SNAP_IN_M
        val snap = Placement.snap(room, spot, s.question.shape, g.size, g.yawDeg, threshold)
        if (snap != null) {
            if (!snapped) effect(FeelEffect.Tick)
            spot = snap.first
            yaw = snap.second
            snapped = true
        } else {
            snapped = false
        }
        set(s.copy(guess = g.copy(spot = spot, userSpot = spot, yawDeg = yaw, snapped = snapped)))
    }

    private fun endDrag() {
        val s = _state.value
        val g = s.guess.copy(dragging = false)
        val pushed = actual(g, s)
        // the clearance push wins over the snap ("推远一生效就解除贴墙吸附")
        val moved = pushed != null && g.spot != null && pushed.distanceTo(g.spot) > 0.01f
        set(s.copy(guess = g.copy(spot = pushed, snapped = g.snapped && !moved)))
        FeelLog.i("DRAG end spot=${FeelLog.pos(pushed)} snapped=${g.snapped && !moved}")
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
        val onTray = trueSize < Placement.FLOOR_MIN_M
        val tray = g.tray ?: Placement.trayPoint(head, facing)
        val spot: Vec3
        val guessBase: Vec3
        if (onTray) {
            spot = tray
            // a big guess stays on the floor (sliding it to the tray would cover the viewer); the side view shows both
            guessBase = if (g.size >= 0.6f) (g.spot ?: tray) else tray
        } else {
            val from = (if (g.onFloor) g.spot else g.userSpot) ?: Placement.defaultSpot(head, facing, room.floorY)
            spot = Placement.revealSpot(from, head, facing, q.shape, trueSize)
            guessBase = spot
        }
        val best = s.best[q.id]
        val newBest = best == null || stars > best.stars || (stars == best.stars && kotlin.math.abs(kotlin.math.ln(r)) < kotlin.math.abs(kotlin.math.ln(best.ratio)))
        val bests = if (newBest) s.best + (q.id to Best(stars, r)) else s.best
        if (newBest) repo.saveBest(bests)
        val reveal =
            Reveal(
                trueSize = trueSize, spot = spot, onTray = onTray, guessBase = guessBase, guessSize = g.size, yawDeg = g.yawDeg,
                startedAtMs = SystemClock.elapsedRealtime(), ratio = r, stars = stars,
            )
        val next = s.copy(phase = Phase.REVEALING, reveal = withCut(reveal, s), best = bests, aimingFloor = false, page = Page.MAIN, guess = g.copy(resizing = false, dragging = false))
        set(next)
        FeelLog.i(resultLine("REVEAL", next))
        effect(FeelEffect.Pop)
    }

    private fun withCut(r: Reveal, s: FeelUiState): Reveal {
        val room = s.room ?: return r
        val body = Body(s.question.shape, Placement.center(r.spot, r.trueSize), r.trueSize, r.yawDeg)
        val cut = Clip.isCut(room, body)
        return r.copy(cut = cut, visible = if (cut) Clip.visibleFraction(room, body) else 1f)
    }

    private fun movePair(s: FeelUiState, r: Reveal, to: Vec3, unwrap: Boolean) {
        val d = to - r.spot
        val moved = r.copy(spot = to, guessBase = r.guessBase + d, wrapped = if (unwrap) false else r.wrapped, beforeWrap = if (unwrap) null else r.beforeWrap)
        set(s.copy(reveal = withCut(moved, s)))
    }

    private fun toggleWrap(s: FeelUiState, on: Boolean) {
        val r = s.reveal ?: return
        if (s.phase != Phase.RESULT || r.onTray || r.trueSize < Placement.WRAP_MIN_M) return
        if (on == r.wrapped) return
        if (on) {
            val to = Placement.wrapSpot(head, s.floorY)
            val d = to - r.spot
            set(s.copy(reveal = withCut(r.copy(spot = to, guessBase = r.guessBase + d, wrapped = true, beforeWrap = r.spot), s)))
            FeelLog.i("WRAP on at=${FeelLog.pos(to)}")
        } else {
            val back = r.beforeWrap ?: return
            val d = back - r.spot
            set(s.copy(reveal = withCut(r.copy(spot = back, guessBase = r.guessBase + d, wrapped = false, beforeWrap = null), s)))
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

    private fun debugGuess(size: Float) {
        var s = _state.value
        if (s.phase != Phase.GUESS) {
            startQuestion(s.questionId)
            s = _state.value
        }
        if (s.phase != Phase.GUESS) return
        val g = s.guess.copy(size = Placement.clampSize(size), onFloor = Placement.onFloor(size), resizing = false, touched = true)
        set(s.copy(guess = g.copy(spot = actual(g, s))))
        FeelLog.i("GUESS debug size=${FeelLog.f(size, 3)} floor=${g.onFloor} spot=${FeelLog.pos(_state.value.guess.spot)}")
    }

    private fun debugDragPose(on: Boolean, wall: Boolean) {
        val s = _state.value
        val room = s.room ?: return
        if (!on) {
            set(s.copy(guess = s.guess.copy(dragging = false, snapped = false)))
            return
        }
        if (!s.guess.onFloor) debugGuess(1.0f)
        val now = _state.value
        set(now.copy(guess = now.guess.copy(dragging = true)))
        if (wall) {
            // the wall straight ahead of the view, the body's surface 0.1 m from it (inside the snap distance)
            val g = _state.value.guess
            val spot = g.spot ?: return
            val dir = RoomBox.level(facing)
            val exit = room.exitDistance(spot, dir) ?: return
            dragTo(spot + dir * (exit - g.size / 2f - 0.1f).coerceAtLeast(0f))
        } else {
            _state.value.guess.spot?.let { dragTo(it) }
        }
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
                update { it.copy(aimingFloor = true) }
            }
            "S05" -> {
                q(QuestionBank.FIRST)
                debugGuess(3.0f)
                update { it.copy(guess = it.guess.copy(resizing = true)) }
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
            "S11" -> update { it.copy(page = Page.DECK, deckSet = e.set ?: it.question.set) }
            "S12" -> update { it.copy(page = Page.ABOUT) }
            "S13" -> update { it.copy(lang = Lang.EN) }
            "S14" -> update { it.copy(page = Page.ROOM) }
            else -> FeelLog.w("CMD goto unknown state $st")
        }
        if (e.set != null && st != "S11") update { it.copy(deckSet = e.set) }
    }

    private fun resultLine(tag: String, s: FeelUiState): String {
        val r = s.reveal ?: return "$tag none"
        return String.format(
            Locale.US, "%s q=%s guess=%.3f true=%.3f r=%.4f stars=%d verdict=%s visible=%.2f cut=%b tray=%b spot=%s",
            tag, s.questionId, r.guessSize, r.trueSize, r.ratio, r.stars, Scoring.verdict(r.ratio), r.visible, r.cut, r.onTray, FeelLog.pos(r.spot),
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
            o.put("result", JSONObject().put("r", r.ratio).put("stars", r.stars).put("verdict", Scoring.verdict(r.ratio).toString()).put("visible", r.visible).put("cut", r.cut).put("inside", r.inside).put("wrapped", r.wrapped).put("hidden", r.hidden).put("onTray", r.onTray).put("spot", FeelLog.pos(r.spot)))
        }
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

        /** The design's QA guesses per question (`stateModel.qaCombos`). */
        val DEFAULT_GUESS = mapOf("co2-1kg" to 0.65f, "kg-gold" to 0.3f, "co2-flight" to 4.0f, "kg-hydrogen" to 1.0f, "co2-1t" to 1.0f, "big-co2-capita" to 2.0f)
    }
}
