package app.zsync.ptp

import android.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

data class ShotInfo(
    val aperture: String?,
    val lens: String?,
    val exif: String?,
)

fun readShot(bytes: ByteArray): ShotInfo {
    if (bytes.isEmpty()) return ShotInfo(null, null, null)
    return try {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            .takeIf { it > 0 }
            ?.let { "f/${trimNumber(it)}" }
        val lens = exif.getAttribute("LensModel")?.takeIf { it.isNotBlank() }
            ?: exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                .takeIf { it > 0 }
                ?.let { "${trimNumber(it)} mm" }
        val taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { it.isNotBlank() }
        val shutter = formatShutter(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        val iso = exif.getAttribute("PhotographicSensitivity")?.takeIf { it.isNotBlank() && it != "0" }
            ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.takeIf { it.isNotBlank() && it != "0" }
        val line = listOfNotNull(taken, shutter, iso?.let { "ISO $it" }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ShotInfo(aperture, lens, line)
    } catch (ignored: Exception) {
        ShotInfo(null, null, null)
    }
}

private fun formatShutter(raw: String?): String? {
    val value = raw?.toDoubleOrNull() ?: return raw?.takeIf { it.isNotBlank() }
    if (value <= 0) return null
    if (value >= 1) return "${trimNumber(value)} 秒"
    return "1/${(1.0 / value).roundToInt()}"
}

private fun trimNumber(value: Double): String {
    val whole = value.toInt()
    return if (value == whole.toDouble()) whole.toString() else String.format(java.util.Locale.US, "%.1f", value)
}
