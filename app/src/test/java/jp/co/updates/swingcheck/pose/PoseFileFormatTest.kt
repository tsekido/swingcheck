package jp.co.updates.swingcheck.pose

import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.Landmark
import jp.co.updates.swingcheck.core.PoseFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PoseFileFormatTest {
    private fun frames(n: Int): List<PoseFrame> = List(n) { i ->
        PoseFrame(
            1_000L * i + i,
            List(Joint.COUNT) { j -> Landmark(i + j / 100f, j * 0.5f, -j * 0.25f, (j % 10) / 10f) },
        )
    }

    private fun assertSame(a: List<PoseFrame>, b: List<PoseFrame>) {
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].timestampMs, b[i].timestampMs)
            for (j in 0 until Joint.COUNT) assertEquals(a[i].landmarks[j], b[i].landmarks[j])
        }
    }

    @Test
    fun roundTrip() {
        val src = frames(7)
        assertSame(src, PoseFileFormat.decode(PoseFileFormat.encode(src)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(0, PoseFileFormat.decode(PoseFileFormat.encode(emptyList())).size)
    }

    @Test
    fun layoutMatchesDesign() {
        val n = 3
        val bytes = PoseFileFormat.encode(frames(n))
        // ヘッダ 16 バイト + 時刻 n × 8 + 骨格 n × 33 × 4 × 4
        assertEquals(16 + n * 8 + n * 33 * 16, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("SWPS", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(PoseFileFormat.VERSION, buf.getInt(4))
        assertEquals(n, buf.getInt(8))
        assertEquals(33, buf.getInt(12))
        assertEquals(1_001L, buf.getLong(16 + 8)) // 2 コマ目の時刻
        // 先頭コマ・先頭関節の x, y, z, visibility（時刻の次）
        val firstLandmark = 16 + n * 8
        assertEquals(0f, buf.getFloat(firstLandmark))
        assertEquals(0f, buf.getFloat(firstLandmark + 4))
        assertEquals(0f, buf.getFloat(firstLandmark + 12))
    }

    @Test
    fun rejectsBrokenData() {
        val good = PoseFileFormat.encode(frames(2))
        assertThrows(IOException::class.java) { PoseFileFormat.decode(ByteArray(3)) }
        assertThrows(IOException::class.java) { PoseFileFormat.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertThrows(IOException::class.java) { PoseFileFormat.decode(good.copyOf(good.size - 1)) }
        assertThrows(IOException::class.java) { PoseFileFormat.decode(good + byteArrayOf(0)) }
        val wrongVersion = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 99) }
        assertThrows(IOException::class.java) { PoseFileFormat.decode(wrongVersion) }
        val wrongJoints = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 32) }
        assertThrows(IOException::class.java) { PoseFileFormat.decode(wrongJoints) }
        val negativeCount = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, -1) }
        assertThrows(IOException::class.java) { PoseFileFormat.decode(negativeCount) }
    }

    @Test
    fun writeAndReadFile(@TempDir dir: File) {
        val file = File(dir, "pose/5.bin")
        val src = frames(4)
        PoseFileFormat.write(file, src)
        assertTrue(file.exists())
        assertFalse(File(dir, "pose/5.bin.tmp").exists())
        assertSame(src, PoseFileFormat.read(file))
        // 上書き
        PoseFileFormat.write(file, frames(2))
        assertEquals(2, PoseFileFormat.read(file).size)
    }

    @Test
    fun undetectedFrameHasZeroVisibility() {
        val f = PoseFrames.undetected(40)
        assertEquals(40L, f.timestampMs)
        assertTrue(f.landmarks.all { it.visibility == 0f })
    }
}
