package jp.co.updates.swingcheck.display

import jp.co.updates.swingcheck.core.Metric
import jp.co.updates.swingcheck.settings.LengthUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class MetricRowsTest {
    private val values = mapOf(
        Metric.SPINE_TILT to 5.0,
        Metric.HEAD_SWAY to 20.0, // S = 200px の 10%
        Metric.PELVIS_SWAY to -50.0,
    )

    @Test
    fun lengthShowsPercentAndCm() {
        val rows = MetricRows.build(values, 200.0, 170f, LengthUnit.CM)
        // Metric の定義順
        assertEquals(listOf(Metric.HEAD_SWAY, Metric.PELVIS_SWAY, Metric.SPINE_TILT), rows.map { it.metric })
        val head = rows[0]
        assertEquals(10.0, head.percentOfShoulderWidth!!, 1e-9)
        // 170cm × 0.259 = 44.03cm が 200px → 20px = 4.403cm
        assertEquals(4.403, head.length!!, 1e-6)
        assertEquals(-25.0, rows[1].percentOfShoulderWidth!!, 1e-9)
    }

    @Test
    fun inchUnitConvertsFromCm() {
        val head = MetricRows.build(values, 200.0, 170f, LengthUnit.INCH)[0]
        assertEquals(4.403 / 2.54, head.length!!, 1e-6)
    }

    @Test
    fun noHeightMeansPercentOnly() {
        val head = MetricRows.build(values, 200.0, null, LengthUnit.CM)[0]
        assertEquals(10.0, head.percentOfShoulderWidth!!, 1e-9)
        assertNull(head.length)
    }

    @Test
    fun angleHasNoLengthFields() {
        val spine = MetricRows.build(values, 200.0, 170f, LengthUnit.CM).single { it.metric == Metric.SPINE_TILT }
        assertEquals(5.0, spine.raw, 0.0)
        assertNull(spine.percentOfShoulderWidth)
        assertNull(spine.length)
    }

    @Test
    fun missingReferenceOmitsLengthConversions() {
        val rows = MetricRows.build(values, Double.NaN, 170f, LengthUnit.CM)
        assertNull(rows[0].percentOfShoulderWidth)
        assertNull(rows[0].length)
        assertEquals(5.0, rows.last().raw, 0.0) // 角度は出る
    }

    @Test
    fun nonFiniteValuesAreDropped() {
        val rows = MetricRows.build(mapOf(Metric.HEAD_SWAY to Double.NaN, Metric.SPINE_TILT to 1.0), 200.0, null, LengthUnit.CM)
        assertEquals(listOf(Metric.SPINE_TILT), rows.map { it.metric })
    }

    @Test
    fun numberText() {
        assertEquals("+12.3", NumberText.format(12.34, 1, Locale.US, signed = true))
        assertEquals("-12.3", NumberText.format(-12.34, 1, Locale.US, signed = true))
        assertEquals("12.3", NumberText.format(12.34, 1, Locale.US))
        assertEquals("0.0", NumberText.format(-0.04, 1, Locale.US, signed = true))
        assertEquals("0", NumberText.format(0.2, 0, Locale.US, signed = true))
        assertEquals("12,3", NumberText.format(12.34, 1, Locale.GERMANY))
    }
}
