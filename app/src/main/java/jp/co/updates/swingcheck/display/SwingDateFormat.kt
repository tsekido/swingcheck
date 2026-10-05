package jp.co.updates.swingcheck.display

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 撮影日時の表示。ロケールに合った書式（日付は中くらい、時刻は分まで）。 */
class SwingDateFormat(locale: Locale, timeZone: TimeZone = TimeZone.getDefault()) {
    private val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply {
        this.timeZone = timeZone
    }

    fun format(epochMillis: Long): String = synchronized(format) { format.format(Date(epochMillis)) }
}
