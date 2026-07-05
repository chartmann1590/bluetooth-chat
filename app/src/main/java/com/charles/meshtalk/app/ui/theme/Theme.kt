package com.charles.meshtalk.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Matches the "MeshTalk Radio Dark" design system created in Stitch:
// deep near-black background, signal-green accent, rounded-12dp shapes.
val SignalGreen = Color(0xFF3DDC97)
val MeshBackground = Color(0xFF0A0A0A)
val MeshSurface = Color(0xFF161616)
val MeshOnSurfaceMuted = Color(0xFF9A9A9A)

private val MeshColorScheme = darkColorScheme(
    primary = SignalGreen,
    onPrimary = Color.Black,
    background = MeshBackground,
    onBackground = Color(0xFFE6E6E6),
    surface = MeshSurface,
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = MeshSurface,
    onSurfaceVariant = MeshOnSurfaceMuted,
    secondary = SignalGreen,
)

private val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
)

@Composable
fun MeshTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshColorScheme,
        shapes = MeshShapes,
        content = content
    )
}
