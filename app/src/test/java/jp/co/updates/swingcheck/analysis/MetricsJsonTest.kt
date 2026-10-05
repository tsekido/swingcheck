package jp.co.updates.swingcheck.analysis

import jp.co.updates.swingcheck.core.Metric
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsJsonTest {
    @Test
    fun roundTrip() {
        val values = Metric.entries.mapIndexed { i, m -> m to (i * 1.5 - 3.25 + 1e-7) }.toMap()
        val decoded = MetricsJson.decode(MetricsJson.encode(values, 187.5))
        assertEquals(187.5, decoded.referenceLengthPx)
        assertEquals(values, decoded.values)
    }

    @Test
    fun extremeValuesRoundTrip() {
        val values = mapOf(Metric.HEAD_SWAY to 1.0e-12, Metric.HEAD_LIFT to -2.5e9, Metric.SPINE_TILT to 0.0)
        val decoded = MetricsJson.decode(MetricsJson.encode(values, 100.0))
        assertEquals(values, decoded.values)
    }

    @Test
    fun usesMetricNamesAsKeys() {
        val json = MetricsJson.encode(mapOf(Metric.HEAD_SWAY to 12.5), 200.0)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"HEAD_SWAY\":12.5"))
        assertTrue(json.contains("\"referenceLengthPx\":200.0"))
    }

    @Test
    fun nonFiniteValuesAreWrittenAsNullAndDropped() {
        val json = MetricsJson.encode(mapOf(Metric.HEAD_SWAY to Double.NaN, Metric.HEAD_LIFT to 1.0), Double.POSITIVE_INFINITY)
        assertTrue(json.contains("\"HEAD_SWAY\":null"))
        val decoded = MetricsJson.decode(json)
        assertFalse(decoded.values.containsKey(Metric.HEAD_SWAY))
        assertEquals(1.0, decoded.values[Metric.HEAD_LIFT])
        assertTrue(decoded.referenceLengthPx.isNaN())
    }

    @Test
    fun unknownKeysAreIgnored() {
        val decoded = MetricsJson.decode("""{ "FUTURE_METRIC": 3.0, "HEAD_SWAY": -4.5, "referenceLengthPx": 90 }""")
        assertEquals(mapOf(Metric.HEAD_SWAY to -4.5), decoded.values)
        assertEquals(90.0, decoded.referenceLengthPx)
    }

    @Test
    fun rejectsNonObject() {
        assertThrows(IllegalArgumentException::class.java) { MetricsJson.decode("[1,2]") }
        assertThrows(IllegalArgumentException::class.java) { MetricsJson.decode("") }
    }
}
