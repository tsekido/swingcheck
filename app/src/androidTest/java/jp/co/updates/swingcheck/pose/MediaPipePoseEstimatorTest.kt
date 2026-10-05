package jp.co.updates.swingcheck.pose

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipePoseEstimatorTest {
    /** モデルが assets から読めて、人のいない画像では null（検出なし）が返ること。 */
    @Test
    fun blankImageHasNoPose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MediaPipePoseEstimator(context).use { estimator ->
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
            assertNull(estimator.estimate(bitmap, 0))
            assertNull(estimator.estimate(bitmap, 33))
        }
    }
}
