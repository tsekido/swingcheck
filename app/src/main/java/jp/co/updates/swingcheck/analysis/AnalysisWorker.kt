package jp.co.updates.swingcheck.analysis

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import jp.co.updates.swingcheck.SwingcheckApp

/** 1 つの Swing を解析する。解析の失敗は Swing の状態（FAILED）に残すので、ワーカー自体は成功で終える。 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val swingId = inputData.getLong(KEY_SWING_ID, -1L)
        if (swingId < 0) return Result.failure()
        (applicationContext as SwingcheckApp).container.analysisPipeline.run(swingId)
        return Result.success()
    }

    companion object {
        const val KEY_SWING_ID = "swingId"
        const val UNIQUE_WORK_NAME = "swing-analysis"
    }
}

/** 解析の順番待ち。1 件ずつ、入れた順に処理する。 */
class AnalysisScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(swingId: Long) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(AnalysisWorker.KEY_SWING_ID to swingId))
            .build()
        // APPEND_OR_REPLACE：前の 1 件が失敗・中止されても、あとの分は続けて処理する
        workManager.enqueueUniqueWork(AnalysisWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
