package jp.co.updates.swingcheck.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder

/** 背面カメラ 1 台の、撮影に必要な情報（[CameraCaps] ＋ 向き）。 */
data class BackCamera(val caps: CameraCaps, val sensorOrientation: Int)

/** Camera2 の CameraCharacteristics と MediaCodec の能力を、[CameraCaps] などに読み替える。カメラの権限は要らない。 */
object CameraCapabilitiesReader {
    /** 背面カメラ（BACKWARD_COMPATIBLE のもの）をカメラ ID の順に返す。 */
    fun readBackCameras(context: Context): List<BackCamera> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.mapNotNull { id ->
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@mapNotNull null
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return@mapNotNull null
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null

            val highSpeed = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps) {
                map.highSpeedVideoSizes.orEmpty().associate { size ->
                    CaptureSize(size.width, size.height) to
                        map.getHighSpeedVideoFpsRangesFor(size).map { FpsRange(it.lower, it.upper) }
                }
            } else {
                emptyMap()
            }

            // SurfaceTexture とエンコーダー（MediaRecorder と同じ扱い）の両方に出せるサイズ
            val textureSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toSet()
            val videoSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty().toSet()
            val normal = textureSizes.intersect(videoSizes).map { size ->
                NormalSize(CaptureSize(size.width, size.height), maxFps(map.getOutputMinFrameDuration(SurfaceTexture::class.java, size), map.getOutputMinFrameDuration(MediaRecorder::class.java, size)))
            }
            val aeRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { FpsRange(it.lower, it.upper) }.orEmpty()
            BackCamera(
                CameraCaps(
                    id, highSpeed, normal, aeRanges,
                    focalLengthMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                    physicalIds = ch.physicalCameraIds.sorted(),
                ),
                ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            )
        }
    }

    /** 最小フレーム間隔（ナノ秒）から求めた fps の上限。間隔が 0 以下（不明）のものは無視し、全部不明なら null。 */
    private fun maxFps(vararg minDurationsNs: Long): Int? {
        val known = minDurationsNs.filter { it > 0 }
        if (known.isEmpty()) return null
        return (1_000_000_000L / known.max()).toInt()
    }

    /** H.264 エンコーダーがそのサイズ・fps に対応しているか。 */
    fun encoderSupports(size: CaptureSize, fps: Int): Boolean {
        val info = findEncoder() ?: return false
        val video = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return false
        return video.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
    }

    /** ハードウェアの H.264 エンコーダー（なければ最初に見つかったもの）。 */
    fun findEncoder(): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        return all.firstOrNull { it.isHardwareAccelerated } ?: all.firstOrNull()
    }
}
