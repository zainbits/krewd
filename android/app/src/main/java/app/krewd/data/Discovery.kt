package app.krewd.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finds Krewd servers on the local network (mDNS `_krewd._tcp`), for when no saved address answers, such as
 * after a router change. Finding one proves nothing: the app still connects only if the certificate matches
 * its pin. With a pin, servers that announce a different id are skipped before connecting.
 */
object Discovery {
    private const val SERVICE = "_krewd._tcp"

    /** HTTPS addresses found within [timeoutMs]. Blocking: call from Dispatchers.IO. */
    fun find(context: Context, pin: String, timeoutMs: Long = 3_000): List<String> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val wanted = pin.removePrefix("sha256/").take(12)
        val found = ConcurrentHashMap<String, Unit>()
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = ArrayList<NsdManager.ServiceInfoCallback>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                        if (wanted.isNotEmpty() && id != null && id != wanted) return
                        info.hostAddresses.filterIsInstance<Inet4Address>().forEach {
                            found["https://${it.hostAddress}:${info.port}"] = Unit
                        }
                    }
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                synchronized(callbacks) { callbacks += callback }
                runCatching { nsd.registerServiceInfoCallback(service, executor, callback) }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && found.isEmpty()) Thread.sleep(100)
            if (found.isNotEmpty()) Thread.sleep(300) // a moment for a second address of the same server
            found.keys.toList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            synchronized(callbacks) { callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } } }
            executor.shutdown()
        }
    }
}
