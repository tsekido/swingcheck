package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.core.Units
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeightInputTest {
    @Test
    fun parseCm() {
        assertEquals(HeightParse.Empty, HeightInput.parseCm(""))
        assertEquals(HeightParse.Empty, HeightInput.parseCm("  "))
        assertEquals(HeightParse.Valid(175f), HeightInput.parseCm("175"))
        assertEquals(HeightParse.Valid(175.5f), HeightInput.parseCm(" 175.5 "))
        assertEquals(HeightParse.Valid(175.5f), HeightInput.parseCm("175,5"))
        assertEquals(HeightParse.Invalid, HeightInput.parseCm("abc"))
        assertEquals(HeightParse.Invalid, HeightInput.parseCm("1e3"))
        assertEquals(HeightParse.Invalid, HeightInput.parseCm("NaN"))
        assertEquals(HeightParse.Invalid, HeightInput.parseCm("-170"))
        assertEquals(HeightParse.Invalid, HeightInput.parseCm("10"))
        assertEquals(HeightParse.Invalid, HeightInput.parseCm("400"))
    }

    @Test
    fun parseFeetInches() {
        assertEquals(HeightParse.Empty, HeightInput.parseFeetInches("", ""))
        val five10 = HeightInput.parseFeetInches("5", "10") as HeightParse.Valid
        assertEquals(177.8, five10.cm.toDouble(), 0.01)
        // フィートだけ・インチだけ
        assertEquals(182.88, (HeightInput.parseFeetInches("6", "") as HeightParse.Valid).cm.toDouble(), 0.01)
        assertEquals(HeightParse.Valid(Units.inchToCm(70.0).toFloat()), HeightInput.parseFeetInches("", "70"))
        assertEquals(HeightParse.Invalid, HeightInput.parseFeetInches("5", "x"))
        assertEquals(HeightParse.Invalid, HeightInput.parseFeetInches("1", "0"))
    }

    @Test
    fun texts() {
        assertEquals("", HeightInput.cmText(null))
        assertEquals("175", HeightInput.cmText(175f))
        assertEquals("175.5", HeightInput.cmText(175.5f))
        assertEquals("5" to "10", HeightInput.feetInchesText(177.8f))
        assertEquals("" to "", HeightInput.feetInchesText(null))
    }

    @Test
    fun feetInchesRoundsUpIntoNextFoot() {
        // 182.85cm = 71.99 インチ → 小数 1 桁に丸めて 72.0 インチ = 6 フィート 0 インチ
        assertEquals("6" to "0", HeightInput.feetInchesText(182.85f))
    }

    @Test
    fun roundTripFeetInches() {
        val cm = (HeightInput.parseFeetInches("5", "7.5") as HeightParse.Valid).cm
        assertEquals("5" to "7.5", HeightInput.feetInchesText(cm))
    }
}
