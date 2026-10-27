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

object Steam {
    val Leather = Color(0xFF1C130C)
    val Wood = Color(0xFF2A1D13)
    val WoodLight = Color(0xFF3B2A1B)
    val Brass = Color(0xFFC9A04A)
    val Copper = Color(0xFFB87333)
    val CopperDark = Color(0xFF5A3A1A)
    val Parchment = Color(0xFFEADBB5)
    val ParchmentDim = Color(0xFFBFA878)
    val Verdigris = Color(0xFF4F8A7B)
    val Rust = Color(0xFFA6452A)
    val Backdrop = Brush.verticalGradient(listOf(Color(0xFF33231A), Leather))
}

private val SteamColors = darkColorScheme(
    primary = Steam.Brass,
    onPrimary = Steam.Leather,
    primaryContainer = Steam.CopperDark,
    onPrimaryContainer = Steam.Parchment,
    secondary = Steam.Verdigris,
    onSecondary = Steam.Leather,
    background = Steam.Leather,
    onBackground = Steam.Parchment,
    surface = Steam.Wood,
    onSurface = Steam.Parchment,
    surfaceVariant = Steam.WoodLight,
    onSurfaceVariant = Steam.Parchment,
    outline = Steam.Brass,
    error = Steam.Rust
)

private fun Typography.withFont(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f)
)

@Composable
fun SteampunkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SteamColors,
        typography = Typography().withFont(FontFamily.Serif),
        content = content
    )
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
