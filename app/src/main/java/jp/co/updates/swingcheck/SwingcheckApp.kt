package jp.co.updates.swingcheck

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import jp.co.updates.swingcheck.analysis.AnalysisPipeline
import jp.co.updates.swingcheck.analysis.AnalysisScheduler
import jp.co.updates.swingcheck.data.AppDatabase
import jp.co.updates.swingcheck.data.SwingFiles
import jp.co.updates.swingcheck.data.SwingRepository
import jp.co.updates.swingcheck.pose.MediaPipePoseEstimator
import jp.co.updates.swingcheck.pose.PoseEstimator
import jp.co.updates.swingcheck.settings.LengthUnit
import jp.co.updates.swingcheck.settings.SettingsRepository
import java.util.Locale

class SwingcheckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 依存関係の組み立て（DI ライブラリは使わず手書き）。必要になるまで作らない。 */
class AppContainer(private val app: Application) {
    val database: AppDatabase by lazy { AppDatabase.create(app) }

    val files: SwingFiles by lazy { SwingFiles(app.filesDir) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("settings") },
            LengthUnit.defaultFor(Locale.getDefault().country),
        )
    }

    val swingRepository: SwingRepository by lazy { SwingRepository(database.swingDao(), files) }

    val analysisScheduler: AnalysisScheduler by lazy { AnalysisScheduler(app) }

    /** 骨格推定の実装の差し替え口（将来のサーバー解析など）。 */
    fun createPoseEstimator(): PoseEstimator = MediaPipePoseEstimator(app)

    val analysisPipeline: AnalysisPipeline by lazy {
        AnalysisPipeline(database.swingDao(), swingRepository, files, settingsRepository, ::createPoseEstimator)
    }

    val videoImporter: VideoImporter by lazy {
        VideoImporter(app, files, swingRepository, settingsRepository, analysisScheduler)
    }
}
