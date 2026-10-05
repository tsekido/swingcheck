package jp.co.updates.swingcheck.analysis

import jp.co.updates.swingcheck.core.Metric
import jp.co.updates.swingcheck.core.MetricValues

/** 保存した 1 つのPの数値。値は :core と同じ単位（LENGTH はピクセル、ANGLE は度）。 */
class StoredMetrics(
    val values: MetricValues,
    /** 換算に使った基準の長さ S（P1 でのピクセル単位の肩幅）。比率や cm への換算に使う */
    val referenceLengthPx: Double,
)

/**
 * `position_mark.metricsJson`：項目名（[Metric] の名前）→ 値 の平らな JSON オブジェクト。
 * 基準の長さ S も [KEY_REFERENCE_LENGTH] として同じオブジェクトに入れる（そのPだけで比率や cm に直せるように）。
 * 有限でない値（NaN など）は null として書き、読むときは項目ごと省く。
 * org.json は JVM の単体テストで使えないので、この形だけを扱う小さな読み書きを自前で持つ。
 */
object MetricsJson {
    const val KEY_REFERENCE_LENGTH = "referenceLengthPx"

    private val ENTRY = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(null|[-+0-9.eE]+)")

    fun encode(values: MetricValues, referenceLengthPx: Double): String {
        val entries = Metric.entries.mapNotNull { m -> values[m]?.let { m.name to it } } +
            (KEY_REFERENCE_LENGTH to referenceLengthPx)
        return entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":${if (v.isFinite()) v.toString() else "null"}"
        }
    }

    /** 形が違うとき（壊れている）は [IllegalArgumentException]。基準の長さが無い・null のときは NaN。 */
    fun decode(json: String): StoredMetrics {
        val text = json.trim()
        require(text.startsWith("{") && text.endsWith("}")) { "metricsJson must be a JSON object" }
        val values = HashMap<Metric, Double>()
        var reference = Double.NaN
        for (m in ENTRY.findAll(text)) {
            val key = m.groupValues[1]
            val raw = m.groupValues[2]
            val v = if (raw == "null") null else raw.toDoubleOrNull()
            if (key == KEY_REFERENCE_LENGTH) {
                if (v != null) reference = v
            } else {
                val metric = Metric.entries.firstOrNull { it.name == key } ?: continue // 将来の項目は無視
                if (v != null) values[metric] = v
            }
        }
        return StoredMetrics(values, reference)
    }
}
