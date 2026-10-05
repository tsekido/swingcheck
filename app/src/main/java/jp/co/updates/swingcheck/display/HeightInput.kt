package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.core.Units
import kotlin.math.floor
import kotlin.math.roundToInt

/** 身長の入力文字列の解釈結果。 */
sealed interface HeightParse {
    /** 未入力（身長なし）。 */
    data object Empty : HeightParse

    data class Valid(val cm: Float) : HeightParse

    /** 数字として読めない、または現実的な範囲外。 */
    data object Invalid : HeightParse
}

/** 身長の入力（cm、またはフィート・インチ）の解釈と、表示用の文字列。内部では常に cm で持つ。 */
object HeightInput {
    const val MIN_CM = 50.0
    const val MAX_CM = 250.0

    private val NUMBER = Regex("^(\\d+([.,]\\d*)?|[.,]\\d+)$")

    private fun number(text: String): Double? {
        val t = text.trim()
        if (!NUMBER.matches(t)) return null
        return t.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun validate(cm: Double): HeightParse =
        if (cm in MIN_CM..MAX_CM) HeightParse.Valid(cm.toFloat()) else HeightParse.Invalid

    fun parseCm(text: String): HeightParse {
        if (text.isBlank()) return HeightParse.Empty
        return number(text)?.let(::validate) ?: HeightParse.Invalid
    }

    /** どちらか片方だけの入力も許す（空欄は 0 とみなす）。両方空なら [HeightParse.Empty]。 */
    fun parseFeetInches(feet: String, inches: String): HeightParse {
        if (feet.isBlank() && inches.isBlank()) return HeightParse.Empty
        val f = if (feet.isBlank()) 0.0 else number(feet) ?: return HeightParse.Invalid
        val i = if (inches.isBlank()) 0.0 else number(inches) ?: return HeightParse.Invalid
        return validate(Units.inchToCm(f * Units.INCHES_PER_FOOT + i))
    }

    /** cm の入力欄の初期文字列。小数 1 桁（末尾の .0 は省く）。 */
    fun cmText(cm: Float?): String = cm?.let { trimmed(it.toDouble(), 1) }.orEmpty()

    /** フィート・インチの入力欄の初期文字列。インチは小数 1 桁で、12 に繰り上がるときはフィートに足す。 */
    fun feetInchesText(cm: Float?): Pair<String, String> {
        if (cm == null) return "" to ""
        val totalTenths = (Units.cmToInch(cm.toDouble()) * 10).roundToInt()
        val feet = floor(totalTenths / 10.0 / Units.INCHES_PER_FOOT).toInt()
        val inchTenths = totalTenths - feet * Units.INCHES_PER_FOOT * 10
        return feet.toString() to trimmed(inchTenths / 10.0, 1)
    }

    private fun trimmed(v: Double, decimals: Int): String {
        val s = String.format(java.util.Locale.ROOT, "%.${decimals}f", v)
        return if (decimals > 0 && s.endsWith("." + "0".repeat(decimals))) s.substringBefore('.') else s
    }
}
