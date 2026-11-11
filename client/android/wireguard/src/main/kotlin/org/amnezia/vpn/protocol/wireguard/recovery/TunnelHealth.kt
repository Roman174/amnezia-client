package org.amnezia.vpn.protocol.wireguard.recovery

data class TunnelSnapshot(
    val handshakeMillis: Long,
    val receivedBytes: Long,
    val sentBytes: Long,
    val outboundBytes: Long,
    val sendErrors: Long,
    val udp: UdpSnapshot? = null,
) {
    companion object {
        fun parse(config: String?): TunnelSnapshot? {
            val values = config?.lineSequence()?.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator < 0) {
                    null
                } else {
                    line.substring(separator + 1).toLongOrNull()?.let {
                        line.substring(0, separator) to it
                    }
                }
            }?.toMap() ?: return null

            return TunnelSnapshot(
                handshakeMillis = (values["last_handshake_time_sec"] ?: return null) * 1000 +
                    (values["last_handshake_time_nsec"] ?: return null) / 1_000_000,
                receivedBytes = values["rx_bytes"] ?: return null,
                sentBytes = values["tx_bytes"] ?: return null,
                outboundBytes = values["tun_tx_bytes"] ?: return null,
                sendErrors = values["tx_errors"] ?: return null,
                udp = UdpSnapshot.parse(values),
            ).takeIf {
                it.handshakeMillis >= 0 && it.receivedBytes >= 0 && it.sentBytes >= 0 &&
                    it.outboundBytes >= 0 && it.sendErrors >= 0
            }
        }
    }
}

class TunnelHealth {
    private var previous: TunnelSnapshot? = null
    private var unansweredSince: Long? = null
    private var readFailures = 0

    fun reset(snapshot: TunnelSnapshot?) {
        previous = snapshot
        unansweredSince = null
        readFailures = 0
    }

    fun needsProbe(
        snapshot: TunnelSnapshot?,
        elapsedMillis: Long,
    ): Boolean {
        if (snapshot == null) {
            return ++readFailures >= 2
        }
        readFailures = 0
        val last = previous
        previous = snapshot
        if (last == null || snapshot.outboundBytes < last.outboundBytes ||
            snapshot.receivedBytes > last.receivedBytes || snapshot.handshakeMillis > last.handshakeMillis
        ) {
            unansweredSince = null
            return false
        }

        if (snapshot.outboundBytes > last.outboundBytes || snapshot.sendErrors > last.sendErrors) {
            if (unansweredSince == null) {
                unansweredSince = elapsedMillis
            }
        }
        return unansweredSince?.let { elapsedMillis - it >= 30_000 } == true
    }
}
