import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 骨格推定モデル（Google 公式）。git には入れず、ビルド時にダウンロードして assets に入れる。
// URL は版を固定したもの（/1/）。内容が変わっても気づけるように SHA-256 を固定して検証する。
val poseModelFileName = "pose_landmarker_full.task"
val poseModelUrl =
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/$poseModelFileName"
val poseModelSha256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"

abstract class DownloadPoseModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val fileName: Property<String>

    /** ダウンロード済みファイルのキャッシュ置き場（clean しても残る）。 */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun run() {
        val expected = sha256.get().lowercase()
        val cacheRoot = cacheDir.get().asFile.also { it.mkdirs() }
        val cached = File(cacheRoot, "$expected-${fileName.get()}")

        if (cached.exists() && sha256Of(cached) != expected) {
            logger.warn("Cached model is corrupted, deleting: $cached")
            cached.delete()
        }
        if (!cached.exists()) {
            logger.lifecycle("Downloading ${url.get()}")
            val tmp = File(cacheRoot, "${cached.name}.part")
            val conn = URI(url.get()).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val actual = sha256Of(tmp)
            if (actual != expected) {
                tmp.delete()
                throw GradleException("SHA-256 mismatch for ${url.get()}: expected $expected but was $actual")
            }
            tmp.renameTo(cached)
        }

        val out = outputDir.get().asFile.also { it.mkdirs() }
        cached.copyTo(File(out, fileName.get()), overwrite = true)
    }
}

val downloadPoseModel = tasks.register<DownloadPoseModelTask>("downloadPoseModel") {
    url.set(poseModelUrl)
    sha256.set(poseModelSha256)
    fileName.set(poseModelFileName)
    cacheDir.set(File(gradle.gradleUserHomeDir, "swingcheck-models"))
    outputDir.set(layout.buildDirectory.dir("generated/poseModel/assets"))
}

android {
    namespace = "jp.co.updates.swingcheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.updates.swingcheck"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // モデルは圧縮せずに置く（MediaPipe がメモリマップで読めるように）
    androidResources {
        noCompress += "task"
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadPoseModel, DownloadPoseModelTask::outputDir)
    }
}

// Room のスキーマ（schemas/）は git に入れる。マイグレーションを書くときの比較元になる
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
