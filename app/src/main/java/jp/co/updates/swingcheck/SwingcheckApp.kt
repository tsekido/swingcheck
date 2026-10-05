package jp.co.updates.swingcheck

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import jp.co.updates.swingcheck.analysis.AnalysisGate
import jp.co.updates.swingcheck.analysis.AnalysisPipeline
import jp.co.updates.swingcheck.analysis.AnalysisScheduler
import jp.co.updates.swingcheck.capture.CaptureController
import jp.co.updates.swingcheck.data.AppDatabase
import jp.co.updates.swingcheck.data.SwingFiles
import jp.co.updates.swingcheck.data.SwingRepository
import jp.co.updates.swingcheck.pose.MediaPipePoseEstimator
import jp.co.updates.swingcheck.pose.PoseEstimator
import jp.co.updates.swingcheck.settings.LengthUnit
import jp.co.updates.swingcheck.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    val swingRegistrar: SwingRegistrar by lazy { SwingRegistrar(swingRepository, settingsRepository, analysisScheduler) }

    val videoImporter: VideoImporter by lazy { VideoImporter(app, files, swingRegistrar) }

    /** 撮影中は新しい解析を始めない（撮影画面の表示中だけ true になる）。 */
    val analysisGate: AnalysisGate by lazy { AnalysisGate() }

    val captureController: CaptureController by lazy {
        CaptureController(
            app, settingsRepository, files, swingRegistrar, analysisGate, ::createPoseEstimator,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
