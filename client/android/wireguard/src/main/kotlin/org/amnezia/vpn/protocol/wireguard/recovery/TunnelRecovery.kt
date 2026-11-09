package org.amnezia.vpn.protocol.wireguard.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
) {
    private val environment = MutableStateFlow(Environment())
    private val health = TunnelHealth()
    private var lastAttemptMillis: Long? = null

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
        } catch (_: Exception) {
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
            if (attempt > 0) {
                publish(ProtocolState.RECONNECTING)
                delay((2_000L shl (attempt - 1)).coerceAtMost(30_000))
            }
            try {
                return withTimeout(handshakeTimeoutMillis) {
                    val network = environment.first { it.online && !it.idle }
                    lastAttemptMillis?.let {
                        delay((2_000 - (elapsedMillis() - it)).coerceAtLeast(0))
                    }
                    lastAttemptMillis = elapsedMillis()
                    if (needsStart) {
                        needsStart = false
                        start()
                    } else {
                        restart()
                    }
                    probe()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val current = snapshot() ?: error("Tunnel telemetry unavailable")
                        if (current.handshakeMillis > 0) {
                            break
                        }
                        delay(1_000)
                    }
                    network
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                failure = e
            }
        }
        throw IllegalStateException("Tunnel recovery failed", failure)
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
                    HealthResult.UNHEALTHY -> return
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
                return@withTimeoutOrNull HealthResult.DEFERRED
            }
            if (wait < 0) {
                return@withTimeoutOrNull HealthResult.UNHEALTHY
            }
            while (true) {
                if (environment.value.idle) {
                    return@withTimeoutOrNull HealthResult.DEFERRED
                }
                if (!environment.value.online) {
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
        } ?: HealthResult.UNHEALTHY
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
