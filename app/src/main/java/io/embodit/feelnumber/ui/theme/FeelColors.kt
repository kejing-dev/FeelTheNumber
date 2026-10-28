package io.embodit.feelnumber.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Five `ColorScheme` roles are fixed here and injected into `PicoTheme` by [FeelTheme]; the other
 * eleven inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials and the side view.
 */
object FeelColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error
    val Alert = Color(0xFFFFB23F) // design-style: fixed-figma-color alert: cut by the room, you are inside
    val Passable = Color(0xFF7CF2A3) // design-style: fixed-figma-color passable
    val Interaction = Color(0xFF8FD3FF) // design-style: fixed-figma-color interaction: floor mark, ghost, slider progress

    // ---- brand tokens (design `palette.brandTokens`)
    val GuessBlue = Color(0xFF8FD3FF) // design-style: fixed-figma-color guessBlue: your guess
    val TrueTeal = Color(0xFF2FC6B5) // design-style: fixed-figma-color trueTeal: the real size
    val TrueTealDeep = Color(0xFF16786D) // design-style: fixed-figma-color trueTealDeep: shape lines on the true body
    val CutAmber = Color(0xFFFFB23F) // design-style: fixed-figma-color cutAmber: cut rings
    val HandleWhite = Color(0xFFF5F8FA) // design-style: fixed-figma-color handleWhite: handle, tray, room frame
    val StarGold = Color(0xFFFFD24A) // design-style: fixed-figma-color starGold: stars
    val Ink = Color(0xFF12161B) // design-style: fixed-figma-color ink: text on light fills (primary button, selected segment)
}
