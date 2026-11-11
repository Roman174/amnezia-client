package org.amnezia.vpn.protocol.wireguard.recovery

data class UdpSnapshot(
    val receivedPackets: Long,
    val receivedBytes: Long,
    val receiveErrors: Long,
    val rejected: Map<String, Long>,
) {
    companion object {
        private val rejectionReasons = listOf(
            "invalid_size",
            "header_error",
            "unknown_type",
            "unknown_receiver",
            "expired_key",
            "handshake_queue_full",
            "invalid_mac",
            "cookie_required",
            "rate_limited",
            "cookie_rejected",
            "initiation_rejected",
            "response_rejected",
            "transport_decrypt",
            "transport_replay",
            "peer_stopped",
            "invalid_ip",
            "disallowed_source",
            "session_error",
        )

        fun parse(values: Map<String, Long>): UdpSnapshot? {
            val packets = values["udp_rx_packets"] ?: return null
            val bytes = values["udp_rx_bytes"] ?: return null
            val errors = values["udp_rx_errors"] ?: return null
            if (packets < 0 || bytes < 0 || errors < 0) {
                return null
            }

            val rejected = mutableMapOf<String, Long>()
            for (reason in rejectionReasons) {
                val count = values["udp_rx_$reason"] ?: return null
                if (count < 0) {
                    return null
                }
                if (count > 0) {
                    rejected[reason] = count
                }
            }
            return UdpSnapshot(
                receivedPackets = packets,
                receivedBytes = bytes,
                receiveErrors = errors,
                rejected = rejected,
            )
        }
    }
}
