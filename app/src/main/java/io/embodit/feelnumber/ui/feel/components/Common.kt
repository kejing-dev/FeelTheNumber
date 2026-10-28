package io.embodit.feelnumber.ui.feel.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.feelnumber.ui.feel.debug.probe
import io.embodit.feelnumber.ui.theme.FeelColors
import java.util.Locale

/** Panel padding and the corner radius of the glass (design: Material.Regular root, 32 dp corners). */
internal val PanelPadding = 16.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp

/**
 * Glass root of a panel: clipped shape, Material.Regular, padding.
 *
 * [refreshKey]: whenever it changes (the panel came into view, its page or rows changed) the content is
 * rebuilt three more times, 1.5 / 4 / 8 s later. On the idle emulator (1 - 2 fps) a pooled glass panel
 * whose rows changed sometimes stays on a half-recorded frame, black below the first rows, until the next
 * content change (seen here on the first run; same finding and fix as Chime Bells).
 */
@Composable
internal fun Glass(width: Dp, height: Dp, corner: Dp = 32.dp, material: Material = Material.Regular, padding: Dp = PanelPadding, refreshKey: Any? = null, content: @Composable ColumnScope.() -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey) {
        for (wait in REBUILD_AFTER_MS) {
            delay(wait)
            generation++
        }
    }
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        key(generation) { content() }
    }
}

private val REBUILD_AFTER_MS = longArrayOf(1500L, 2500L, 4000L)

/** Type roles of the design (`typography`): numbers always tabular. */
internal object FeelType {
    /** Real size on the result page: 28 / 34 at 600, tabular figures. */
    val big: TextStyle
        @Composable get() = PicoTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** The question: 20 / 26 at 600. */
    val question: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    /** Titles, verdict, button text: 16 / 20 at 600. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Hint line, fact, explanations: 14 / 18 at 500. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")

    /** Chips, joystick hint, progress: 12 / 16 at 600. */
    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Source line: 11 / 14 at 500. */
    val small: TextStyle
        @Composable get() = PicoTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}

internal fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

internal fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)

/** Metres for display: 3 decimals under 0.1 m (gold is 0.037 m), 2 under 10 m, 1 above. */
internal fun metres(v: Float): String = if (v >= 10f) fmt1(v) else if (v < 0.1f) String.format(Locale.US, "%.3f", v) else fmt2(v)

/** Cubic metres: 3 significant figures. */
internal fun cubic(v: Double): String =
    when {
        v >= 100 -> String.format(Locale.US, "%,.0f", v)
        v >= 1 -> String.format(Locale.US, "%.2f", v)
        v >= 0.001 -> String.format(Locale.US, "%.4f", v)
        else -> String.format(Locale.US, "%.6f", v)
    }

/** A 56 x 48 icon key (more, back, close, delete, pause, stop). */
@Composable
internal fun Key(name: String, iconRes: Int, description: String, enabled: Boolean = true, width: Dp = KeyWidth, onClick: () -> Unit) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillPrimary, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
        size = IconButtonDefaults.iconButtonSize(width = width, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/**
 * A worded button: capsule, 48 dp high. [primary] = white fill with ink text (the confirming action),
 * [danger] = error fill with ink text; otherwise the tertiary fill with glass text.
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 104.dp,
    iconRes: Int? = null,
) {
    val container =
        when {
            danger -> PicoTheme.colorScheme.error
            primary -> PicoTheme.colorScheme.labelPrimaryLight
            else -> PicoTheme.colorScheme.fillTertiary
        }
    val content = if (primary || danger) FeelColors.Ink else if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = FeelType.title)
    }
}

/** Title row of a panel: an optional leading key, the title, an optional trailing key. */
@Composable
internal fun TitleRow(title: String, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) leading()
        Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = FeelType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}
