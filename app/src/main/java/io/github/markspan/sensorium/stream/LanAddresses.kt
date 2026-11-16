package io.github.markspan.sensorium.stream

import java.net.Inet4Address
import java.net.NetworkInterface

/** An IPv4 address on which LSL consumers on the local network can reach this phone. */
data class LanAddress(val interfaceName: String, val address: String)

object LanAddresses {

    /** Mobile data interfaces: not reachable from a PC on the local network. */
    private val cellular = listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "clat", "dummy", "tun", "ppp")

    /**
     * All usable IPv4 addresses, Wi-Fi first. All interfaces are listed (not only the default
     * network), so the address is also found when the phone itself is the hotspot.
     */
    fun list(): List<LanAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && cellular.none { p -> it.name.startsWith(p) } }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map { LanAddress(nif.name, it.hostAddress ?: "") }
            }
            .filter { it.address.isNotEmpty() }
            .sortedBy { if (it.interfaceName.startsWith("wlan")) 0 else 1 }
    }.getOrDefault(emptyList())

    /** The `KnownPeers` line for the `[lab]` section of lsl_api.cfg. */
    fun knownPeersLine(addresses: List<LanAddress>) =
        "KnownPeers = {${addresses.joinToString(", ") { it.address }}}"
}
