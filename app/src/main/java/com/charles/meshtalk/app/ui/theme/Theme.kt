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

// SMS/iMessage-style chat bubble colors: sent bubbles use the signal-green accent with dark
// text (like a "read" iMessage bubble); incoming bubbles are a lighter-than-background charcoal
// so they read as a distinct bubble rather than blending into the screen.
val MeshBubbleMine = SignalGreen
val MeshBubbleMineContent = Color(0xFF06140F)
val MeshBubbleIncoming = Color(0xFF1E1F20)
val MeshBubbleIncomingContent = Color(0xFFECECEC)

/** Rounded on three corners with a tighter "tail" corner on the side nearest the sender — the
 * classic asymmetric-corner trick chat apps use to imply bubble direction without an actual
 * tail graphic. */
fun chatBubbleShape(isMine: Boolean): RoundedCornerShape = if (isMine) {
    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
} else {
    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
}

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
