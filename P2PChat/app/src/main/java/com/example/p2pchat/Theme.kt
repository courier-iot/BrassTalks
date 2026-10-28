package com.example.p2pchat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private class Palette(
    val leather: Color, val wood: Color, val woodLight: Color, val brass: Color, val copper: Color,
    val copperDark: Color, val parchment: Color, val parchmentDim: Color, val verdigris: Color,
    val rust: Color, val backdropTop: Color
)

private val DARK = Palette(
    leather = Color(0xFF1C130C), wood = Color(0xFF2A1D13), woodLight = Color(0xFF3B2A1B),
    brass = Color(0xFFC9A04A), copper = Color(0xFFB87333), copperDark = Color(0xFF5A3A1A),
    parchment = Color(0xFFEADBB5), parchmentDim = Color(0xFFBFA878), verdigris = Color(0xFF4F8A7B),
    rust = Color(0xFFA6452A), backdropTop = Color(0xFF33231A)
)

/** Light "parchment" look: aged paper, ink-brown text, darker brass so it stays readable. */
private val LIGHT = Palette(
    leather = Color(0xFFF3E7C9), wood = Color(0xFFE6D3A8), woodLight = Color(0xFFEFE0BC),
    brass = Color(0xFF7E5A12), copper = Color(0xFF8E4F1C), copperDark = Color(0xFFDDBF8E),
    parchment = Color(0xFF2B1D12), parchmentDim = Color(0xFF6B5235), verdigris = Color(0xFF2F6B5E),
    rust = Color(0xFF9C3B22), backdropTop = Color(0xFFF8EED8)
)

/**
 * The app's colours. They are Compose state, so switching between brass (dark) and parchment (light)
 * recolours every screen instantly without restarting.
 */
object Steam {
    var Leather by mutableStateOf(DARK.leather); private set
    var Wood by mutableStateOf(DARK.wood); private set
    var WoodLight by mutableStateOf(DARK.woodLight); private set
    var Brass by mutableStateOf(DARK.brass); private set
    var Copper by mutableStateOf(DARK.copper); private set
    var CopperDark by mutableStateOf(DARK.copperDark); private set
    var Parchment by mutableStateOf(DARK.parchment); private set
    var ParchmentDim by mutableStateOf(DARK.parchmentDim); private set
    var Verdigris by mutableStateOf(DARK.verdigris); private set
    var Rust by mutableStateOf(DARK.rust); private set
    private var BackdropTop by mutableStateOf(DARK.backdropTop)
    var isDark by mutableStateOf(true); private set

    val Backdrop: Brush get() = Brush.verticalGradient(listOf(BackdropTop, Leather))

    fun apply(dark: Boolean) {
        if (dark == isDark && Leather == (if (dark) DARK else LIGHT).leather) return
        val p = if (dark) DARK else LIGHT
        Leather = p.leather; Wood = p.wood; WoodLight = p.woodLight; Brass = p.brass; Copper = p.copper
        CopperDark = p.copperDark; Parchment = p.parchment; ParchmentDim = p.parchmentDim
        Verdigris = p.verdigris; Rust = p.rust; BackdropTop = p.backdropTop; isDark = dark
    }
}

/** Cinzel Decorative (SIL Open Font License), fetched into res/font by the GitHub build. Used for headings. */
val Victorian = FontFamily(
    Font(R.font.cinzel_decorative, FontWeight.Normal),
    Font(R.font.cinzel_decorative_bold, FontWeight.Bold)
)

private fun Typography.steamFonts(): Typography {
    val body = FontFamily.Serif
    return copy(
        displayLarge = displayLarge.copy(fontFamily = Victorian),
        displayMedium = displayMedium.copy(fontFamily = Victorian),
        displaySmall = displaySmall.copy(fontFamily = Victorian),
        headlineLarge = headlineLarge.copy(fontFamily = Victorian),
        headlineMedium = headlineMedium.copy(fontFamily = Victorian),
        headlineSmall = headlineSmall.copy(fontFamily = Victorian),
        titleLarge = titleLarge.copy(fontFamily = Victorian),
        titleMedium = titleMedium.copy(fontFamily = body),
        titleSmall = titleSmall.copy(fontFamily = body),
        bodyLarge = bodyLarge.copy(fontFamily = body),
        bodyMedium = bodyMedium.copy(fontFamily = body),
        bodySmall = bodySmall.copy(fontFamily = body),
        labelLarge = labelLarge.copy(fontFamily = body),
        labelMedium = labelMedium.copy(fontFamily = body),
        labelSmall = labelSmall.copy(fontFamily = body)
    )
}

@Composable
fun SteampunkTheme(content: @Composable () -> Unit) {
    val scheme = if (Steam.isDark) darkColorScheme(
        primary = Steam.Brass, onPrimary = Steam.Leather,
        primaryContainer = Steam.CopperDark, onPrimaryContainer = Steam.Parchment,
        secondary = Steam.Verdigris, onSecondary = Steam.Leather,
        background = Steam.Leather, onBackground = Steam.Parchment,
        surface = Steam.Wood, onSurface = Steam.Parchment,
        surfaceVariant = Steam.WoodLight, onSurfaceVariant = Steam.Parchment,
        outline = Steam.Brass, error = Steam.Rust
    ) else lightColorScheme(
        primary = Steam.Brass, onPrimary = Steam.Leather,
        primaryContainer = Steam.CopperDark, onPrimaryContainer = Steam.Parchment,
        secondary = Steam.Verdigris, onSecondary = Steam.Leather,
        background = Steam.Leather, onBackground = Steam.Parchment,
        surface = Steam.Wood, onSurface = Steam.Parchment,
        surfaceVariant = Steam.WoodLight, onSurfaceVariant = Steam.Parchment,
        outline = Steam.Brass, error = Steam.Rust
    )
    MaterialTheme(colorScheme = scheme, typography = remember { Typography().steamFonts() }, content = content)
}

@Composable
fun steamFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Steam.Parchment,
    unfocusedTextColor = Steam.Parchment,
    focusedBorderColor = Steam.Brass,
    unfocusedBorderColor = Steam.Copper,
    focusedLabelColor = Steam.Brass,
    unfocusedLabelColor = Steam.ParchmentDim,
    cursorColor = Steam.Brass,
    focusedPlaceholderColor = Steam.ParchmentDim,
    unfocusedPlaceholderColor = Steam.ParchmentDim
)

/** A brass gear drawn with Canvas. Set spin = true for a slow rotation. */
@Composable
fun Gear(dimension: Dp, color: Color, modifier: Modifier = Modifier, spin: Boolean = false) {
    val angle = if (spin) {
        rememberInfiniteTransition(label = "gear").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
            label = "gearAngle"
        ).value
    } else 0f

    Canvas(modifier.size(dimension)) {
        val c = center
        val r = this.size.minDimension / 2f
        rotate(angle, c) {
            val teeth = 10
            repeat(teeth) { i ->
                rotate(i * 360f / teeth, c) {
                    drawRect(color, Offset(c.x - r * 0.12f, c.y - r), Size(r * 0.24f, r * 0.32f))
                }
            }
            drawCircle(color, radius = r * 0.62f, center = c, style = Stroke(width = r * 0.2f))
            repeat(3) { i ->
                rotate(i * 60f, c) {
                    drawRect(color, Offset(c.x - r * 0.05f, c.y - r * 0.65f), Size(r * 0.1f, r * 1.3f))
                }
            }
            drawCircle(color, radius = r * 0.2f, center = c)
        }
    }
}
