package io.embodit.feelnumber.debug

import android.content.Context
import android.content.Intent
import io.embodit.feelnumber.domain.model.Vec3
import io.embodit.feelnumber.platform.FeelLog
import io.embodit.feelnumber.ui.feel.FeelEvent
import io.embodit.feelnumber.ui.feel.Lang
import io.embodit.feelnumber.ui.feel.debug.FeelProbe
import java.io.File

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `FeelNumber`) answers every command.
 *
 * `am broadcast -a io.embodit.feelnumber.DEBUG -p io.embodit.feelnumber --es cmd <command> [extras]`
 *
 * - `goto --es s S01..S14 [--es q <question id>] [--es set co2|kilo|body|big]`  straight to a designed state with the
 *                                  design's QA setup (S08 / S09 / S10 use the `qaCombos` guesses; S10 presses [Wrap me])
 * - `q --es id <question id>`      a fresh guess for that question
 * - `guess --ef size 0.65`         guess size in metres, put on the tray / the floor by the 0.30 m threshold
 * - `spot --ef x 0.5 --ef z -2`    the user's spot, metres from the head (Stage axes)
 * - `reveal [--ef t 0.5]`          reveal; with t the growth is frozen at that progress (t = 1: straight to the result)
 * - `yaw --ef deg 30`              the guess's turn
 * - `drag_pose --ei on 1 [--es wall 1]`  the drag (S06) and wall-snap visuals
 * - `best --es v seed|clear`       best results seeded / cleared
 * - `lang --es id zh|en`, `mute --ei on 1|0`, `wrap --ei on 1|0`
 * - `room --ef l 5 --ef w 4 --ef h 2.6 [--ei virtual 1]`  room size; virtual = drop the scan and use a virtual room
 * - `freeze --ei on 1|0`           plane detection / scan timeout no longer change the state
 * - `noscan --ei on 1|0`           detected planes ignored (the nothing-detected path; with `room --ei virtual 1`)
 * - `ray --es v "ox,oy,oz,dx,dy,dz"` / `ray --es v off`  a stand-in controller ray (ghost ring, S04)
 * - `ui --es id <control>`         presses a question-card control by name (reveal, again, next, to_front, hide, wrap,
 *                                  back_in_front, hint_room, deck, deck_close, set_*, pick_<id>, deck_room, deck_about,
 *                                  about_back, room_back, room_l_minus / _plus, room_w_*, room_h_*, room_align, room_scan,
 *                                  lang_zh, lang_en, mute)
 * - `state`                        `STATE {json}` (also files/state.json): state, question, guess / true volumes, r,
 *                                  stars, visible share, inside, room box
 * - `pose`, `panels`, `planes`, `park`
 */
internal object DebugCommands {
    fun run(context: Context, cmd: String, intent: Intent) {
        val host = FeelProbe.host
        if (host == null) {
            FeelLog.w("CMD $cmd ignored: no stage")
            return
        }
        fun f(key: String): Float? = if (intent.hasExtra(key)) intent.getFloatExtra(key, 0f).takeIf { !it.isNaN() } ?: intent.getStringExtra(key)?.toFloatOrNull() else null
        fun on(): Boolean = intent.getIntExtra("on", 1) != 0
        fun s(key: String): String? = intent.getStringExtra(key)
        val ev: (FeelEvent) -> Unit = { host.dispatch(it) }
        when (cmd.trim().lowercase()) {
            "goto" -> ev(FeelEvent.DebugGoto(s("s") ?: "S03", s("q"), s("set")))
            "q" -> ev(FeelEvent.Pick(s("id") ?: "co2-1kg"))
            "guess" -> ev(FeelEvent.DebugGuess(f("size") ?: 0.25f))
            "spot" -> ev(FeelEvent.DebugSpot(f("x") ?: 0f, f("z") ?: -1.8f))
            "reveal" -> ev(FeelEvent.DebugReveal(f("t")))
            "yaw" -> ev(FeelEvent.DebugYaw(f("deg") ?: 0f))
            "drag_pose" -> ev(FeelEvent.DebugDragPose(on(), s("wall") == "1"))
            "best" -> ev(FeelEvent.DebugBest(s("v") != "clear"))
            "lang" -> ev(FeelEvent.SetLang(if (s("id") == "en") Lang.EN else Lang.ZH))
            "mute" -> ev(FeelEvent.DebugMute(on()))
            "wrap" -> ev(FeelEvent.DebugWrap(on()))
            "room" -> ev(FeelEvent.DebugRoom(f("l"), f("w"), f("h"), intent.getIntExtra("virtual", 0) != 0))
            "freeze" -> {
                host.setFrozen(on())
                FeelLog.i("CMD freeze ${on()}")
            }
            "noscan" -> FeelLog.i("CMD ${host.scene()?.setNoScan(on())}")
            "ray" -> {
                val v = s("v") ?: "off"
                val n = v.split(",").mapNotNull { it.trim().toFloatOrNull() }
                val line = if (n.size == 6) host.scene()?.setDebugRay(Vec3(n[0], n[1], n[2]), Vec3(n[3], n[4], n[5])) else host.scene()?.setDebugRay(null, null)
                FeelLog.i("CMD $line")
            }
            "ui" -> {
                val id = s("id") ?: ""
                FeelLog.i("CMD ui $id pressed=${host.pressControl(id)}")
            }
            "state" -> {
                val json = host.dumpJson()
                runCatching { File(context.filesDir, "state.json").writeText(json) }
                FeelLog.i("STATE $json")
            }
            "pose" -> FeelLog.i(host.scene()?.poseLine() ?: "POSE none")
            "panels" -> host.scene()?.panelLines()?.forEach { FeelLog.i(it) }
            "planes" -> host.scene()?.planesSummary()?.forEach { FeelLog.i(it) }
            "park" -> FeelLog.i(host.scene()?.parkPanel() ?: "")
            else -> FeelLog.w("CMD unknown '$cmd'")
        }
    }
}
