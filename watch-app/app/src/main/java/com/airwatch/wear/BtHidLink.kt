package com.airwatch.wear

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Transporte Bluetooth HID: el reloj se anuncia como mouse + teclado Bluetooth (API BluetoothHidDevice) y la PC
 * lo ve como un dispositivo normal, sin software en la PC. Traduce los mismos mensajes JSON que usa el
 * transporte Wi-Fi (btn, scroll, key, pres) a informes HID. No hay laser, marcador, notas ni perfiles por
 * aplicacion: eso necesita la app del PC (Wi-Fi).
 *
 * Emparejar (una vez): Ajustes del reloj -> "Emparejar Bluetooth" -> en Windows: Configuracion -> Bluetooth ->
 * Agregar dispositivo -> "AirWatch". Despues el reloj se reconecta solo al ultimo PC.
 */
@SuppressLint("MissingPermission")
class BtHidLink(private val app: Context, private val prefs: Prefs) {
    val state = MutableStateFlow(LinkState())

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "airwatch-bt").apply { isDaemon = true } }
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var wanted = false
    @Volatile private var buttons = 0

    val connected: Boolean get() = host != null

    // ================================================================== API publica
    fun start() {
        stop()
        val a = adapter
        if (a == null || !a.isEnabled) { state.value = LinkState(Phase.ERROR, "Bluetooth apagado"); return }
        if (!hasPermission(app)) { state.value = LinkState(Phase.ERROR, "Falta el permiso de Bluetooth"); return }
        wanted = true
        state.value = LinkState(Phase.DISCOVERING, "Esperando al PC (Bluetooth)")
        if (!a.getProfileProxy(app, serviceListener, BluetoothProfile.HID_DEVICE)) {
            state.value = LinkState(Phase.ERROR, "Este reloj no ofrece modo ratón Bluetooth")
        }
    }

    fun stop() {
        wanted = false
        val h = hid
        if (h != null) {
            runCatching { host?.let { h.disconnect(it) } }
            runCatching { h.unregisterApp() }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        }
        hid = null; host = null; registered = false; buttons = 0
        state.value = LinkState()
    }

    /** Intenta reconectar con el ultimo PC emparejado. */
    fun reconnect() {
        val h = hid ?: return
        if (!registered || host != null) return
        val addr = prefs.btHost ?: return
        runCatching { adapter?.getRemoteDevice(addr)?.let { h.connect(it) } }
    }

    fun sendMove(dx: Int, dy: Int) {
        if (host == null) return
        io.execute { for (s in HidKeys.splitMove(dx, dy)) report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons, s[0], s[1])) }
    }

    /** Mismos mensajes JSON que el transporte Wi-Fi; los que no tienen equivalente HID se ignoran. */
    fun sendCtrl(o: JSONObject) {
        if (host == null) return
        io.execute { runCatching { handle(o) } }
    }

    // ================================================================== traduccion JSON -> HID
    private fun handle(o: JSONObject) {
        when (o.optString("t")) {
            "btn" -> {
                val mask = HidKeys.buttonMask(o.optString("b"))
                if (mask == 0) return
                o.optJSONArray("adj")?.let { adj ->          // deshacer el sacudon del gesto antes de pulsar
                    for (s in HidKeys.splitMove(adj.optInt(0), adj.optInt(1))) report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons, s[0], s[1]))
                }
                when (o.optString("s")) {
                    "down" -> { buttons = buttons or mask; report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons, 0, 0)) }
                    "up" -> { buttons = buttons and mask.inv(); report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons, 0, 0)) }
                    "click" -> click(mask)
                    "double" -> { click(mask); Thread.sleep(40); click(mask) }
                }
            }
            "scroll" -> report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons, 0, 0, o.optInt("v")))
            "key" -> {
                val arr = o.optJSONArray("k") ?: return
                tap((0 until arr.length()).map { arr.optString(it) })
            }
            "pres" -> {
                val n = if (o.has("n") && !o.isNull("n")) o.optInt("n") else null
                HidKeys.presSequence(o.optString("a"), n)?.forEach { tap(it); Thread.sleep(30) }
            }
        }
    }

    private fun click(mask: Int) {
        report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons or mask, 0, 0))
        Thread.sleep(12)
        report(HidKeys.REPORT_MOUSE, HidKeys.mouseReport(buttons and mask.inv(), 0, 0))
    }

    private fun tap(keys: List<String>) {
        val r = HidKeys.keyboardReport(keys) ?: return
        report(HidKeys.REPORT_KEYBOARD, r)
        Thread.sleep(15)
        report(HidKeys.REPORT_KEYBOARD, HidKeys.RELEASE_ALL)
    }

    private fun report(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        runCatching { h.sendReport(d, id, data) }
    }

    // ================================================================== Android
    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            if (!wanted) { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h); return }
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "AirWatch", "Air mouse", "AirWatch", BluetoothHidDevice.SUBCLASS1_COMBO, HidKeys.DESCRIPTOR,
            )
            if (!h.registerApp(sdp, null, null, io, callback)) {
                state.value = LinkState(Phase.ERROR, "El reloj no permite el modo ratón Bluetooth")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null; host = null; registered = false
            if (wanted) state.value = LinkState(Phase.ERROR, "Servicio Bluetooth detenido")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BtHidLink.registered = registered
            if (registered) reconnect()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, st: Int) {
            when (st) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.btHost = device.address
                    buttons = 0
                    state.value = LinkState(Phase.CONNECTED, device.name ?: "PC")
                }
                BluetoothProfile.STATE_CONNECTING -> state.value = LinkState(Phase.CONNECTING, "Conectando…")
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    if (wanted) state.value = LinkState(Phase.DISCOVERING, "Esperando al PC (Bluetooth)")
                }
            }
        }
    }

    companion object {
        fun hasPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** Hace visible al reloj 2 minutos para que Windows lo encuentre al agregar un dispositivo Bluetooth. */
        fun requestDiscoverable(ctx: Context) {
            ctx.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
