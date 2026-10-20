package com.airwatch.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class Phase { STOPPED, NETWORK, DISCOVERING, CONNECTING, PAIRING, CONNECTED, ERROR }

data class LinkState(val phase: Phase = Phase.STOPPED, val detail: String = "")

/** Errores que no se arreglan reintentando (p. ej. el PC no reconoce al reloj). */
class FatalLinkException(message: String) : Exception(message)

private data class Endpoint(val host: String, val port: Int = 8765, val udp: Int = 8766)

/**
 * Transporte Wi-Fi: WebSocket cifrado (control) + UDP cifrado (movimiento).
 *
 *  - requestNetwork(TRANSPORT_WIFI): obliga a Android a mantener Wi-Fi activo y a usarlo, para que el
 *    trafico no se enrute por el Bluetooth del telefono (proxy).
 *  - WifiLock FULL_LOW_LATENCY: desactiva el ahorro de energia del radio Wi-Fi.
 *    Android solo lo aplica con la app en primer plano y la pantalla encendida.
 *  - Sockets del WS (OkHttp) y de UDP enlazados a esa red concreta.
 */
class WifiLink(
    private val app: Context,
    private val scope: CoroutineScope,
    private val store: PairingStore,
    private val prefs: Prefs,
) {
    val state = MutableStateFlow(LinkState())
    val latencyMs = MutableStateFlow<Int?>(null)
    val pairingSas = MutableStateFlow<String?>(null)

    /** Mensajes de control del PC (perfil, diapositiva, avisos). Se llama desde un hilo de fondo. */
    @Volatile var onServerMessage: ((JSONObject) -> Unit)? = null

    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wm = app.applicationContext.getSystemService(WifiManager::class.java)

    private var job: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var pairingDecision: CompletableDeferred<Boolean>? = null
    @Volatile private var live: LiveSession? = null
    private var rttEma = 0f

    private class LiveSession(val key: ByteArray, val sid: Int, val conn: WsConn, val socket: DatagramSocket) {
        val txCtr = AtomicLong(0)
        val rxReplay = ReplayWindow()
    }

    // ================================================================== API publica
    fun start(pair: Boolean = false, hostOverride: String? = null) {
        stop()
        job = scope.launch(Dispatchers.IO) { supervise(pair, hostOverride) }
    }

    fun stop() {
        job?.cancel(); job = null
        live?.let { closeLive(it) }; live = null
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }; netCallback = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        pairingDecision?.complete(false); pairingDecision = null
        pairingSas.value = null
        latencyMs.value = null
        state.value = LinkState()
    }

    fun confirmPairing(ok: Boolean) {
        pairingDecision?.complete(ok)
    }

    fun sendMove(dx: Int, dy: Int) {
        val ls = live ?: return
        val b = ByteBuffer.allocate(5).put(1.toByte()).putShort(dx.toShort()).putShort(dy.toShort()).array()
        sendUdp(ls, b)
    }

    fun sendCtrl(obj: JSONObject) {
        val ls = live ?: return
        ls.conn.sendBytes(sealTx(ls, obj.toString().toByteArray()))
    }

    // ================================================================== supervisor
    private suspend fun supervise(pairMode: Boolean, hostOverride: String?) {
        var pairPending = pairMode
        state.value = LinkState(Phase.NETWORK, "Activando Wi-Fi…")
        acquireWifiLock()
        val netFlow = registerNetworkCallback()
        var endpoint: Endpoint? = (hostOverride ?: prefs.lastHost)?.let { parseHost(it) }
        var failures = 0

        while (currentCoroutineContext().isActive) {
            val network = withTimeoutOrNull(15_000) { netFlow.filterNotNull().first() }
            if (network == null) {
                state.value = LinkState(Phase.ERROR, "Wi-Fi no disponible. ¿El reloj está en la red?")
                delay(2000); continue
            }
            if (endpoint == null) {
                state.value = LinkState(Phase.DISCOVERING, "Buscando PC…")
                endpoint = discover(network, 6_000)
                if (endpoint == null) {
                    state.value = LinkState(Phase.ERROR, "No encuentro el PC. ¿Servidor abierto y firewall?")
                    delay(2000); continue
                }
            }
            try {
                if (pairPending) {
                    val ok = pair(network, endpoint)
                    pairPending = false
                    if (!ok) return          // pair() ya dejo el motivo en `state`
                }
                val paired = store.load() ?: run {
                    state.value = LinkState(Phase.ERROR, "Sin emparejar. Toca Emparejar.")
                    return
                }
                state.value = LinkState(Phase.CONNECTING, endpoint.host)
                runSession(network, endpoint, paired)   // vuelve cuando la sesion se cae
                failures = 0
                state.value = LinkState(Phase.CONNECTING, "Reconectando…")
                delay(250)                               // reintento rapido al mismo endpoint
            } catch (e: FatalLinkException) {
                state.value = LinkState(Phase.ERROR, e.message ?: "Error")
                return
            } catch (e: TimeoutCancellationException) {   // antes que CancellationException
                failures++
                state.value = LinkState(Phase.ERROR, "Tiempo de espera agotado")
                if (failures >= 2) endpoint = null
                delay(1000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sesion fallida", e)
                failures++
                state.value = LinkState(Phase.ERROR, e.message ?: "Error de conexión")
                if (failures >= 2) endpoint = null       // la IP pudo cambiar: redescubrir
                delay(1000)
            }
        }
    }

    // ================================================================== red
    private fun acquireWifiLock() {
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "airwatch:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun registerNetworkCallback(): MutableStateFlow<Network?> {
        val flow = MutableStateFlow<Network?>(null)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flow.value = network }
            override fun onLost(network: Network) { if (flow.value == network) flow.value = null }
        }
        netCallback = cb
        cm.requestNetwork(req, cb)   // se mantiene registrado mientras dure la sesion
        return flow
    }

    private fun parseHost(s: String): Endpoint? {
        val parts = s.trim().split(":")
        if (parts[0].isBlank()) return null
        return Endpoint(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 8765)
    }

    @Suppress("DEPRECATION")
    private suspend fun discover(network: Network, timeoutMs: Long): Endpoint? {
        val nsd = app.getSystemService(NsdManager::class.java)
        var listener: NsdManager.DiscoveryListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Endpoint> { cont ->
                    var resolving = false
                    val resolve = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolving = false }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            if (host == null || host.contains(':')) { resolving = false; return }  // solo IPv4
                            val udp = info.attributes["udp"]?.let { String(it).toIntOrNull() } ?: 8766
                            if (cont.isActive) cont.resume(Endpoint(host, info.port, udp))
                        }
                    }
                    val disc = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onServiceFound(info: NsdServiceInfo) {
                            if (!resolving) { resolving = true; nsd.resolveService(info, resolve) }
                        }
                    }
                    listener = disc
                    nsd.discoverServices("_airwatch._tcp.", NsdManager.PROTOCOL_DNS_SD, network, app.mainExecutor, disc)
                }
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    // ================================================================== emparejamiento
    private suspend fun pair(network: Network, ep: Endpoint): Boolean {
        state.value = LinkState(Phase.PAIRING, "Conectando…")
        val conn = WsConn(httpClient(network), "ws://${ep.host}:${ep.port}")
        try {
            withTimeout(5000) { conn.opened.await() }
            val kp = Crypto.newKeyPair()
            conn.sendText(JSONObject().put("t", "pair_req").put("pub", Crypto.b64e(kp.pubRaw))
                .put("name", Build.MODEL ?: "Reloj").toString())
            val resp = JSONObject(withTimeout(5000) { conn.texts.receive() })
            if (resp.getString("t") != "pair_resp") {
                state.value = LinkState(Phase.ERROR, "El PC no está en modo emparejar (usa --pair)")
                return false
            }
            val serverPub = Crypto.b64d(resp.getString("pub"))
            val (secret, sas) = Crypto.pairingMaterial(Crypto.ecdh(kp, serverPub), kp.pubRaw, serverPub)

            val decision = CompletableDeferred<Boolean>()
            pairingDecision = decision
            pairingSas.value = sas
            state.value = LinkState(Phase.PAIRING, "Compara el código")
            val ok = withTimeoutOrNull(120_000) { decision.await() } ?: false
            pairingSas.value = null
            pairingDecision = null
            if (!ok) {
                state.value = LinkState(Phase.ERROR, "Emparejamiento cancelado")
                return false
            }
            conn.sendText(JSONObject().put("t", "pair_confirm")
                .put("mac", Crypto.b64e(Crypto.hmac(secret, "confirm-watch".toByteArray()))).toString())
            state.value = LinkState(Phase.PAIRING, "Confirma en el PC…")
            val fin = JSONObject(withTimeout(120_000) { conn.texts.receive() })
            if (fin.getString("t") != "pair_ok" ||
                !MessageDigest.isEqual(Crypto.b64d(fin.getString("mac")),
                    Crypto.hmac(secret, "confirm-server".toByteArray()))
            ) {
                state.value = LinkState(Phase.ERROR, "El PC rechazó el emparejamiento")
                return false
            }
            store.save(Paired(Crypto.deviceId(kp.pubRaw), secret))
            prefs.lastHost = ep.host
            return true
        } finally {
            pairingSas.value = null
            conn.close()
        }
    }

    // ================================================================== sesion
    private suspend fun runSession(network: Network, ep: Endpoint, paired: Paired) = coroutineScope {
        val conn = WsConn(httpClient(network), "ws://${ep.host}:${ep.port}")
        var socket: DatagramSocket? = null
        var ls: LiveSession? = null
        try {
            withTimeout(5000) { conn.opened.await() }
            val cn = Crypto.randomBytes(16)
            conn.sendText(JSONObject().put("t", "hello").put("dev", paired.devId).put("cn", Crypto.b64e(cn)).toString())
            val r = JSONObject(withTimeout(4000) { conn.texts.receive() })
            if (r.getString("t") != "hello_ok") {
                throw FatalLinkException("El PC no reconoce este reloj. Vuelve a emparejar.")
            }
            val sn = Crypto.b64d(r.getString("sn"))
            if (!MessageDigest.isEqual(Crypto.b64d(r.getString("proof")), Crypto.serverProof(paired.secret, cn, sn))) {
                throw FatalLinkException("El PC no pasó la verificación. Vuelve a emparejar.")
            }
            val (key, sid) = Crypto.sessionKeys(paired.secret, cn, sn)

            socket = DatagramSocket().also {
                network.bindSocket(it)                       // UDP por Wi-Fi, nunca por Bluetooth
                it.connect(InetSocketAddress(ep.host, r.optInt("udp", ep.udp)))
            }
            val session = LiveSession(key, sid, conn, socket)
            ls = session

            val info = openServer(session, withTimeout(4000) { conn.bins.receive() })
                ?: throw IOException("Trama de información inválida")
            val pcName = JSONObject(String(info)).optString("name", ep.host)

            live = session
            prefs.lastHost = ep.host
            state.value = LinkState(Phase.CONNECTED, pcName)

            launch { rxLoop(session) }
            launch(Dispatchers.IO) { udpRxLoop(session) }
            launch { pingLoop(session) }

            conn.closed.await()
            closeLive(session)                      // desbloquea el receive() de UDP
            coroutineContext.cancelChildren()
        } finally {
            if (live === ls) live = null
            latencyMs.value = null
            if (ls != null) closeLive(ls) else { socket?.close(); conn.close() }
        }
    }

    private fun httpClient(network: Network) = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)      // el WebSocket sale por la red Wi-Fi elegida
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private fun closeLive(ls: LiveSession) {
        runCatching { ls.socket.close() }
        ls.conn.close()
    }

    private fun sealTx(ls: LiveSession, plain: ByteArray) = Crypto.seal(ls.key, ls.sid, ls.txCtr.incrementAndGet(), plain)

    private fun openServer(ls: LiveSession, frame: ByteArray, length: Int = frame.size): ByteArray? {
        val o = Crypto.open(ls.key, frame, length) ?: return null
        if (o.sid != (ls.sid xor Crypto.SERVER_SID_MASK)) return null
        if (!ls.rxReplay.checkAndUpdate(o.ctr)) return null
        return o.plain
    }

    private fun sendUdp(ls: LiveSession, plain: ByteArray) {
        try {
            val f = sealTx(ls, plain)
            ls.socket.send(DatagramPacket(f, f.size))
        } catch (_: Exception) {
            // un paquete perdido no importa; la reconexion la maneja el WebSocket
        }
    }

    private suspend fun rxLoop(ls: LiveSession) {
        for (frame in ls.conn.bins) {
            val plain = openServer(ls, frame) ?: continue
            try {
                onServerMessage?.invoke(JSONObject(String(plain)))
            } catch (_: Exception) {
                // un mensaje mal formado del PC no debe tumbar la sesion
            }
        }
    }

    private fun udpRxLoop(ls: LiveSession) {
        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)
        while (true) {
            pkt.length = buf.size
            try { ls.socket.receive(pkt) } catch (_: Exception) { return }
            val plain = openServer(ls, buf, pkt.length) ?: continue
            if (plain.size >= 9 && plain[0].toInt() == 3) {
                val t0 = ByteBuffer.wrap(plain, 1, 8).long
                val rtt = (System.nanoTime() - t0) / 1e6f
                rttEma = if (rttEma == 0f) rtt else rttEma * 0.7f + rtt * 0.3f
                latencyMs.value = rttEma.toInt()
            }
        }
    }

    private suspend fun pingLoop(ls: LiveSession) {
        delay(200)
        while (currentCoroutineContext().isActive) {
            sendUdp(ls, ByteBuffer.allocate(9).put(2.toByte()).putLong(System.nanoTime()).array())
            delay(1000)
        }
    }

    // ================================================================== WebSocket con canales
    private class WsConn(client: OkHttpClient, url: String) {
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val texts = Channel<String>(Channel.UNLIMITED)
        val bins = Channel<ByteArray>(Channel.UNLIMITED)

        private val ws: WebSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { texts.trySend(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { bins.trySend(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish(t) }
        })

        private fun finish(t: Throwable?) {
            if (t != null) opened.completeExceptionally(t)
            closed.complete(Unit)
            texts.close(); bins.close()
        }

        fun sendText(s: String) { ws.send(s) }
        fun sendBytes(b: ByteArray) { ws.send(b.toByteString()) }
        fun close() { runCatching { ws.close(1000, null) } }
    }

    private companion object {
        const val TAG = "AirWatch"
    }
}
