package jp.co.updates.swingcheck.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.co.updates.swingcheck.core.BallResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwingDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SwingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.swingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun swing(createdAt: Long) = SwingEntity(
        createdAt = createdAt, videoPath = "videos/$createdAt.mp4", fps = 240f, frameCount = 100, width = 1080, height = 1920,
    )

    private fun marks(swingId: Long) = (1..8).map {
        PositionMarkEntity(swingId, it, autoFrame = it * 10, manualFrame = null, autoUncertain = false, metricsJson = "{}")
    }

    @Test
    fun listIsNewestFirst() = runBlocking {
        dao.insert(swing(100))
        dao.insert(swing(300))
        dao.insert(swing(200))
        assertEquals(listOf(300L, 200L, 100L), dao.observeAll().first().map { it.createdAt })
    }

    @Test
    fun saveAnalysisResultReplacesMarksAndSetsStatus() = runBlocking {
        val id = dao.insert(swing(1))
        dao.saveAnalysisResult(id, marks(id), BallResult.HIT, AnalysisVersion.CURRENT)
        dao.saveAnalysisResult(id, marks(id), BallResult.UNKNOWN, AnalysisVersion.CURRENT)
        val s = dao.get(id)!!
        assertEquals(AnalysisStatus.DONE, s.analysisStatus)
        assertEquals(BallResult.UNKNOWN, s.ballResult)
        assertEquals(8, dao.getMarks(id).size)
    }

    @Test
    fun deletingSwingCascadesToMarks() = runBlocking {
        val id = dao.insert(swing(1))
        dao.saveAnalysisResult(id, marks(id), null, AnalysisVersion.CURRENT)
        dao.deleteAll(listOf(id))
        assertNull(dao.get(id))
        assertEquals(0, dao.getMarks(id).size)
    }

    @Test
    fun correctionUpdatesManualFrameAndMetrics() = runBlocking {
        val id = dao.insert(swing(1))
        dao.saveAnalysisResult(id, marks(id), null, AnalysisVersion.CURRENT)
        dao.saveCorrection(id, 4, 77, mapOf(4 to "{\"a\":1}", 5 to "{\"b\":2}"))
        val m = dao.getMarks(id)
        assertEquals(77, m[3].manualFrame)
        assertEquals(77, m[3].frame)
        assertEquals("{\"a\":1}", m[3].metricsJson)
        assertNull(m[4].manualFrame)
        assertEquals("{\"b\":2}", m[4].metricsJson)
        dao.saveCorrection(id, 4, null, mapOf(4 to "{}"))
        assertEquals(40, dao.getMarks(id)[3].frame)
    }

    @Test
    fun memoAndHeightUpdate() = runBlocking {
        val id = dao.insert(swing(1))
        dao.updateMemo(id, "テスト")
        dao.updateHeight(id, 172.5f)
        val s = dao.get(id)!!
        assertEquals("テスト", s.memo)
        assertEquals(172.5f, s.heightCm)
    }
}
