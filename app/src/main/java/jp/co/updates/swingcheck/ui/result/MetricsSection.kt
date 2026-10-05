package jp.co.updates.swingcheck.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.core.Metric
import jp.co.updates.swingcheck.core.MetricKind
import jp.co.updates.swingcheck.display.FrameMetrics
import jp.co.updates.swingcheck.display.MetricRow
import jp.co.updates.swingcheck.display.MetricRows
import jp.co.updates.swingcheck.display.NumberText
import jp.co.updates.swingcheck.settings.LengthUnit

/** 表示中のコマの数値の一覧。Pのコマなら保存してある値、それ以外は計算した値。 */
@Composable
fun MetricsSection(
    frame: Int,
    metrics: FrameMetrics?,
    heightCm: Float?,
    unit: LengthUnit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (metrics?.phase != null) stringResource(R.string.result_metrics_phase, metrics.phase)
            else stringResource(R.string.result_metrics_frame, frame + 1),
            style = MaterialTheme.typography.titleMedium,
        )
        if (metrics == null) {
            Text(stringResource(R.string.result_metrics_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val rows = MetricRows.build(metrics.values, metrics.referenceLengthPx, heightCm, unit)
        rows.forEach { row ->
            MetricRowView(row)
            HorizontalDivider()
        }
        Text(
            stringResource(R.string.result_metrics_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MetricRowView(row: MetricRow) {
    val locale = LocalConfiguration.current.locales[0]
    // 屈曲角は 0 以上なので符号は付けない。そのほかは目標方向・上・リード側が高いなどが正
    val signed = row.metric !in UNSIGNED
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(stringResource(labelOf(row.metric)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Column(horizontalAlignment = Alignment.End) {
            when (row.metric.kind) {
                MetricKind.ANGLE -> Text(
                    stringResource(R.string.metric_degrees, NumberText.format(row.raw, 1, locale, signed)),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.End,
                )
                MetricKind.LENGTH -> {
                    row.percentOfShoulderWidth?.let {
                        Text(
                            stringResource(R.string.metric_percent, NumberText.format(it, 0, locale, signed)),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.End,
                        )
                    }
                    row.length?.let {
                        val text = NumberText.format(it, 1, locale, signed)
                        Text(
                            stringResource(
                                if (row.unit == LengthUnit.CM) R.string.metric_length_cm else R.string.metric_length_inch,
                                text,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

private val UNSIGNED = setOf(
    Metric.KNEE_FLEX_LEFT, Metric.KNEE_FLEX_RIGHT, Metric.ELBOW_FLEX_LEFT, Metric.ELBOW_FLEX_RIGHT,
)

private fun labelOf(metric: Metric): Int = when (metric) {
    Metric.HEAD_SWAY -> R.string.metric_head_sway
    Metric.HEAD_LIFT -> R.string.metric_head_lift
    Metric.SHOULDER_TILT -> R.string.metric_shoulder_tilt
    Metric.PELVIS_SIDE_BEND -> R.string.metric_pelvis_side_bend
    Metric.PELVIS_SWAY -> R.string.metric_pelvis_sway
    Metric.SPINE_TILT -> R.string.metric_spine_tilt
    Metric.KNEE_FLEX_LEFT -> R.string.metric_knee_flex_left
    Metric.KNEE_FLEX_RIGHT -> R.string.metric_knee_flex_right
    Metric.ELBOW_FLEX_LEFT -> R.string.metric_elbow_flex_left
    Metric.ELBOW_FLEX_RIGHT -> R.string.metric_elbow_flex_right
}
