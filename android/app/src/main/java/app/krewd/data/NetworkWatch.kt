package app.krewd.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Follows the phone's default network (Wi-Fi, mobile data, a VPN such as Tailscale) and calls [onChange] when it
 * changes or goes away, so the app checks its connection at once instead of waiting for a dead one to time out.
 * The first report after [start] is the network the phone is already on and is not a change.
 */
class NetworkWatch(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var current: Network? = null
    @Volatile private var started = false
    @Volatile private var wifi = false

    /** On Wi-Fi or Ethernet (where a home address and the mDNS search can work), as far as the phone knows. */
    val onLocalNetwork: Boolean get() = wifi

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val was = current
            current = network
            if (was != null && was != network) onChange()
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                onChange()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                // Behind a VPN the default network is the VPN; the underlying one is not named, so stay hopeful.
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    fun start() {
        if (started || connectivity == null) return
        started = true
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }.onFailure { started = false }
    }
}
