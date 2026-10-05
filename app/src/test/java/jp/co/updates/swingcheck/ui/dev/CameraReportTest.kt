package jp.co.updates.swingcheck.ui.dev

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraReportTest {
    @Test
    fun textListsHighSpeedSizesAndRanges() {
        val report = DeviceReport(
            "ASUS ASUS_AI2202", "Android 15 (API 35)",
            listOf(
                CameraReport(
                    id = "0", facing = "BACK", hardwareLevel = "LEVEL_3",
                    capabilities = listOf("BACKWARD_COMPATIBLE", "CONSTRAINED_HIGH_SPEED_VIDEO"),
                    supportsConstrainedHighSpeed = true,
                    highSpeedSizes = listOf(HighSpeedSize(1920, 1080, listOf(120 to 120, 240 to 240))),
                    aeFpsRanges = listOf(15 to 30, 30 to 30),
                    physicalIds = emptyList(),
                ),
                CameraReport("1", "FRONT", "FULL", emptyList(), false, emptyList(), emptyList(), listOf("2", "3")),
            ),
        )
        val t = report.toText()
        assertTrue(t.contains("device: ASUS ASUS_AI2202"), t)
        assertTrue(t.contains("camera 0 (BACK)"), t)
        assertTrue(t.contains("CONSTRAINED_HIGH_SPEED_VIDEO: yes"), t)
        assertTrue(t.contains("1920x1080: [120, 120], [240, 240]"), t)
        assertTrue(t.contains("[15, 30], [30, 30]"), t)
        assertTrue(t.contains("camera 1 (FRONT)"), t)
        assertTrue(t.contains("CONSTRAINED_HIGH_SPEED_VIDEO: no"), t)
        assertTrue(t.contains("high speed video sizes: (none)"), t)
        assertTrue(t.contains("physical camera ids: 2, 3"), t)
    }
}
