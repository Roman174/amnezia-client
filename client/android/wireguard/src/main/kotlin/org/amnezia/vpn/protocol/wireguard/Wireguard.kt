package org.amnezia.vpn.protocol.wireguard

import android.net.VpnService.Builder
import android.os.ParcelFileDescriptor
import org.amnezia.awg.GoBackend
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.VpnException
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.wireguard.recovery.TunnelSnapshot
import org.amnezia.vpn.util.LibraryLoader.loadSharedLibrary
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.asSequence
import org.amnezia.vpn.util.net.InetEndpoint
import org.amnezia.vpn.util.net.InetNetwork
import org.amnezia.vpn.util.net.parseInetAddress
import org.amnezia.vpn.util.optStringOrNull
import org.json.JSONObject

private const val TAG = "Wireguard"

open class Wireguard : Protocol() {

    private val backendLock = Any()
    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var tunnelHandle: Int = -1
    private var config: WireguardConfig? = null // save config for reconnect
    protected open val ifName: String = "amn0"

    override val statistics: Statistics
        get() = synchronized(backendLock) {
            val telemetry = readSnapshot()
            if (telemetry == null) {
                Statistics.EMPTY_STATISTICS
            } else {
                Statistics.build {
                    setRxBytes(telemetry.receivedBytes)
                    setTxBytes(telemetry.sentBytes)
                }
            }
        }

    override fun internalInit() {
        if (!isInitialized) loadSharedLibrary(context, "wg-go")

    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        val wireguardConfig = parseConfig(config)
        synchronized(backendLock) {
            this.config = wireguardConfig
            start(config = wireguardConfig, vpnBuilder = vpnBuilder, protect = protect)
        }
    }

    protected open fun parseConfig(config: JSONObject): WireguardConfig {
        val configData = config.getJSONObject("wireguard_config_data")
        return WireguardConfig.build {
            configWireguard(config, configData)
            configSplitTunneling(config)
            configAppSplitTunneling(config)
        }
    }

    protected fun WireguardConfig.Builder.configWireguard(config: JSONObject, configData: JSONObject) {
        configData.getString("client_ip").split(",").map { address ->
            InetNetwork.parse(address.trim())
        }.forEach(::addAddress)

        config.optStringOrNull("dns1")?.let { dns ->
            addDnsServer(parseInetAddress(dns.trim()))
        }

        config.optStringOrNull("dns2")?.let { dns ->
            addDnsServer(parseInetAddress(dns.trim()))
        }

        val defRoutes = hashSetOf(
            InetNetwork("0.0.0.0", 0),
            InetNetwork("::", 0)
        )
        val routes = hashSetOf<InetNetwork>()
        configData.getJSONArray("allowed_ips").asSequence<String>().map { route ->
            InetNetwork.parse(route.trim())
        }.forEach(routes::add)
        // if the allowed IPs list contains at least one non-default route, disable global split tunneling
        if (routes.any { it !in defRoutes }) disableSplitTunneling()
        addRoutes(routes)

        configData.optStringOrNull("mtu")?.let { setMtu(it.toInt()) }

        val host = configData.getString("hostName").let { parseInetAddress(it.trim()) }
        val port = configData.getInt("port")
        setEndpoint(InetEndpoint(host, port))

        if (configData.optBoolean("isObfuscationEnabled")) {
            setUseProtocolExtension(true)
            configExtensionParameters(configData)
        }

        configData.optStringOrNull("persistent_keep_alive")?.let { setPersistentKeepalive(it) }
        configData.getString("client_priv_key").let { setPrivateKeyHex(it.base64ToHex()) }
        configData.getString("server_pub_key").let { setPublicKeyHex(it.base64ToHex()) }
        configData.optStringOrNull("psk_key")?.let { setPreSharedKeyHex(it.base64ToHex()) }
    }

    protected fun WireguardConfig.Builder.configExtensionParameters(configData: JSONObject) {
        configData.optStringOrNull("Jc")?.let { setJc(it.toInt()) }
        configData.optStringOrNull("Jmin")?.let { setJmin(it.toInt()) }
        configData.optStringOrNull("Jmax")?.let { setJmax(it.toInt()) }
        configData.optStringOrNull("S1")?.let { setS1(it.toInt()) }
        configData.optStringOrNull("S2")?.let { setS2(it.toInt()) }
        configData.optStringOrNull("S3")?.let { setS3(it.toInt()) }
        configData.optStringOrNull("S4")?.let { setS4(it.toInt()) }
        configData.optStringOrNull("H1")?.trim()?.let { if (it.isNotEmpty()) setH1(it) }
        configData.optStringOrNull("H2")?.trim()?.let { if (it.isNotEmpty()) setH2(it) }
        configData.optStringOrNull("H3")?.trim()?.let { if (it.isNotEmpty()) setH3(it) }
        configData.optStringOrNull("H4")?.trim()?.let { if (it.isNotEmpty()) setH4(it) }
        configData.optStringOrNull("I1")?.let { setI1(it) }
        configData.optStringOrNull("I2")?.let { setI2(it) }
        configData.optStringOrNull("I3")?.let { setI3(it) }
        configData.optStringOrNull("I4")?.let { setI4(it) }
        configData.optStringOrNull("I5")?.let { setI5(it) }
        configData.optStringOrNull("HeaderProtectionKey")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setHeaderProtectionKey(it.base64ToHex()) }
        configData.optStringOrNull("ContentPaddingAddition")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setContentPaddingAddition(it) }
        configData.optStringOrNull("RekeyAfterTime")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setRekeyAfterTime(it) }
        configData.optStringOrNull("RekeyTimeout")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setRekeyTimeout(it) }
        configData.optStringOrNull("RejectAfterTime")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setRejectAfterTime(it) }
        configData.optStringOrNull("KeepaliveTimeout")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setKeepaliveTimeout(it) }
        configData.optStringOrNull("MaxHandshakeAttempts")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setMaxHandshakeAttempts(it) }
        configData.optStringOrNull("RandomTrailers")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setRandomTrailers(it) }
        configData.optStringOrNull("DisableCookies")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { setDisableCookies(it) }
    }

    fun readSnapshot(): TunnelSnapshot? = synchronized(backendLock) {
        if (tunnelHandle < 0) {
            null
        } else {
            TunnelSnapshot.parse(GoBackend.awgGetConfig(tunnelHandle))
        }
    }

    fun probe(): Long = synchronized(backendLock) {
        try {
            if (tunnelHandle < 0) -1L else GoBackend.awgProbe(tunnelHandle).toLong()
        } catch (_: LinkageError) {
            throw VpnException("Incompatible WireGuard recovery backend")
        }
    }

    override fun stopVpn() {
        synchronized(backendLock) {
            turnOffVpn()
            tunnelDescriptor?.close()
            tunnelDescriptor = null
            config = null
            state.value = DISCONNECTED
        }
    }

    override fun reconnectVpn(
        vpnBuilder: Builder,
        protect: (Int) -> Boolean,
    ) {
        synchronized(backendLock) {
            val config = this.config ?: throw VpnException("Reconnect config is empty")
            turnOffVpn()
            start(config = config, vpnBuilder = vpnBuilder, protect = protect)
        }
    }

    private fun start(
        config: WireguardConfig,
        vpnBuilder: Builder,
        protect: (Int) -> Boolean,
    ) {
        if (tunnelHandle >= 0) {
            return
        }

        val deviceSettings = buildString {
            config.appendDeviceLine(this)
        }
        val peerSettings = buildString {
            config.appendPeerLine(this)
        }
        if (tunnelDescriptor == null) {
            buildVpnInterface(config, vpnBuilder)
            tunnelDescriptor = vpnBuilder.establish()
                ?: throw VpnStartException("Create VPN interface: permission not granted or revoked")
        }
        val descriptor = requireNotNull(tunnelDescriptor)
        Log.i(TAG, "awg-go backend ${GoBackend.awgVersion()}")
        tunnelHandle = GoBackend.awgTurnOn(
            ifName = ifName,
            tunFd = ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd(),
            settings = deviceSettings,
        )
        if (tunnelHandle < 0) {
            tunnelHandle = -1
            throw VpnStartException("Wireguard tunnel creation error")
        }
        try {
            val sockets = listOf(
                GoBackend.awgGetSocketV4(tunnelHandle),
                GoBackend.awgGetSocketV6(tunnelHandle),
            ).filter { it >= 0 }
            if (sockets.isEmpty() || sockets.any { !protect(it) }) {
                throw VpnStartException("Protect VPN interface: permission not granted or revoked")
            }
            val result = GoBackend.awgSetConfig(
                handle = tunnelHandle,
                settings = peerSettings,
            )
            if (result != 0) {
                throw VpnStartException("WireGuard peer configuration failed")
            }
        } catch (_: LinkageError) {
            turnOffVpn()
            throw VpnStartException("Incompatible WireGuard startup backend")
        } catch (e: Exception) {
            turnOffVpn()
            throw e
        }
    }

    private fun turnOffVpn() {
        val handleToClose = tunnelHandle
        tunnelHandle = -1
        if (handleToClose >= 0) {
            GoBackend.awgTurnOff(handleToClose)
        }
    }
}
