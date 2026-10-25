package app.zsync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFFF4F1EA)
val Muted = Color(0xFFB0AAA2)
val Page = Color(0xFF1C1C1E)
val Phone = Color(0xFF3A3A3E)
val Card = Color(0xFF48484C)
val CardSelected = Color(0xFF56565B)
val Stone = Color(0xFFE6DFD4)
val StoneInk = Color(0xFF2C2926)
val Line = Color(0x59F4F1EA)
val Danger = Color(0xFFFF453A)
val OnDanger = Color.White
val CancelFill = Color(0xFF343436)

private val scheme = darkColorScheme(
    primary = Stone,
    onPrimary = StoneInk,
    background = Phone,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    secondary = Muted,
)

@Composable
fun ZSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
