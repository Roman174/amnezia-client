package org.amnezia.vpn.protocol.wireguard.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.vpn.protocol.ProtocolState

class TunnelRecovery(
    private val state: MutableStateFlow<ProtocolState>,
    private val start: suspend () -> Unit,
    private val restart: suspend () -> Unit,
    private val stop: () -> Unit,
    private val snapshot: () -> TunnelSnapshot?,
    private val probe: () -> Long,
    private val elapsedMillis: () -> Long,
    private val onFailure: () -> Unit,
    private val handshakeTimeoutMillis: Long = 45_000,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val environment = MutableStateFlow(Environment())
    private val health = TunnelHealth()
    private var lastAttemptMillis: Long? = null
    private var restartReason = "initial"

    fun updateNetwork(
        online: Boolean,
        epoch: Long,
        networkId: Long? = if (online) epoch else null,
    ) {
        environment.update {
            it.copy(online = online, networkEpoch = epoch, networkId = networkId)
        }
    }

    fun updatePower(
        interactive: Boolean,
        idle: Boolean,
    ) {
        environment.update {
            if (it.interactive == interactive && it.idle == idle) {
                it
            } else {
                it.copy(interactive = interactive, idle = idle, wakeEpoch = it.wakeEpoch + 1)
            }
        }
    }

    suspend fun run() {
        try {
            var initial = true
            while (true) {
                val connectedEnvironment = connect(initial)
                initial = false
                health.reset(snapshot())
                publish(ProtocolState.CONNECTED)
                monitor(connectedEnvironment)
                publish(ProtocolState.RECONNECTING)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onDiagnostic("Recovery failed: error=${e.javaClass.simpleName}")
            onFailure()
        } finally {
            withContext(NonCancellable) {
                stop()
                state.value = ProtocolState.DISCONNECTED
            }
        }
    }

    private suspend fun connect(initial: Boolean): Environment {
        var failure: Exception? = null
        var needsStart = initial
        repeat(5) { attempt ->
            var stage = "waiting_network"
            if (attempt > 0) {
                publish(ProtocolState.RECONNECTING)
                val retryDelayMillis = (2_000L shl (attempt - 1)).coerceAtMost(30_000)
                onDiagnostic("Retry scheduled: attempt=${attempt + 1} reason=$restartReason delayMillis=$retryDelayMillis")
                delay(retryDelayMillis)
            }
            try {
                return withTimeout(handshakeTimeoutMillis) {
                    var network: Environment
                    var connected: Boolean
                    do {
                        lastAttemptMillis?.let {
                            delay((2_000 - (elapsedMillis() - it)).coerceAtLeast(0))
                        }
                        network = environment.first {
                            it.online && !it.idle
                        }
                        stage = "backend_start"
                        lastAttemptMillis = elapsedMillis()
                        val operation = if (needsStart) "start" else "restart"
                        onDiagnostic("Backend $operation: attempt=${attempt + 1} reason=$restartReason " +
                            "networkId=${network.networkId} epoch=${network.networkEpoch}")
                        if (needsStart) {
                            start()
                            needsStart = false
                        } else {
                            restart()
                        }
                        stage = "probe"
                        probe()
                        stage = "handshake"
                        connected = awaitHandshake(network)
                        if (!connected) {
                            publish(ProtocolState.RECONNECTING)
                        }
                        stage = "waiting_network"
                    } while (!connected)
                    network
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                restartReason = if (e is TimeoutCancellationException) {
                    "${stage}_timeout"
                } else {
                    "attempt_failed"
                }
                onDiagnostic("Attempt failed: attempt=${attempt + 1} reason=$restartReason " +
                    "stage=$stage error=${e.javaClass.simpleName} environment=${environment.value}")
                failure = e
            }
        }
        throw IllegalStateException("Tunnel recovery failed", failure)
    }

    private suspend fun awaitHandshake(network: Environment): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val currentEnvironment = environment.value
            if (!currentEnvironment.online || currentEnvironment.idle ||
                currentEnvironment.networkId != network.networkId
            ) {
                restartReason = when {
                    !currentEnvironment.online -> "network_unavailable"
                    currentEnvironment.idle -> "device_idle"
                    else -> "network_changed"
                }
                onDiagnostic("Handshake interrupted: reason=$restartReason " +
                    "startedNetworkId=${network.networkId} environment=$currentEnvironment")
                return false
            }

            val current = snapshot() ?: error("Tunnel telemetry unavailable")
            if (current.handshakeMillis > 0) {
                onDiagnostic("Handshake confirmed: networkId=${network.networkId}")
                return true
            }
            delay(1_000)
        }
    }

    private suspend fun monitor(connectedEnvironment: Environment) {
        var observed = connectedEnvironment
        var observeUntilMillis = elapsedMillis() + 60_000
        while (true) {
            val sampling = !observed.idle && (observed.interactive || elapsedMillis() < observeUntilMillis)
            val timeout = if (sampling) 15_000L else Long.MAX_VALUE
            var next = withTimeoutOrNull(timeout) {
                environment.first { it != observed }
            } ?: environment.value
            val waking = (observed.idle && !next.idle) || (!observed.interactive && next.interactive)
            if (waking && !next.online) {
                next = withTimeoutOrNull(5_000) {
                    environment.first { it.online || it.idle }
                } ?: environment.value
            }
            if (next.idle) {
                observed = next
                continue
            }
            if (!next.online || next.networkId != connectedEnvironment.networkId) {
                restartReason = if (next.online) "network_changed" else "network_unavailable"
                onDiagnostic("Monitor interrupted: reason=$restartReason " +
                    "connectedNetworkId=${connectedEnvironment.networkId} environment=$next")
                return
            }
            if (next.wakeEpoch != observed.wakeEpoch) {
                health.reset(snapshot())
                observeUntilMillis = elapsedMillis() + 60_000
            }
            observed = next
            if (next.idle || (!next.interactive && elapsedMillis() >= observeUntilMillis)) {
                continue
            }
            if (health.needsProbe(snapshot = snapshot(), elapsedMillis = elapsedMillis())) {
                when (confirmHealth()) {
                    HealthResult.UNHEALTHY -> {
                        restartReason = "health_check_failed"
                        onDiagnostic("Monitor interrupted: reason=$restartReason environment=${environment.value}")
                        return
                    }
                    HealthResult.HEALTHY -> health.reset(snapshot())
                    HealthResult.DEFERRED -> {}
                }
            }
        }
    }

    private suspend fun confirmHealth(): HealthResult {
        val before = snapshot() ?: return HealthResult.UNHEALTHY
        return withTimeoutOrNull(handshakeTimeoutMillis) {
            val wait = probe()
            if (wait > 0) {
                onDiagnostic("Health probe deferred: delayMillis=$wait")
                return@withTimeoutOrNull HealthResult.DEFERRED
            }
            if (wait < 0) {
                onDiagnostic("Health probe failed")
                return@withTimeoutOrNull HealthResult.UNHEALTHY
            }
            while (true) {
                if (environment.value.idle) {
                    return@withTimeoutOrNull HealthResult.DEFERRED
                }
                if (!environment.value.online) {
                    onDiagnostic("Health probe interrupted: reason=network_unavailable")
                    return@withTimeoutOrNull HealthResult.UNHEALTHY
                }
                delay(1_000)
                val current = snapshot() ?: return@withTimeoutOrNull HealthResult.UNHEALTHY
                if (current.handshakeMillis > before.handshakeMillis || current.receivedBytes > before.receivedBytes) {
                    return@withTimeoutOrNull HealthResult.HEALTHY
                }
            }
            @Suppress("UNREACHABLE_CODE")
            HealthResult.UNHEALTHY
        } ?: run {
            onDiagnostic("Health probe timed out: timeoutMillis=$handshakeTimeoutMillis")
            HealthResult.UNHEALTHY
        }
    }

    private suspend fun publish(value: ProtocolState) {
        currentCoroutineContext().ensureActive()
        state.update {
            if (it == ProtocolState.DISCONNECTING) it else value
        }
    }

    private enum class HealthResult {
        HEALTHY, DEFERRED, UNHEALTHY
    }

    private data class Environment(
        val online: Boolean = false,
        val networkEpoch: Long = 0,
        val networkId: Long? = null,
        val interactive: Boolean = true,
        val idle: Boolean = false,
        val wakeEpoch: Long = 0,
    )
}
