package io.embodit.feelnumber.ui.feel.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import androidx.compose.foundation.clickable
import io.embodit.feelnumber.R
import io.embodit.feelnumber.domain.model.Body
import io.embodit.feelnumber.domain.model.Geometry
import io.embodit.feelnumber.domain.model.Placement
import io.embodit.feelnumber.domain.model.QuestionBank
import io.embodit.feelnumber.domain.model.RoomBox
import io.embodit.feelnumber.domain.model.RoomSource
import io.embodit.feelnumber.domain.model.Scoring
import io.embodit.feelnumber.domain.model.Shape
import io.embodit.feelnumber.domain.model.Verdict
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.ui.feel.FeelEvent
import io.embodit.feelnumber.ui.feel.FeelUiState
import io.embodit.feelnumber.ui.feel.Lang
import io.embodit.feelnumber.ui.feel.Note
import io.embodit.feelnumber.ui.feel.Page
import io.embodit.feelnumber.ui.feel.Phase
import io.embodit.feelnumber.ui.feel.debug.probe
import io.embodit.feelnumber.ui.feel.scene.PanelSpec
import io.embodit.feelnumber.ui.theme.FeelColors

private val CardW: Dp = PanelSpec.CARD_SIZE.first.dp
private val CardH: Dp = PanelSpec.CARD_SIZE.second.dp

/**
 * The question card (design `panels.questionCard`): the only standing panel, lower left of the view.
 * Front = the question and the size controls; after the reveal it turns into the result; the deck,
 * about and room pages replace it while open. [filled] = the pooled panel is in view (draw only then).
 */
@Composable
internal fun QuestionCard(state: FeelUiState, filled: Boolean, onEvent: (FeelEvent) -> Unit) {
    // rebuilt after every change of page / state / question / note / language (black-block guard, see Glass)
    // (S04 - S06 count as S03: a rebuild must not cut a slider drag short)
    val stateKey = state.stateId.replace(Regex("S0[456]"), "S03")
    val refresh = listOf(filled, state.page, stateKey, state.questionId, state.note, state.reveal?.stars, state.reveal?.cut, state.reveal?.hidden, state.reveal?.wrapped)
    Glass(width = CardW, height = CardH, padding = 20.dp, refreshKey = refresh) {
        if (!filled) return@Glass
        when (state.page) {
            Page.DECK -> DeckPage(state, onEvent)
            Page.ABOUT -> AboutPage(state, onEvent)
            Page.ROOM -> RoomPage(state, onEvent)
            Page.MAIN -> if (state.showsResult) ResultPage(state, onEvent) else GuessPage(state, onEvent)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// top bar: set chip · language · sound · deck
// -------------------------------------------------------------------------------------------------

@Composable
private fun TopBar(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    val q = state.question
    val set = QuestionBank.set(q.set)
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "${set.name(state.en)} ${QuestionBank.indexInSet(q.id)}/5",
            style = FeelType.label,
            color = PicoTheme.colorScheme.labelPrimary,
            maxLines = 1,
            modifier = Modifier.clip(CircleShape).drawBehind { drawRect(Color.White.copy(alpha = 0.14f)) }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        LangSegment(state, onEvent)
        Key(name = "mute", iconRes = if (state.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, description = rs(if (state.muted) R.string.action_unmute else R.string.action_mute), width = 48.dp) { onEvent(FeelEvent.ToggleMute) }
        Key(name = "deck", iconRes = R.drawable.ic_list, description = rs(R.string.action_deck), width = 48.dp) { onEvent(FeelEvent.OpenDeck) }
    }
}

@Composable
private fun LangSegment(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    SegmentControl(modifier = Modifier.width(112.dp).height(KeyHeight), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        listOf(Lang.ZH to "中", Lang.EN to "EN").forEach { (lang, label) ->
            val onClick = { onEvent(FeelEvent.SetLang(lang)) }
            SegmentItem(
                selected = state.lang == lang,
                onClick = onClick,
                modifier = Modifier.width(52.dp).fillMaxHeight().probe("lang_${lang.name.lowercase()}", onClick),
                textStyle = FeelType.label,
                colors = selectedColors(),
                title = { Text(text = label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun selectedColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = PicoTheme.colorScheme.labelPrimaryLight,
        selectedItemContentColor = FeelColors.Ink,
    )

// -------------------------------------------------------------------------------------------------
// guess page (S01 - S06)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.GuessPage(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    val q = state.question
    val ready = state.phase == Phase.GUESS
    TopBar(state, onEvent)
    Text(text = q.prompt(state.en), style = FeelType.question, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    Spacer(modifier = Modifier.height(4.dp))
    // logarithmic slider, no ticks, no numbers: a small ball at the left end, a big one at the right
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painter = painterResource(R.drawable.ic_ball), contentDescription = rs(R.string.slider_small), modifier = Modifier.size(12.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Box(modifier = Modifier.weight(1f).probe("slider")) {
            Slider(
                Placement.sliderOf(state.guess.size),
                { v: Float -> onEvent(FeelEvent.Slider(v)) },
                Modifier.fillMaxWidth(),
                0f..1f,
                { onEvent(FeelEvent.SliderEnded) },
                ready,
                colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
            )
        }
        Icon(painter = painterResource(R.drawable.ic_ball), contentDescription = rs(R.string.slider_big), modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
    // hint line (+ [Move to marker] while guessing, [Room] in the virtual room)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = hintText(state), style = FeelType.body, color = noteColor(state), maxLines = 6, modifier = Modifier.weight(1f).padding(top = 4.dp).probe("hint"))
        if (ready) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WordButton(name = "to_marker", text = rs(R.string.action_to_marker), onClick = { onEvent(FeelEvent.BringToMarker) }, minWidth = 120.dp, iconRes = R.drawable.ic_bring, enabled = state.marker != null && !state.atMarker)
                if (state.roomSource == RoomSource.VIRTUAL) {
                    WordButton(name = "hint_room", text = rs(R.string.action_room), onClick = { onEvent(FeelEvent.OpenRoom) }, minWidth = 120.dp, iconRes = R.drawable.ic_room)
                }
            }
        }
    }
    Spacer(modifier = Modifier.weight(1f))
    Text(text = rs(R.string.joystick_hint), style = FeelType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    WordButton(name = "reveal", text = rs(R.string.action_reveal), onClick = { onEvent(FeelEvent.Reveal) }, modifier = Modifier.fillMaxWidth(), primary = true, enabled = ready, minWidth = 200.dp)
}

/** Hint text stays in labelPrimary: the accent colours are too light for text on the glass (taste D13). */
@Composable
private fun noteColor(@Suppress("UNUSED_PARAMETER") state: FeelUiState): Color = PicoTheme.colorScheme.labelPrimary

/** Hint line by state (design `states[].panelHint`, `stateModel.hintPriority`); a one-off note wins. */
@Composable
private fun hintText(state: FeelUiState): String {
    when (state.note) {
        Note.SCANNED_ROOM -> return rs(R.string.note_scanned)
        Note.NO_CEILING -> return rs(R.string.note_no_ceiling)
        null -> Unit
    }
    val id = state.stateId.substringBefore("+")
    return when (id) {
        "S01" -> rs(R.string.hint_s01)
        "S02" -> rs(R.string.hint_s02, state.room?.label ?: "")
        "S04" -> rs(R.string.hint_s04)
        "S05" -> rs(R.string.hint_s05)
        "S06" -> rs(R.string.hint_s06)
        "S07" -> rs(R.string.hint_s07)
        "S08" -> if (state.marker != null && !state.atMarker) rs(R.string.hint_marker_set) else rs(R.string.hint_s08)
        "S09" -> rs(R.string.hint_s09)
        "S10" -> rs(R.string.hint_s10)
        "S11" -> rs(R.string.hint_s11)
        "S14" -> rs(R.string.hint_s14)
        else ->
            when {
                // taste D1: content goes where the user puts a marker, not in front of the head
                state.marker == null -> rs(R.string.hint_place_marker)
                !state.atMarker -> rs(R.string.hint_marker_set)
                state.questionId == QuestionBank.FIRST && !state.guess.touched -> rs(R.string.hint_first)
                else -> rs(R.string.hint_s03)
            }
    }
}

// -------------------------------------------------------------------------------------------------
// result page (S07 - S10)
// -------------------------------------------------------------------------------------------------

@Composable
private fun sizeText(shape: Shape, size: Float): String = rs(if (shape == Shape.SPHERE) R.string.size_diameter else R.string.size_edge, metres(size))

@Composable
private fun verdictText(r: Double): String =
    when (val v = Scoring.verdict(r)) {
        is Verdict.TimesBigger -> rs(R.string.verdict_times, v.x)
        is Verdict.PercentBigger -> rs(R.string.verdict_plus, v.p)
        is Verdict.PercentSmaller -> rs(R.string.verdict_minus, v.p)
        is Verdict.FractionOf -> rs(R.string.verdict_fraction, v.x)
    }

@Composable
private fun ColumnScope.ResultPage(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    val q = state.question
    val r = state.reveal ?: return
    val done = state.phase == Phase.RESULT
    TopBar(state, onEvent)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Swatch(FeelColors.TrueTeal, 14.dp, Modifier.padding(bottom = 10.dp))
        Text(text = sizeText(q.shape, r.trueSize), style = FeelType.big, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.probe("result_true"))
        Text(text = q.title(state.en), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(bottom = 2.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Swatch(FeelColors.GuessBlue, 10.dp, dashed = true)
        Text(text = rs(R.string.you_guessed, sizeText(q.shape, r.guessSize)), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = verdictText(r.ratio), style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.weight(1f).probe("verdict"))
        Stars(r.stars)
        Text(
            text = rs(when (r.stars) { 3 -> R.string.stars_3; 2 -> R.string.stars_2; 1 -> R.string.stars_1; else -> R.string.stars_0 }),
            style = FeelType.label,
            color = PicoTheme.colorScheme.labelPrimary,
            maxLines = 1,
        )
    }
    if (Scoring.showsLesson(r.ratio)) Text(text = rs(R.string.lesson, 0), style = FeelType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    SideView(state, Modifier.fillMaxWidth().height(132.dp))
    val pct = (r.visible * 100f).toInt().coerceIn(0, 100)
    if (r.inside || r.cut) {
        // alert line: an amber key + labelPrimary text (amber text on the glass is under 3:1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.probe("alert_line")) {
            Box(modifier = Modifier.size(width = 6.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)).drawBehind { drawRect(FeelColors.CutAmber) })
            Text(text = rs(if (r.inside) R.string.inside_line else R.string.cut_line, pct), style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
    }
    Text(text = q.fact(state.en), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(text = rs(R.string.source_line, q.source(state.en)), style = FeelType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val setQs = QuestionBank.inSet(q.set)
    val three = setQs.count { (state.best[it.id]?.stars ?: 0) == 3 }
    val tried = setQs.count { state.best.containsKey(it.id) }
    Text(text = rs(R.string.progress_line, tried, setQs.size, three), style = FeelType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton(name = "to_marker", text = rs(R.string.action_to_marker), onClick = { onEvent(FeelEvent.BringToMarker) }, modifier = Modifier.weight(1.3f), enabled = done && state.marker != null && !state.atMarker, minWidth = 0.dp)
        WordButton(name = "hide", text = rs(if (r.hidden) R.string.action_show else R.string.action_hide), onClick = { onEvent(FeelEvent.ToggleHidden) }, modifier = Modifier.weight(1f), enabled = done, minWidth = 0.dp)
        if (!r.onTray && r.trueSize >= Placement.WRAP_MIN_M) {
            WordButton(name = "wrap", text = rs(if (r.wrapped) R.string.action_unwrap else R.string.action_wrap), onClick = { onEvent(FeelEvent.ToggleWrap) }, modifier = Modifier.weight(1f), enabled = done, minWidth = 0.dp)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton(name = "again", text = rs(R.string.action_again), onClick = { onEvent(FeelEvent.Again) }, modifier = Modifier.weight(1f), enabled = done, minWidth = 0.dp)
        WordButton(
            name = "next",
            text = rs(if (QuestionBank.isLastInSet(q.id)) R.string.action_next_set else R.string.action_next),
            onClick = { onEvent(FeelEvent.Next) },
            modifier = Modifier.weight(1f),
            primary = true,
            enabled = done,
            minWidth = 0.dp,
        )
    }
}

/** A colour key in front of a line (the colours themselves are too light for text on the glass, taste D13). */
@Composable
private fun Swatch(color: Color, size: Dp, modifier: Modifier = Modifier, dashed: Boolean = false) {
    Box(
        modifier =
            modifier.size(size).drawBehind {
                if (dashed) drawCircle(color, radius = this.size.minDimension / 2f - 1.5f, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
                else drawCircle(color)
            }
    )
}

@Composable
private fun Stars(n: Int) {
    // on a dark capsule: gold stars on the light glass are under 3:1 (taste D13)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.clip(CircleShape).drawBehind { drawRect(Color.Black.copy(alpha = 0.6f)) }.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        repeat(3) { i ->
            Icon(painter = painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(18.dp), tint = if (i < n) FeelColors.StarGold else Color.White.copy(alpha = 0.35f))
        }
    }
}

/**
 * Side view (design `clipModel.sideView`): the room's section along head -> spot, your silhouette, the
 * true outline (teal) and the guess (dashed blue), at one scale; the whole truth is drawn even when it
 * does not fit the room.
 */
@Composable
private fun SideView(state: FeelUiState, modifier: Modifier) {
    val room = state.room ?: return
    val tb = state.trueBody ?: return
    val wb = state.wireBody ?: return
    val head = state.head
    // drawn on its own dark plate, so the colours read the same on any glass
    val labelColor = Color.White.copy(alpha = 0.9f)
    val frameColor = Color.White.copy(alpha = 0.55f)
    Box(
        modifier =
            modifier.clip(RoundedCornerShape(16.dp)).drawBehind {
                drawRect(Color.Black.copy(alpha = 0.55f))
                // horizontal axis: from head towards the truth
                val dirRaw = Vec3(tb.center.x - head.x, 0f, tb.center.z - head.z)
                val dir = if (dirRaw.length < 0.05f) RoomBox.level(state.facing) else dirRaw.normalized()
                fun along(p: Vec3) = (p.x - head.x) * dir.x + (p.z - head.z) * dir.z
                val back = room.exitDistance(Vec3(head.x, room.floorY, head.z), -dir) ?: 1f
                val ahead = room.exitDistance(Vec3(head.x, room.floorY, head.z), dir) ?: 4f
                val headH = (head.y - room.floorY + 0.1f).coerceAtLeast(1.2f)
                val tc = along(tb.center)
                // the section is cropped to you and the bodies (+ 1 m); walls further away are left open
                val minX = minOf(-minOf(back, 1.0f), tc - tb.half, along(wb.center) - wb.half) - 0.2f
                val maxX = maxOf(minOf(ahead, tc + tb.half + 1.0f), tc + tb.half, along(wb.center) + wb.half, 0.6f) + 0.2f
                val maxY = maxOf(if (room.hasCeiling) room.height else 0f, tb.topY - room.floorY, wb.topY - room.floorY, headH)
                val pad = 10f
                val k = minOf((size.width - 2 * pad) / (maxX - minX), (size.height - 2 * pad) / maxY)
                fun px(x: Float) = pad + (x - minX) * k
                fun py(y: Float) = size.height - pad - y * k
                // room section
                val floorPy = py(0f)
                drawLine(frameColor, Offset(px(minX), floorPy), Offset(px(maxX), floorPy), strokeWidth = 3f)
                if (room.hasCeiling) drawLine(frameColor, Offset(px(minX), py(room.height)), Offset(px(maxX), py(room.height)), strokeWidth = 2f)
                val top = if (room.hasCeiling) py(room.height) else pad
                if (-back >= minX) drawLine(frameColor, Offset(px(-back), top), Offset(px(-back), floorPy), strokeWidth = 3f)
                if (ahead <= maxX) drawLine(frameColor, Offset(px(ahead), top), Offset(px(ahead), floorPy), strokeWidth = 3f)
                // truth (teal) and guess (dashed blue)
                fun outline(b: Body, color: Color, dashed: Boolean) {
                    val cx = px(along(b.center))
                    val cy = py(b.center.y - room.floorY)
                    val r = b.half * k
                    val stroke = Stroke(width = 3f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
                    if (b.shape == Shape.SPHERE) drawCircle(color, radius = r, center = Offset(cx, cy), style = stroke)
                    else drawRect(color, topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r), style = stroke)
                }
                if (tb.shape == Shape.SPHERE) drawCircle(FeelColors.TrueTeal.copy(alpha = 0.35f), radius = tb.half * k, center = Offset(px(tc), py(tb.center.y - room.floorY)))
                outline(tb, FeelColors.TrueTeal, dashed = false)
                outline(wb, FeelColors.GuessBlue, dashed = true)
                // you: head + body
                val hx = px(0f)
                val hr = 0.11f * k
                drawCircle(labelColor, radius = hr.coerceAtLeast(3f), center = Offset(hx, py(headH - 0.12f)))
                drawLine(labelColor, Offset(hx, py(headH - 0.24f)), Offset(hx, py(0f)), strokeWidth = (0.14f * k).coerceAtLeast(3f))
            }
    )
}

// -------------------------------------------------------------------------------------------------
// deck (S11) and about (S12)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.DeckPage(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    TitleRow(
        title = rs(R.string.deck_title),
        trailing = {
            LangSegment(state, onEvent)
            Key(name = "deck_close", iconRes = R.drawable.ic_close, description = rs(R.string.action_close), width = 48.dp) { onEvent(FeelEvent.CloseDeck) }
        },
    )
    val sets = QuestionBank.sets
    SegmentControl(modifier = Modifier.fillMaxWidth().height(KeyHeight), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        sets.forEach { set ->
            val onClick = { onEvent(FeelEvent.DeckSet(set.id)) }
            SegmentItem(
                selected = state.deckSet == set.id,
                onClick = onClick,
                modifier = Modifier.weight(1f).fillMaxHeight().probe("set_${set.id}", onClick),
                textStyle = FeelType.label,
                colors = selectedColors(),
                title = { Text(text = set.name(state.en), maxLines = 1, softWrap = false) },
            )
        }
    }
    QuestionBank.inSet(state.deckSet).forEach { q ->
        val best = state.best[q.id]
        val current = q.id == state.questionId
        val onClick = { onEvent(FeelEvent.Pick(q.id)) }
        Row(
            modifier =
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                    .drawBehind { drawRect(if (current) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f)) }
                    .spatialHoverEffect()
                    .clickable(onClick = onClick)
                    .probe("pick_${q.id}", onClick)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = q.title(state.en), style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (best == null) Text(text = rs(R.string.deck_not_tried), style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary) else Stars(best.stars)
        }
    }
    Spacer(modifier = Modifier.weight(1f))
    val done = state.best.size
    val err = Scoring.meanErrorPercent(state.best.values.map { it.ratio })
    Text(
        text = rs(R.string.deck_done, done) + (err?.let { "  ·  " + rs(R.string.deck_error, it) } ?: ""),
        style = FeelType.label,
        color = PicoTheme.colorScheme.labelPrimary,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton(name = "deck_room", text = rs(R.string.action_room), onClick = { onEvent(FeelEvent.OpenRoom) }, modifier = Modifier.weight(1f), minWidth = 0.dp, iconRes = R.drawable.ic_room)
        WordButton(name = "deck_about", text = rs(R.string.action_about), onClick = { onEvent(FeelEvent.OpenAbout) }, modifier = Modifier.weight(1f), minWidth = 0.dp, iconRes = R.drawable.ic_info)
    }
}

@Composable
private fun ColumnScope.AboutPage(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    TitleRow(
        title = rs(R.string.about_title),
        leading = { Key(name = "about_back", iconRes = R.drawable.ic_back, description = rs(R.string.action_back), width = 48.dp) { onEvent(FeelEvent.BackToDeck) } },
    )
    Text(text = rs(R.string.about_body, 0), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 7)
    Text(text = rs(R.string.about_credit), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3)
    Text(text = rs(R.string.about_sources), style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(top = 4.dp))
    // the sources of the set open in the deck: five at a time keeps the page airy (taste B19)
    Text(text = QuestionBank.set(state.deckSet).name(state.en), style = FeelType.label, color = PicoTheme.colorScheme.labelPrimary)
    QuestionBank.inSet(state.deckSet).forEach { q ->
        Text(text = "${q.title(state.en)} — ${q.source(state.en).substringBefore("；").substringBefore("; ")}", style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// -------------------------------------------------------------------------------------------------
// room page (S14)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.RoomPage(state: FeelUiState, onEvent: (FeelEvent) -> Unit) {
    val room = state.room
    TitleRow(
        title = rs(R.string.room_title),
        leading = { Key(name = "room_back", iconRes = R.drawable.ic_back, description = rs(R.string.action_back), width = 48.dp) { onEvent(FeelEvent.BackToDeck) } },
    )
    Text(text = rs(if (state.roomSource == RoomSource.SCAN) R.string.room_from_scan else R.string.room_from_virtual), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary)
    if (room != null) {
        val step = RoomBox.STEP_M
        RoomStepper("room_l", rs(R.string.room_length, fmt1(room.length))) { d -> onEvent(FeelEvent.RoomResize(d * step, 0f, 0f)) }
        RoomStepper("room_w", rs(R.string.room_width, fmt1(room.width))) { d -> onEvent(FeelEvent.RoomResize(0f, d * step, 0f)) }
        RoomStepper("room_h", rs(R.string.room_height, if (room.hasCeiling) fmt1(room.height) else "—")) { d -> onEvent(FeelEvent.RoomResize(0f, 0f, d * step)) }
    }
    Text(text = rs(R.string.hint_s14), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton(name = "room_align", text = rs(R.string.room_align), onClick = { onEvent(FeelEvent.RoomAlign) }, modifier = Modifier.weight(1f), minWidth = 0.dp, iconRes = R.drawable.ic_bring)
        if (state.scanAvailable && (state.roomSource != RoomSource.SCAN || state.roomEdited)) {
            WordButton(name = "room_scan", text = rs(R.string.room_use_scan), onClick = { onEvent(FeelEvent.RoomUseScan) }, modifier = Modifier.weight(1f), minWidth = 0.dp, iconRes = R.drawable.ic_room)
        }
    }
}

/** A stepper row: [-] value [+], 0.1 m a press. */
@Composable
private fun RoomStepper(name: String, text: String, onStep: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(KeyHeight).clip(RoundedCornerShape(24.dp)).drawBehind { drawRect(Color.White.copy(alpha = 0.08f)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(name = "${name}_minus", iconRes = R.drawable.ic_minus, description = rs(R.string.room_shorter), width = 56.dp) { onStep(-1) }
        Text(text = text, style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).probe(name))
        Key(name = "${name}_plus", iconRes = R.drawable.ic_plus, description = rs(R.string.room_longer), width = 56.dp) { onStep(1) }
    }
}

// -------------------------------------------------------------------------------------------------
// size tag
// -------------------------------------------------------------------------------------------------

/** The size tag above the true body (design `panels.sizeTag`). */
@Composable
internal fun SizeTag(state: FeelUiState, filled: Boolean) {
    val (w, h) = PanelSpec.TAG_SIZE
    Glass(width = w.dp, height = h.dp, corner = 24.dp, padding = 12.dp, refreshKey = listOf(filled, state.questionId, state.reveal?.cut, state.lang)) {
        if (!filled) return@Glass
        val q = state.question
        val r = state.reveal ?: return@Glass
        Text(text = q.title(state.en), style = FeelType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(FeelColors.TrueTeal, 10.dp)
            Text(text = rs(R.string.tag_volume, sizeText(q.shape, r.trueSize), cubic(Geometry.volumeOf(q.shape, r.trueSize.toDouble()))), style = FeelType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (r.cut) {
                Text(
                    text = rs(R.string.tag_cut),
                    style = FeelType.label,
                    color = FeelColors.Ink,
                    maxLines = 1,
                    modifier = Modifier.clip(CircleShape).drawBehind { drawRect(FeelColors.CutAmber) }.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}
