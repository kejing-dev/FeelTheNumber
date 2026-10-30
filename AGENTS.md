# Feel the Number (量感)

Guess-the-size app for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space, passthrough),
package `io.embodit.feelnumber`. A question says "1 kg of CO₂"; you pull a ball (or cube) out of the floor to the size
you think it is, tap Reveal, and the true volume grows 1:1 from the same spot, with your error in volume and stars.
Anything bigger than your room is cut by the walls and ceiling (amber cut rings); [Wrap me] puts you inside it.

Design package (binding): `../spatial-app-factory/apps/feel-number/design-package.json` v1.1 (archetype D,
STAGE_MIXED, single panel, states S01 - S14, 20 questions). PRD and mockup: links in the factory's state.json.
Skeleton taken from `../fire-plan` (itself from `../spatial-ruler`): plane tracker, head tracker, controller input,
panel pool (`PanelRig`), materials and meshes (`SceneKit`), debug probe. Their AGENTS.md hold the SDK findings.

Built by the factory's build station on 2026-10-27 (v0.1.0, debug) and run on emulator-5568 (AVD PICO_6_1_Factory5):
every designed state captured through the QA hooks, real gaze + pinch input checked for the floor tap, the handle pull
and the body drag. Not yet run on a headset.

## v0.1.1 (2026-10-28): marker placement, content never blocks the card

Headset feedback on v0.1.0: "you used the headset ray to place the ball again … use the controller ray marker on the floor
and [bring it to the marker]; otherwise the ball is right in front of me and blocks the UI". Now (taste D1 / D20):
- A pinch / trigger on the floor puts a **marker** down (blue ring + post); it moves nothing. [Move to marker] (`to_marker`,
  on the guess page and the result page) brings the guess or the revealed pair to it. Before any marker the content
  stands 2.5 m out, 55 degrees right of the question card (taken once from the card's position); the hint asks for a
  marker. Small bodies sit on a tray 1.0 m above the anchor (thin stand), not in front of the head any more.
- [Bring back] and [In front] (head-forward placement) are gone. The anchor holds for the whole session (next / again /
  deck return to it).
- Every frame the guess / truth / wireframe are tested against the view cone eye -> card corners (nearer than card + 0.5 m);
  inside it they drop to <= 30 % (`CONE guess= truth=` in the log).
- Head-pose uses that remain (card follow, facing, clearance push, handle side, inside checks, room frame, wrap me) are
  listed one by one in `../spatial-app-factory/apps/feel-number/build/ui-audit.md`.
- Debug `spot --ef x --ef z` now = a marker at head + (x, z), then [Move to marker].

## v0.1.5 (2026-10-29): critic fixes (card fixed, truth on the floor, no snap)

From the critic report on v0.1.4 (`../spatial-app-factory/apps/feel-number/critic/build-v0.1.4.md`):
- The truth appears the way the real thing would: on the floor at real size (under 0.30 m on the stand 1 m above), not
  at the guess's temporary height; only the truth moves sideways when it would reach the viewer (the card then says so,
  `Reveal.movedBack`). The guess wireframe stays where the guess was left. "Wrap me" / "X % fits" use the floor truth.
- The question card is put down once (30 left / 20 down / 1.0 m, turned to you once) and stays fixed. Drag the grip
  bar under it to move it; when it is out of view (> 50 degrees or > 3 m) a "Bring the card back" pill appears under the
  view and puts it straight ahead. No lazy follow, no per-frame facing.
- Result page [Move to marker] also carries the next guess onto the marker (next / again start there).
- No wall snap; a base within 10 cm of the floor counts as on the floor (`Placement.ON_FLOOR_M`).
- Every question (and [Move to marker]) turns the body to face where you stand, once (`Placement.faceYaw`).
- Debug `guess`, `drag_pose` and `goto` send the same `FeelEvent`s as real input (no state shortcuts).
- Icons: standard Material Icons (Apache 2.0) from `tools/make_icons.py`, contact sheet `tools/icons.html`.
- `FeelPlacementStateTest`: the placement state machine driven by events, and the four-direction facing test.

## v0.1.4 (2026-10-29): it stays where you leave it

Headset feedback: "拉那个球之后好像会掉下去，保持球的位置". A resize no longer moves the guess (no tray -> floor drop on
release, no clearance push while growing); a drag moves the base in 3D (it may stay in the air, never below the floor) and
stays where let go; a new question / guess again starts where the last guess was left; room updates only follow the floor
height; the truth grows from the guess's base height. [Move to marker] is the only placement that moves it (onto the
marker; small bodies on the stand 1 m above). Where the drop came from: ui-audit.md, v0.1.4 section.

## v0.1.3 (2026-10-29): no room clipping at all

Headset feedback: "点了 reveal 之后就变 2D 了，不用 cut by the room". Every body (guess, the 8 growth levels, the final truth,
its lines, the guess wireframe, the inner surface) is now built with `room = null`: nothing is clipped or flattened, a body
bigger than the room is drawn whole (through the walls). No cut rings. "Doesn't fit" stays as text only (result card
"Bigger than your room: only X % fits inside", size-tag chip "Bigger than your room"). Why the first reveal looked fine and
later ones went flat: see the v0.1.3 section of `../spatial-app-factory/apps/feel-number/build/ui-audit.md`.

## v0.1.2 (2026-10-28): released ball went flat against the wall

Headset feedback on v0.1.1: "松开后球怎么直接变成 2D 的还贴到边上". Cause: on every release (resize end) the v0.1.1
view-cone push ran without a limit and could move the guess metres past the room's wall; the room clip then clamped every
vertex onto the wall plane, a flat disc. Now: the cone push runs only when content is put at an anchor (new question,
[Move to marker]) and stops inside the room (`Placement.clearOfPanel`); a release, resize or drag never moves the guess
for the cone (it only fades, and takes no taps while it sits between the eyes and the card); a drag stays exactly where it
was let go; the guess is clipped by the room only while its axis is inside the room (`Clip.clipsGuess`), so a guess
outside is drawn whole. The reveal no longer pushes for the cone either (fade only). Unit tests 22 (5 new release cases).

## v0.1.6 (2026-10-30): one base for the truth and the guess; settings + quit; upright panels

Headset feedback on v0.1.5: "怎么有的 reveal 的绿色答案，和蓝色不同起点呢，绿色在下，蓝色在上，怎么不 consistent 呢". v0.1.5 put the
truth on the floor while the guess wireframe stayed where the guess was left (in the air on its stand), so the two had
different bases. Now (taste D1 "揭晓 / 对比同一基准"):
- `Placement.pairPose` is the one anchor function of the reveal: `Reveal.base` is the bottom centre of the true body AND
  of the guess wireframe, `Reveal.yawDeg` the turn of both, `Reveal.lift` the base's height above the floor (the guess
  left in the air keeps the pair in the air, on the stand; on the floor keeps both on the floor). The true size never
  changes. Only when the true body would reach the viewer (D14) does the pair move away **together**, level, and the
  card says "both moved back a little, together" (`Reveal.movedBack`). `trueBody` / `wireBody` are derived from the
  same base; [Move to marker], a pair drag, [Wrap me] and room updates move `base` only (`Placement.pairAt`).
  The 1 m stand for small truths is gone: a 0.037 m gold cube reveals exactly where the guess stands.
- Settings page (taste D26, `Page.SETTINGS`, state S15): deck -> [Settings]; language and sound rows, [Room] / [About],
  and [Quit the app] at the bottom. The first press opens a confirm row (S16: "Quit Feel the Number?" [Cancel] [Quit]);
  [Quit] sends `FeelEffect.Exit` -> `AppExit.quit()`: every activity `finishAndRemoveTask()`, the trackers stopped,
  the sound pool released, `exitProcess(0)` 0.6 s later. Debug: `goto S15` / `goto S16`, `ui exit` / `exit_cancel` /
  `exit_confirm` (the last one really quits).
- Upright panels (taste D9): `Placement.placeUpright(head, forward, distance, downDeg, leftDeg)` is the only pose
  function for panels - the card at start (30 left / 20 down / 1.0 m), [Bring the card back] (15 down / 1.0 m); it takes
  the head's level heading only (pitch / roll dropped) and returns a rotation about the vertical. Auto-facing room panels
  (the size tag) now turn about the vertical only (`PanelRig.face`: pitch 0). Unit test: head down 30 / up 30 / rolled 20.
- Debug `drag_pose --ei on 1 --ef dx --ef dy --ef dz` moves the guess's base by an offset through the real drag events
  (dy > 0.10 m leaves it in the air), so QA can set up the air / floor / wall reveal shots without a hand.
- Unit tests 45 (domain 26 + state machine 13): `truthAndGuessShareOneBaseInEveryPose` (air / floor / wall / turned:
  bottoms < 1 cm apart, yaw < 1 degree, through reveal, pair drag, marker, room update), `aBigTruthMovesBackTogetherWithTheGuess`,
  `quitNeedsAConfirm`, `debugDragOffsetIsARealDrag`, `pairPoseKeepsLiftAndTurnAndShiftsLevel`, `placeUprightDropsPitchAndRoll`.

## Structure

```
app/src/main/java/io/embodit/feelnumber/
  Main.kt                         DefaultStage { FeelTheme { FeelScreen() } }
  platform/                       SpatialApplication, LaunchActivity, FeelGraph (wiring), FeelLog (tag FeelNumber)
    sense/PlaneTracker            plane detection -> PlaneSurface (Stage metres); `ignoreDetected` = debug noscan
    tracking/HeadTracker          HMD pose
    input/ControllerInput         controller ray, trigger, thumbstick x / y (ControllerTrackingProvider)
    audio/SoundPlayer             pop / chime / tick (SoundPool, res/raw, made by tools/make_sounds.py)
  domain/model                    pure Kotlin, JVM-tested:
    Questions                     the 20 questions (volume is the truth, size derived), sets, order
    Scoring                       ratio, stars, verdict copy rounding, mean error
    Placement                     every number of guessModel: tray, default spot, clearance push (0.8 m, stops at the
                                  wall), reveal push, front / wrap spots, handle size, slider / handle / stick scaling, wall snap
    RoomBox (+ RoomFit)           the room as one level oriented box; fit from FLOOR / WALL / CEILING planes; virtual room
    Clip                          Body, clipped ball / cube meshes (vertices clamped into the box), wireframes, cut rings,
                                  visible share, cut / inside checks
  data/FeelRepository             SharedPreferences: best results, language, mute, virtual room size
  ui/feel/FeelViewModel           the state machine (FeelUiState / FeelEvent in FeelUiState.kt)
  ui/feel/scene                   FeelScene (one ECS frame System: input routing, growth animation, inside check, card
                                  lazy follow, size tag, controller ghost + stick), Views (GuessView, TruthView,
                                  MarksView, RoomView), PanelRig, SceneKit, PanelSpec
  ui/feel/components              FeelStage (SpatialView + 2 pooled panels), QuestionCard (guess / result / deck / about /
                                  room pages + size tag + side view), Common (Glass, keys, buttons, type roles), Strings
app/src/debug/                    DebugCommandReceiver + DebugCommands (QA hooks)
tools/                            make_sounds.py, render_icon.py, harness/fn.sh (broadcast + screenshot), harness/deploy.sh
```

Data flow: panel controls, scene reports (taps, drags, head at 10 Hz, planes at 2 Hz, stick) and debug hooks ->
`FeelViewModel.onEvent` -> `FeelUiState` -> `FeelScene.render` (idempotent) + the card. Per-frame work (handle sizing,
slides, the 8-level growth, inside check, card follow, tag, ghost ring) lives in `FeelScene.onFrame` only.

## Behaviour notes

- Room: scanned planes -> `RoomFit` (2+ walls: turned to the widest wall; else the floor's min-area rectangle; missing
  ceiling = top not cut). No floor within 5 s -> virtual room 5 x 4 x 2.6 m around the viewer (floor = head - 1.6 m);
  a later scan replaces it ("Now using the scanned room"). The room page (deck -> Room, or [Room] on the virtual-room
  hint) has length / width / height steppers (0.1 m), [Around me], [Use the scan]; while it is open, dragging the floor
  moves the room and the stick turns it. The emulator room fits to 6.0 x 10.7 x 2.9 m (19 walls, 8 ceilings), floor y 0.40.
- Guess (v0.1.1): 0.25 m on the tray (1.0 m above the anchor, on a stand) until it is pulled to 0.30 m or more; it moves between tray
  and floor only when a resize ends. The actual spot is the user's spot pushed to level radius + 0.8 m from the head
  (recomputed on resize / drag / tap only), stopped at the room's wall. Bigger than the room = clipped mesh once still.
- Input: handle pull (system drag, 1 cm of hand = x1.08), slider (log 0.02 - 30 m), stick up/down (x2 per second);
  body drag (> 2 cm, hand travel x distance / 0.45 m), no wall snap (v0.1.5);
  floor pinch = the marker (floor catcher, a 30 x 30 m thin box), [Move to marker] moves the content there; controller ray over the floor = ghost ring (S04);
  stick left/right turns the guess (cubes; the ball only matters for its side handle) or the room on the room page.
  No head-forward ray anywhere: the head pose is only used to place things in front of you (D9) and for the card.
- Reveal (v0.1.6, taste D1): 8 growth levels built off the main thread (64 x 32 ball / 6 x 16 x 16 cube, drawn whole)
  swapped in over 1.6 s (ease-out). The truth grows from the guess's own base (bottom centre, height and turn exactly as
  the guess was left: in the air on the stand, or on the floor); the guess wireframe stands on the same base. Only when
  the truth's near surface would come within 0.8 m of the head does the pair move away together, level, with a line on
  the card. A body in the view cone to the card fades and takes no taps. Inside the truth: inner surface 25 %.
- Card: 460 x 640 dp at scale 1 (0.37 x 0.51 m), 30 degrees left / 20 down / 1.0 m, fixed where it is put
  (v0.1.5: grip bar to drag, "Bring the card back" pill), turned about the vertical only. Glass panels rebuild their content 1.5 / 4 / 8 s after every
  page / state change (the idle emulator sometimes leaves a half-drawn black block otherwise; Chime Bells fix).
  Text is always labelPrimary / labelSecondary; the accent colours appear only as keys, swatches and on dark plates.

## QA hooks (debug build only)

```
adb -s emulator-5568 shell am broadcast -a io.embodit.feelnumber.DEBUG -p io.embodit.feelnumber --es cmd <command> [extras]
tools/harness/fn.sh cmd <command> [extras]      # same, prints the reply lines
tools/harness/fn.sh shot <name> [wait_s]        # console screenshot, two in a row, the second kept
```

| command | extras | effect |
|---|---|---|
| `goto` | `--es s S01..S16 [--es q <id>] [--es set co2/kilo/body/big]` | straight to a state with the design's QA setup: S01 (frozen), S02 (virtual room), S04 (aiming flag), S05 (guess 3.0 m), S06 (kg-water 1 m at the wall ahead), S07 (frozen at 50 %), S08 co2-1kg 0.65, S09 co2-flight 4.0, S10 co2-1t 1.0 + wrap, S11 deck, S12 about, S14 room, S15 settings, S16 settings + quit confirm; `--es q` picks another question (kg-gold 0.3, kg-hydrogen 1.0, big-co2-capita 2.0 have their QA guesses) |
| `q` | `--es id <id>` | fresh guess for that question |
| `guess` | `--ef size 0.65` | guess size (m), tray / floor by the 0.30 m threshold |
| `spot` | `--ef x 0.5 --ef z -2` | marker at head + (x, z), then [Move to marker] |
| `reveal` | `[--ef t 0.5]` | reveal; t freezes the growth; t = 1 jumps to the result |
| `yaw` | `--ef deg 30` | guess turn |
| `drag_pose` | `--ei on 1 [--es wall 1 \| --ef dx 0 --ef dy 0.6 --ef dz 0]`, `--ei on 0` | a real drag (DragStarted / DragTo / DragEnded): `wall` = to 0.1 m from the wall ahead (no snap), else the base moved by (dx, dy, dz) m (dy > 0.10 leaves it in the air); `on 0` lets go |
| `best` | `--es v seed / clear` | best results |
| `lang` | `--es id zh / en` | language |
| `mute` / `wrap` / `freeze` / `noscan` | `--ei on 1 / 0` | mute; wrap me; freeze plane-driven state changes; ignore detected planes |
| `room` | `--ef l 5 --ef w 4 --ef h 2.6 [--ei virtual 1]` | room size; virtual = drop the scan, virtual room |
| `ray` | `--es v "ox,oy,oz,dx,dy,dz"` / `--es v off` | stand-in controller ray (ghost ring) |
| `ui` | `--es id <control>` | presses a card control: reveal, again, next, to_marker, hide, wrap, hint_room, deck, deck_close, set_*, pick_<id>, deck_room, deck_about, deck_settings, about_back, room_back, room_l/w/h_minus/plus, room_align, room_scan, lang_zh, lang_en, mute, settings_back, settings_room, settings_about, sound_on, sound_off, exit, exit_cancel, exit_confirm (really quits) |
| `state` | | `STATE {json}` (also files/state.json): state id, question, guess / true size and volume, r, stars, verdict, visible share, cut, inside, room box; `result.base` / `result.yaw` / `result.lift` = the shared base of truth and wireframe (D1) |
| `pose`, `panels`, `planes`, `park` | | scene queries; park = card back in front |

Log tag `FeelNumber`: `STATE Sxx[+S13] q=<id>`, `QUESTION`, `GUESS`, `SPOT`, `DRAG start|end`, `TAP floor`,
`REVEAL ... r= stars= verdict= visible= cut=`, `REVEAL levels ready`, `RESULT`, `WRAP`, `INSIDE`, `ROOM scan|virtual`,
`PLANES`, `SOUND`, `MUTE`, `FOLLOW`, `CMD`.

Screenshot list (design `stateModel.qaCombos`): S01; S02; S03 (co2-1kg tray); S03 (`guess --ef size 0.8`); S03 kg-water
(`q --es id kg-water`); S04 (`ray --es v "0.25,1.3,-0.2,0.15,-0.55,-0.82"`); S05; S06; S07; S08 co2-1kg; S08 kg-gold;
S09 co2-flight; S09 kg-hydrogen (needs a ceiling under 2.82 m: `room --ef h 2.6` first); S10 co2-1t; S10 big-co2-capita;
S11 (`best --es v seed` first); S12; S14; S03 / S09 / S11 / S14 with `lang --es id en` (= +S13). The station's set is in
`../spatial-app-factory/apps/feel-number/build/shots/`. Emulator HD (2560) frames; console screenshots two in a row.

Real input on the emulator (Spatial Ruler harness, `../spatial-ruler/tools/harness/emu.sh` with
`DEV=emulator-5568 AVD=PICO_6_1_Factory5 PKG=io.embodit.feelnumber TAG=FeelNumber`; framebuffer = 2560-frame pixels
x 0.84375): `emu.sh fast on`, then `click X Y -HoldMs 160` on the floor (TAP floor), `drag X Y X Y-346` on the handle
(S05 -> GUESS size), `drag` on the body (S06). The emulator reports one controller online once host input starts,
so the S04 ghost follows that controller.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug testDebugUnitTest
tools/harness/deploy.sh          # install -r on emulator-5568, cold start, first log lines
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be empty.
Unit tests: `app/src/test/.../FeelDomainTest.kt` and `FeelPlacementStateTest.kt` (45 in v0.1.6; the domain set: bank sizes, stars / verdict boundaries, QA expectations,
clearance and reveal pushes, wall stop, wrap, virtual room, visible share vs analytic, inside hysteresis, clamped
mesh and rings, slider / handle / stick scaling, wall snap).

## Deviations from the design package (also in the factory's state.json)

- co2-phone: the EPA factor is now 1.24e-5 t CO₂ per charge (was 8.22e-6), so the ball is 0.233 m; fact copy says
  "about a basketball" (N5 check; other six verified numbers kept: petrol 2.31 kg/L = 100 % mineral petrol, flight
  90 kg within ICAO 80 - 100, air 11 m³/day ALA, whale 150 t in 100 - 190, China 2022 8.9 t per person).
- Ball mesh 64 x 32 instead of 96 x 48 (the design's own N3 fallback; 4,096 triangles a side).
- Persistence in SharedPreferences, not DataStore (same keys and behaviour).
- Card 460 x 640 dp instead of 440 x 560 (the result page needs the room; taste B19 roomy layout), still under 720.
- The true body has no second 1.01x shell; its shape reads through thin dark-teal latitude / longitude lines.
- Coloured text (guess blue, true teal, amber) is replaced by labelPrimary text with coloured keys / a dark star
  capsule: the accents measured well under 3:1 on the glass (taste D13).
- Noto Sans SC is bundled whole (17 MB, from Fire Plan); APK 45 MB.

## Not verified

Headset only: real-room WALL / CEILING coverage, stick feel (the emulator's eye mode has no stick; `yaw` / `guess`
cover the results), hand / controller handle feel above 2.2 m, inner surface comfort, the floor plane permission
prompt. Once on the emulator (first run) the app process disappeared while switching to the hydrogen question; it did
not happen again in 30 in/out cycles, three cold starts and a 60 s idle (no FATAL / ANR in logcat).
