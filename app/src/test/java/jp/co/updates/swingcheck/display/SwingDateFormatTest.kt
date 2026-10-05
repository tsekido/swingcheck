package jp.co.updates.swingcheck.display

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

class SwingDateFormatTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val at = ZonedDateTime.of(2026, 10, 5, 14, 30, 0, 0, ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()

    @Test
    fun japaneseOrderIsYearMonthDay() {
        val s = SwingDateFormat(Locale.JAPAN, tokyo).format(at)
        assertTrue(s.contains("2026/10/05") || s.contains("2026/10/5"), s)
        assertTrue(s.contains("14:30"), s)
    }

    @Test
    fun usEnglishUsesMonthName() {
        val s = SwingDateFormat(Locale.US, tokyo).format(at)
        assertTrue(s.contains("Oct 5, 2026"), s)
        assertTrue(s.contains("2:30"), s)
    }

    @Test
    fun timeZoneIsApplied() {
        val s = SwingDateFormat(Locale.JAPAN, TimeZone.getTimeZone("UTC")).format(at)
        assertTrue(s.contains("5:30"), s)
    }
}
