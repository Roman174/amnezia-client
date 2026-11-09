import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.wireguard.recovery.TunnelHealth
import org.amnezia.vpn.protocol.wireguard.recovery.TunnelRecovery
import org.amnezia.vpn.protocol.wireguard.recovery.TunnelSnapshot
import org.amnezia.vpn.util.net.PhysicalNetworkTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelRecoveryTest {
    @Test
    fun tenMinuteSleepWithSameNetworkDoesNotReconnect() = runTest {
        checkSleep(600_000)
    }

    @Test
    fun dozeBlockingSamePhysicalNetworkDoesNotRebuildHealthyTunnel() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.controller.updatePower(interactive = false, idle = true)
        backend.controller.updateNetwork(online = false, epoch = 2)
        advanceTimeBy(3_600_000)
        backend.controller.updatePower(interactive = true, idle = false)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(0, backend.restarts)
        backend.controller.updateNetwork(online = true, epoch = 3, networkId = 1)
        advanceTimeBy(60_000)
        assertEquals(0, backend.restarts)
        assertEquals(ProtocolState.CONNECTED, backend.state.value)
        job.cancelAndJoin()
    }

    @Test
    fun hourSleepWithSameNetworkDoesNotReconnect() = runTest {
        checkSleep(3_600_000)
    }

    @Test
    fun staleHandshakeWithoutTrafficDoesNotReconnect() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        advanceTimeBy(3_600_000)
        assertEquals(1, backend.starts)
        assertEquals(0, backend.restarts)
        assertEquals(1, backend.probes)
        job.cancelAndJoin()
    }

    @Test
    fun outgoingTrafficWithoutRepliesProbesBeforeRestarting() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.telemetry = backend.telemetry!!.copy(outboundBytes = 100)
        advanceTimeBy(45_001)
        assertEquals(2, backend.probes)
        assertEquals(0, backend.restarts)
        advanceTimeBy(45_001)
        assertEquals(1, backend.restarts)
        assertEquals(ProtocolState.CONNECTED, backend.state.value)
        job.cancelAndJoin()
    }

    @Test
    fun freshProbeHandshakePreventsUnnecessaryRestart() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.answerProbes = true
        backend.telemetry = backend.telemetry!!.copy(outboundBytes = 100)
        advanceTimeBy(90_000)
        assertEquals(0, backend.restarts)
        assertEquals(2, backend.probes)
        job.cancelAndJoin()
    }

    @Test
    fun customHandshakeThrottleDoesNotCauseReconnect() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.probeDelay = 120_000
        backend.telemetry = backend.telemetry!!.copy(outboundBytes = 100)
        advanceTimeBy(180_000)
        assertEquals(0, backend.restarts)
        job.cancelAndJoin()
    }

    @Test
    fun deferredProbeKeepsUnansweredTrafficUntilItCanCheck() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.probeDelay = 10_000
        backend.telemetry = backend.telemetry!!.copy(outboundBytes = 100)
        advanceTimeBy(45_001)
        assertEquals(0, backend.restarts)
        backend.probeDelay = 0
        advanceTimeBy(60_001)
        assertEquals(1, backend.restarts)
        job.cancelAndJoin()
    }

    @Test
    fun wifiToMobileAndConcurrentCallbacksCoalesce() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        repeat(100) {
            backend.controller.updateNetwork(online = true, epoch = 2)
        }
        advanceTimeBy(2_001)
        assertEquals(1, backend.restarts)
        assertEquals(ProtocolState.CONNECTED, backend.state.value)
        job.cancelAndJoin()
    }

    @Test
    fun signalLossWaitsForPhysicalNetwork() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.controller.updateNetwork(online = false, epoch = 2)
        advanceTimeBy(20_000)
        assertEquals(0, backend.restarts)
        assertEquals(ProtocolState.RECONNECTING, backend.state.value)
        backend.controller.updateNetwork(online = true, epoch = 3)
        runCurrent()
        assertEquals(1, backend.restarts)
        job.cancelAndJoin()
    }

    @Test
    fun failedHandshakeAndStuckReconnectingHaveBoundedAttempts() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.completeHandshake = false
        backend.controller.updateNetwork(online = true, epoch = 2)
        advanceTimeBy(300_000)
        assertEquals(5, backend.restarts)
        assertEquals(1, backend.failures)
        assertEquals(ProtocolState.DISCONNECTED, backend.state.value)
        assertTrue(job.isCompleted)
        assertTrue(backend.attemptTimes.zipWithNext().all { (a, b) -> b - a >= 2_000 })
    }

    @Test
    fun unavailableBackendNeverBecomesConnected() = runTest {
        val backend = Backend(this)
        backend.readable = false
        val job = launch { backend.controller.run() }
        advanceTimeBy(300_000)
        assertEquals(1, backend.failures)
        assertEquals(ProtocolState.DISCONNECTED, backend.state.value)
        assertTrue(job.isCompleted)
    }

    @Test
    fun initialOfflineTimeoutDoesNotSkipBackendInitialization() = runTest {
        val backend = Backend(this)
        backend.controller.updateNetwork(online = false, epoch = 0)
        val job = launch { backend.controller.run() }
        advanceTimeBy(50_000)
        backend.controller.updateNetwork(online = true, epoch = 1)
        runCurrent()
        assertEquals(1, backend.starts)
        assertEquals(0, backend.restarts)
        assertEquals(ProtocolState.CONNECTED, backend.state.value)
        job.cancelAndJoin()
    }

    @Test
    fun userDisconnectDuringRecoveryCancelsAllFutureWork() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.completeHandshake = false
        backend.controller.updateNetwork(online = true, epoch = 2)
        advanceTimeBy(50_000)
        backend.state.value = ProtocolState.DISCONNECTING
        job.cancelAndJoin()
        val attempts = backend.restarts
        backend.controller.updatePower(interactive = true, idle = false)
        backend.controller.updateNetwork(online = true, epoch = 3)
        advanceTimeBy(600_000)
        assertEquals(attempts, backend.restarts)
        assertEquals(1, backend.stops)
        assertEquals(ProtocolState.DISCONNECTED, backend.state.value)
    }

    @Test
    fun backendRestartDoesNotStopOrReinitializeSession() = runTest {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.controller.updateNetwork(online = true, epoch = 2)
        advanceTimeBy(2_001)
        assertEquals(1, backend.starts)
        assertEquals(1, backend.restarts)
        assertEquals(0, backend.stops)
        job.cancelAndJoin()
    }

    @Test
    fun lostBlockedAndInvalidPhysicalNetworksAreRemoved() {
        val networks = PhysicalNetworkTracker<String>()
        networks.available("wifi")
        assertNull(networks.selected)
        assertTrue(networks.capabilities(network = "wifi", usable = true, priority = 2))
        assertFalse(networks.capabilities(network = "vpn", usable = false, priority = 3))
        assertFalse(networks.capabilities(network = "cell", usable = true, priority = 1))
        assertEquals("wifi", networks.selected)
        assertTrue(networks.blocked(network = "wifi", blocked = true))
        assertEquals("cell", networks.selected)
        assertTrue(networks.lost("cell"))
        assertNull(networks.selected)
        assertTrue(networks.blocked(network = "wifi", blocked = false))
        assertTrue(networks.capabilities(network = "wifi", usable = false, priority = 2))
        assertNull(networks.selected)
    }

    @Test
    fun missingHandshakeAndLegacyTelemetryAreErrors() {
        assertNull(TunnelSnapshot.parse(null))
        assertNull(TunnelSnapshot.parse("last_handshake_time_sec=-2"))
        assertNull(TunnelSnapshot.parse("last_handshake_time_sec=100\nrx_bytes=0\ntx_bytes=0"))
        assertEquals(123_456, TunnelSnapshot.parse(
            "last_handshake_time_sec=123\nlast_handshake_time_nsec=456000000\n" +
                "rx_bytes=10\ntx_bytes=20\ntun_tx_bytes=30\ntx_errors=0"
        )?.handshakeMillis)
    }

    @Test
    fun backendCounterResetClearsHealthSuspicion() {
        val health = TunnelHealth()
        val initial = TunnelSnapshot(1, 100, 100, 100, 0)
        health.reset(initial)
        assertFalse(health.needsProbe(snapshot = initial.copy(outboundBytes = 200), elapsedMillis = 0))
        assertFalse(health.needsProbe(snapshot = initial.copy(outboundBytes = 0), elapsedMillis = 60_000))
    }

    private suspend fun TestScope.checkSleep(duration: Long) {
        val backend = Backend(this)
        val job = launch { backend.controller.run() }
        runCurrent()
        backend.controller.updatePower(interactive = false, idle = true)
        advanceTimeBy(duration)
        val reads = backend.reads
        backend.controller.updatePower(interactive = true, idle = false)
        advanceTimeBy(60_000)
        assertTrue(backend.reads > reads)
        assertEquals(0, backend.restarts)
        assertEquals(1, backend.probes)
        job.cancelAndJoin()
    }

    private class Backend(private val scope: TestScope) {
        var starts = 0
        var restarts = 0
        var stops = 0
        var probes = 0
        var reads = 0
        var failures = 0
        var completeHandshake = true
        var readable = true
        var answerProbes = false
        var probeDelay = 0L
        var telemetry: TunnelSnapshot? = null
        val attemptTimes = mutableListOf<Long>()
        val state = MutableStateFlow(ProtocolState.CONNECTING)
        val controller = TunnelRecovery(
            state = state,
            start = {
                starts++
                open()
            },
            restart = {
                restarts++
                open()
            },
            stop = {
                stops++
            },
            snapshot = {
                reads++
                if (readable) telemetry else null
            },
            probe = {
                probes++
                if (answerProbes) {
                    telemetry = telemetry?.copy(handshakeMillis = scope.testScheduler.currentTime + 1)
                }
                probeDelay
            },
            elapsedMillis = {
                scope.testScheduler.currentTime
            },
            onFailure = {
                failures++
            },
        )

        init {
            controller.updateNetwork(online = true, epoch = 1)
        }

        private fun open() {
            attemptTimes += scope.testScheduler.currentTime
            telemetry = TunnelSnapshot(
                handshakeMillis = if (completeHandshake) scope.testScheduler.currentTime + 1 else 0,
                receivedBytes = 0,
                sentBytes = 0,
                outboundBytes = 0,
                sendErrors = 0,
            )
        }
    }
}
