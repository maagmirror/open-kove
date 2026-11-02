package dev.zanderp.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The vendor client (`net.easyconn.carman.ecsocksserver.SocksService`) starts a **SOCKS5 proxy on
 * TCP 10923** and a **DNS forwarder on UDP 10924** the moment the dash's CLIENT_INFO arrives
 * (`Socks5Manager.startSocksServer()` / `startSocks5(10923)` / `startDnsServer(10924)`), bound to the
 * phone's group-owner address. The dash routes its own internet traffic (licence/activation checks
 * against reg.carbit.com.cn, map tiles, OTA) through this proxy — the phone is the only uplink on the
 * Wi‑Fi Direct group, which otherwise has **no internet route**. Until this exists the dash joins,
 * pulls video, and answers every control command, but it never sees a working uplink, so it never
 * lights the Wi‑Fi icon / commits to projection.
 *
 * We reproduce it in pure Kotlin (the vendor uses a native lib): a minimal SOCKS5 CONNECT proxy and a
 * UDP DNS relay, both listening on the GO address and forwarding through the phone's **cellular**
 * network (bound explicitly, because the process default route is the internet-less p2p0 group).
 */
object SocksProxy {
    private const val SOCKS_PORT = 10923
    private const val DNS_PORT = 10924
    private const val UPSTREAM_DNS = "8.8.8.8"

    private val running = AtomicBoolean(false)
    @Volatile private var socksServer: ServerSocket? = null
    @Volatile private var dnsSocket: DatagramSocket? = null
    @Volatile private var uplink: Network? = null
    private var cm: ConnectivityManager? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var log: (String) -> Unit = {}

    fun start(context: Context, bindIp: String, log: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        this.log = log
        val appCtx = context.applicationContext
        cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // NOTE: we deliberately do NOT `requestNetwork(cellular)` here. Forcing a second network up
        // while the phone is group-owner of the 5GHz Wi‑Fi Direct group destabilised the p2p link
        // (the dash pulled a couple of frames then stopped). We only ever *query* the existing
        // cellular network on demand (uplinkNetwork), so an idle SOCKS proxy touches nothing.
        startSocks(bindIp)
        startDns(bindIp)
        log("[socks] SOCKS5 :$SOCKS_PORT + DNS :$DNS_PORT up on $bindIp (uplink via cellular) — dash internet path, like the vendor")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socksServer?.close() } catch (_: Exception) {}
        try { dnsSocket?.close() } catch (_: Exception) {}
        socksServer = null
        dnsSocket = null
        try { netCb?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCb = null
        uplink = null
        log("[socks] stopped")
    }

    /** Hold a handle to the cellular network so proxied sockets bypass the internet-less p2p route. */
    private fun requestCellularUplink() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { uplink = network }
            override fun onLost(network: Network) { if (uplink == network) uplink = null }
        }
        netCb = cb
        try {
            cm?.requestNetwork(req, cb)
        } catch (e: Exception) {
            log("[socks] cellular request failed: $e")
        }
    }

    /** A network with an internet uplink; the requested cellular one, else any internet-capable net. */
    private fun uplinkNetwork(): Network? {
        uplink?.let { return it }
        val c = cm ?: return null
        return try {
            c.allNetworks.firstOrNull { n ->
                val caps = c.getNetworkCapabilities(n)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (_: Exception) { null }
    }

    private fun startSocks(bindIp: String) {
        Thread {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(bindIp, SOCKS_PORT))
                socksServer = srv
                while (running.get()) {
                    val client = try { srv.accept() } catch (e: Exception) { if (running.get()) log("[socks] accept: $e"); break }
                    log("[socks] dash dialed SOCKS5 from ${client.inetAddress?.hostAddress} — it IS using the phone as its internet uplink")
                    Thread { handleSocks(client) }.apply { isDaemon = true; name = "socks5-conn"; start() }
                }
            } catch (e: Exception) {
                if (running.get()) log("[socks] SOCKS5 server error: $e")
            }
        }.apply { isDaemon = true; name = "socks5-accept"; start() }
    }

    private fun handleSocks(client: Socket) {
        try {
            client.soTimeout = 30_000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            // Greeting: ver(1) nMethods(1) methods(n). Reply no-auth.
            val ver = cin.read()
            if (ver != 0x05) { client.close(); return }
            val n = cin.read()
            if (n < 0) { client.close(); return }
            val skip = ByteArray(n); readFully(cin, skip)
            cout.write(byteArrayOf(0x05, 0x00)); cout.flush()
            // Request: ver(1) cmd(1) rsv(1) atyp(1) addr port(2)
            val head = ByteArray(4); if (!readFully(cin, head)) { client.close(); return }
            if (head[0].toInt() != 0x05) { client.close(); return }
            val cmd = head[1].toInt()
            val host: String = when (head[3].toInt()) {
                0x01 -> { val a = ByteArray(4); readFully(cin, a); InetAddress.getByAddress(a).hostAddress ?: "" }
                0x03 -> { val len = cin.read(); val d = ByteArray(len); readFully(cin, d); String(d) }
                0x04 -> { val a = ByteArray(16); readFully(cin, a); InetAddress.getByAddress(a).hostAddress ?: "" }
                else -> { reply(cout, 0x08); client.close(); return }
            }
            val pb = ByteArray(2); readFully(cin, pb)
            val port = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)
            if (cmd != 0x01) { reply(cout, 0x07); client.close(); return }  // only CONNECT
            log("[socks] dash → CONNECT $host:$port (routing via cellular)")

            val net = uplinkNetwork()
            val target = try {
                val addr = if (net != null) net.getAllByName(host).firstOrNull() else InetAddress.getByName(host)
                    ?: throw java.net.UnknownHostException(host)
                Socket().also { s -> net?.bindSocket(s); s.connect(InetSocketAddress(addr, port), 15_000) }
            } catch (e: Exception) {
                reply(cout, 0x05); client.close(); return  // connection refused
            }
            reply(cout, 0x00)
            relay(client, target)
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** SOCKS5 reply with the given status and a zeroed BND.ADDR/PORT (IPv4). */
    private fun reply(out: java.io.OutputStream, status: Int) {
        try { out.write(byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush() } catch (_: Exception) {}
    }

    private fun relay(a: Socket, b: Socket) {
        val t1 = Thread { pump(a, b) }.apply { isDaemon = true; start() }
        pump(b, a)
        try { t1.join(1000) } catch (_: Exception) {}
        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(16 * 1024)
            val i = from.getInputStream(); val o = to.getOutputStream()
            while (true) {
                val r = i.read(buf); if (r < 0) break
                o.write(buf, 0, r); o.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { to.shutdownOutput() } catch (_: Exception) {}
        }
    }

    private fun startDns(bindIp: String) {
        Thread {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(bindIp, DNS_PORT))
                dnsSocket = sock
                val buf = ByteArray(2048)
                while (running.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (e: Exception) { if (running.get()) log("[socks] dns recv: $e"); break }
                    val query = pkt.data.copyOf(pkt.length)
                    val from = pkt.socketAddress
                    Thread { forwardDns(sock, query, from) }.apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                if (running.get()) log("[socks] DNS server error: $e")
            }
        }.apply { isDaemon = true; name = "dns-relay"; start() }
    }

    private fun forwardDns(server: DatagramSocket, query: ByteArray, from: java.net.SocketAddress) {
        try {
            val net = uplinkNetwork()
            val up = DatagramSocket()
            net?.bindSocket(up)
            up.soTimeout = 5_000
            up.send(DatagramPacket(query, query.size, InetAddress.getByName(UPSTREAM_DNS), 53))
            val resp = ByteArray(2048)
            val rp = DatagramPacket(resp, resp.size)
            up.receive(rp)
            up.close()
            server.send(DatagramPacket(resp, rp.length, from))
        } catch (_: Exception) {
        }
    }

    private fun readFully(ins: java.io.InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val r = try { ins.read(b, off, b.size - off) } catch (_: Exception) { return false }
            if (r < 0) return false
            off += r
        }
        return true
    }
}
