package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.data.PositionMarkEntity

/** Pボタンの見た目の種類。手で直したもの、自動で推定できなかったもの、普通の自動。 */
enum class PhaseButtonKind { AUTO, MANUAL, UNCERTAIN }

object PhaseButtonState {
    /** 手で直していれば MANUAL（直したら「推定できなかった」印は意味を失う）。 */
    fun kindOf(mark: PositionMarkEntity): PhaseButtonKind = when {
        mark.manualFrame != null -> PhaseButtonKind.MANUAL
        mark.autoUncertain -> PhaseButtonKind.UNCERTAIN
        else -> PhaseButtonKind.AUTO
    }

    /** いま表示しているコマに当たるP（1〜8）。複数あれば小さいほう。なければ null。 */
    fun phaseAtFrame(marks: List<PositionMarkEntity>, frame: Int): Int? =
        marks.firstOrNull { it.frame == frame }?.position
}
