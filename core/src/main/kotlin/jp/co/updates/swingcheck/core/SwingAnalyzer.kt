package jp.co.updates.swingcheck.core

/** 各Pの数値と、その換算に使った基準の長さ。 */
class SwingMetrics(
    /** 基準の長さ S（P1 でのピクセル単位の肩幅）。 */
    val referenceLengthPx: Double,
    val byPhase: Map<Phase, MetricValues>,
) {
    operator fun get(phase: Phase): MetricValues = byPhase.getValue(phase)
}

/** 解析全体の結果。smoothed は表示（骨格・シャフトの描画）や数値の再計算に使う。 */
class SwingAnalysis(
    val smoothed: PixelSequence,
    val phases: PhaseDetectionResult,
    val metrics: SwingMetrics,
)

/** PoseSequence → 平滑化 → Pの判定 → 各Pの数値。 */
class SwingAnalyzer(
    private val smoothing: SmoothingConfig = SmoothingConfig(),
    private val phaseDetector: PhaseDetector = PhaseDetector(),
    private val handedness: Handedness = Handedness.RIGHT,
) {
    fun analyze(sequence: PoseSequence): SwingAnalysis {
        require(sequence.size > 0) { "empty sequence" }
        val smoothed = Preprocessor.preprocess(sequence, handedness, smoothing)
        val phases = phaseDetector.detect(smoothed)
        return SwingAnalysis(smoothed, phases, computeMetrics(smoothed, phases.frames))
    }

    companion object {
        /**
         * Pのコマ（P1〜P8 の順に 8 個）を指定して、数値を計算し直す。
         * P1 のコマが基準（Sway/Lift の 0、S）になるので、P1 を直したときも全Pが変わる。
         */
        fun computeMetrics(smoothed: PixelSequence, frames: List<Int>): SwingMetrics {
            require(frames.size == Phase.entries.size) { "frames must have 8 items" }
            require(frames.all { it in 0 until smoothed.size }) { "frame out of range" }
            val baseline = smoothed.poses[frames[Phase.P1.ordinal]]
            val s = Derived.shoulderWidth(baseline)
            val byPhase = Phase.entries.associateWith { p ->
                MetricsCalculator.compute(smoothed.poses[frames[p.ordinal]], baseline)
            }
            return SwingMetrics(s, byPhase)
        }

        /** 1 つのPだけ直したあとの数値の計算し直し。frames は現在の P1〜P8 のコマ。 */
        fun recompute(smoothed: PixelSequence, frames: List<Int>, changed: Phase, newFrame: Int): SwingMetrics {
            val updated = frames.toMutableList()
            updated[changed.ordinal] = newFrame
            return computeMetrics(smoothed, updated)
        }
    }
}
