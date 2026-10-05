package jp.co.updates.swingcheck.display

import java.util.Locale

/** 数値の表示用の文字列。 */
object NumberText {
    /** 小数 [decimals] 桁に丸める。符号つきのとき、正は +、丸めて 0 になる値は符号なし。 */
    fun format(value: Double, decimals: Int, locale: Locale, signed: Boolean = false): String {
        val plain = String.format(locale, "%.${decimals}f", value)
        val isZero = plain.all { !it.isDigit() || it == '0' }
        if (isZero) return String.format(locale, "%.${decimals}f", 0.0)
        return if (signed && value > 0) "+$plain" else plain
    }
}
