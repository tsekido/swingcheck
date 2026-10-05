package jp.co.updates.swingcheck.pose

import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Landmark
import jp.co.updates.swingcheck.core.PoseFrame
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 骨格の列のファイル形式（design.md 10章、`pose/<id>.bin`）。リトルエンディアン。
 *
 * ```
 * ヘッダ  : magic "SWPS"(4 バイト) / 版 Int32 / コマ数 Int32 / 関節数 Int32（33）
 * 時刻    : コマ数 × Int64（ミリ秒。動画の先頭が 0）
 * 骨格    : コマ数 × 関節数 × (x, y, z, visibility) の Float32
 * ```
 */
object PoseFileFormat {
    const val VERSION = 1
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte())
    private const val HEADER_SIZE = 16
    private const val VALUES_PER_JOINT = 4

    fun encode(frames: List<PoseFrame>): ByteArray {
        val n = frames.size
        val size = HEADER_SIZE + n * 8L + n.toLong() * Joint.COUNT * VALUES_PER_JOINT * 4
        require(size <= Int.MAX_VALUE) { "too many frames" }
        val buf = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(VERSION)
        buf.putInt(n)
        buf.putInt(Joint.COUNT)
        for (f in frames) buf.putLong(f.timestampMs)
        for (f in frames) {
            for (l in f.landmarks) {
                buf.putFloat(l.x)
                buf.putFloat(l.y)
                buf.putFloat(l.z)
                buf.putFloat(l.visibility)
            }
        }
        return buf.array()
    }

    /** 形式が合わない（壊れている、版が違う）ときは [IOException]。 */
    fun decode(bytes: ByteArray): List<PoseFrame> {
        if (bytes.size < HEADER_SIZE) throw IOException("pose file too short")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("not a pose file")
        val version = buf.getInt()
        if (version != VERSION) throw IOException("unsupported pose file version: $version")
        val n = buf.getInt()
        val joints = buf.getInt()
        if (n < 0 || joints != Joint.COUNT) throw IOException("invalid pose file header")
        val expected = HEADER_SIZE + n * 8L + n.toLong() * joints * VALUES_PER_JOINT * 4
        if (bytes.size.toLong() != expected) throw IOException("pose file size mismatch")

        val times = LongArray(n) { buf.getLong() }
        return List(n) { i ->
            PoseFrame(
                times[i],
                List(joints) { Landmark(buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat()) },
            )
        }
    }

    /** 途中で止まっても壊れたファイルが残らないよう、一時ファイルに書いてから置き換える。 */
    fun write(file: File, frames: List<PoseFrame>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encode(frames))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("failed to write $file")
        }
    }

    fun read(file: File): List<PoseFrame> = decode(file.readBytes())
}
