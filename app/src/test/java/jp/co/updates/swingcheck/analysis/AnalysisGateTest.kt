package jp.co.updates.swingcheck.analysis

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalysisGateTest {
    @Test
    fun returnsImmediatelyWhenNotCapturing() = runBlocking {
        withTimeout(1000) { AnalysisGate().awaitIdle() }
    }

    @Test
    fun waitsWhileCapturingAndResumesWhenStopped() = runBlocking {
        val gate = AnalysisGate()
        gate.setCapturing(true)
        val waiter = async { gate.awaitIdle() }
        delay(100)
        assertFalse(waiter.isCompleted)
        gate.setCapturing(false)
        withTimeout(1000) { waiter.await() }
        assertTrue(waiter.isCompleted)
    }
}
