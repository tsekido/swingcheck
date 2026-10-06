package jp.co.updates.swingcheck.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** スキーマ 1 → 2（source と timeScale の追加）。app/schemas/ の JSON を androidTest の assets として使う。 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2KeepsExistingSwingsAsCaptured() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO swing (id, createdAt, videoPath, fps, frameCount, width, height, cameraView, handedness, " +
                    "heightCm, memo, analysisStatus, ballResult, analysisVersion) " +
                    "VALUES (1, 1000, 'videos/a.mp4', 240.0, 100, 1080, 1920, 'FACE_ON', 'RIGHT', NULL, '', 'DONE', 'HIT', 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT source, timeScale, fps FROM swing WHERE id = 1").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("CAPTURED", c.getString(0))
            assertEquals(1.0, c.getDouble(1), 0.0)
            assertEquals(240.0, c.getDouble(2), 0.0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
