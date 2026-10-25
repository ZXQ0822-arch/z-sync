package app.zsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zsync.ui.theme.CancelFill
import app.zsync.ui.theme.Danger
import app.zsync.ui.theme.Line
import app.zsync.ui.theme.OnDanger

/**
 * The app-wide buttons. Every screen uses these so a primary, secondary and destructive action look
 * the same wherever they appear. Shapes follow the design language: connect pages may use the pill
 * (half the height), every other page uses the 14 dp corner.
 */
private val StoneFill = Color(0xFFF7F4EE)
private val StoneFillInk = Color(0xFF1A1816)

private fun buttonShape(pill: Boolean): Shape =
    if (pill) RoundedCornerShape(50) else RoundedCornerShape(14.dp)

/** Primary action: Stone fill, dark label. One per screen. */
@Composable
fun StoneButton(
    label: String,
    enabled: Boolean = true,
    arrow: Boolean = false,
    pill: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = buttonShape(pill),
        colors = ButtonDefaults.buttonColors(
            containerColor = StoneFill,
            contentColor = StoneFillInk,
            disabledContainerColor = StoneFill.copy(alpha = 0.4f),
            disabledContentColor = StoneFillInk.copy(alpha = 0.5f),
        ),
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        if (arrow) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
    }
}

/** Secondary action: a filled dark-grey button. Cancel and back live here. */
@Composable
fun CancelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pill: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (enabled) CancelFill else CancelFill.copy(alpha = 0.4f), buttonShape(pill))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
    }
}

/** Destructive action: a solid red button with white text, never Stone, so delete cannot read as primary. */
@Composable
fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pill: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (enabled) Danger else Danger.copy(alpha = 0.4f), buttonShape(pill))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) OnDanger else OnDanger.copy(alpha = 0.6f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

/** A hairline divider-coloured surface used by the toast and the confirm dialog. */
internal val DialogCardShape: Shape = RoundedCornerShape(22.dp)
internal val DialogCardColor: Color = Color(0xFF48484C)
internal val DialogScrimColor: Color = Color(0x6B141416)
internal val HairlineShape: Shape = RoundedCornerShape(14.dp)
internal val HairlineColor: Color = Line
