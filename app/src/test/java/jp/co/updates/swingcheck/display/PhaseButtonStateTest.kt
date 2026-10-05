package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.data.PositionMarkEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhaseButtonStateTest {
    private fun mark(pos: Int, auto: Int, manual: Int? = null, uncertain: Boolean = false) =
        PositionMarkEntity(1, pos, auto, manual, uncertain, "{}")

    @Test
    fun kinds() {
        assertEquals(PhaseButtonKind.AUTO, PhaseButtonState.kindOf(mark(1, 10)))
        assertEquals(PhaseButtonKind.UNCERTAIN, PhaseButtonState.kindOf(mark(1, 10, uncertain = true)))
        assertEquals(PhaseButtonKind.MANUAL, PhaseButtonState.kindOf(mark(1, 10, manual = 12)))
        // 手で直せば、推定できなかった印より手動の表示を優先する
        assertEquals(PhaseButtonKind.MANUAL, PhaseButtonState.kindOf(mark(1, 10, manual = 12, uncertain = true)))
    }

    @Test
    fun phaseAtFrameUsesManualFrameAndLowestPhase() {
        val marks = listOf(mark(1, 10), mark(2, 20, manual = 25), mark(3, 25), mark(4, 40))
        assertEquals(1, PhaseButtonState.phaseAtFrame(marks, 10))
        assertNull(PhaseButtonState.phaseAtFrame(marks, 20))
        assertEquals(2, PhaseButtonState.phaseAtFrame(marks, 25)) // P2 と P3 が同じコマなら小さいほう
        assertNull(PhaseButtonState.phaseAtFrame(marks, 99))
    }
}
