package com.randallengineering.sleepasringconn.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.randallengineering.sleepasringconn.protocol.RingProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One GATT connection to the ring, as a coroutine API.
 *
 * Android allows a single outstanding GATT operation per connection; a write issued while another
 * operation is in flight is rejected and silently lost. Every operation here goes through [opLock]
 * and waits for its completion callback before the next one starts (same approach as RingLink and
 * RingConn-Gen-2-Android). Notifications are queued in arrival order on [incoming] so the binder
 * thread never blocks.
 */
@SuppressLint("MissingPermission")
class GattTransport(private val context: Context, val device: BluetoothDevice) {

    /** Raw notify frames, in arrival order. */
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    private val _linkUp = MutableStateFlow(false)
    /** True between STATE_CONNECTED and STATE_DISCONNECTED. */
    val linkUp: StateFlow<Boolean> = _linkUp.asStateFlow()

    @Volatile var mtu: Int = 23
        private set

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()

    private class OpResult(val ok: Boolean, val value: ByteArray? = null, val status: Int = 0)

    @Volatile private var pending: CompletableDeferred<OpResult>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val up = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            _linkUp.value = up
            connectWaiter?.complete(up)
            if (!up) pending?.complete(OpResult(false, status = status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(OpResult(status == BluetoothGatt.GATT_SUCCESS, status = status))
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            pending?.complete(OpResult(status == BluetoothGatt.GATT_SUCCESS, status = status))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(OpResult(status == BluetoothGatt.GATT_SUCCESS, value.copyOf(), status))
        }

        @Deprecated("Pre-API 33 path")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pending?.complete(OpResult(status == BluetoothGatt.GATT_SUCCESS, c.value?.copyOf(), status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(OpResult(status == BluetoothGatt.GATT_SUCCESS, status = status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(OpResult(status == BluetoothGatt.GATT_SUCCESS, status = status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == RingProtocol.NOTIFY_CHAR_UUID) incoming.trySend(value.copyOf())
        }

        @Deprecated("Pre-API 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            if (c.uuid == RingProtocol.NOTIFY_CHAR_UUID) incoming.trySend(v.copyOf())
        }
    }

    /** Runs one GATT operation; [start] returns true if the stack accepted the request. */
    private suspend fun op(timeoutMs: Long = OP_TIMEOUT_MS, start: (BluetoothGatt) -> Boolean): OpResult =
        opLock.withLock {
            val g = gatt ?: return@withLock OpResult(false)
            val d = CompletableDeferred<OpResult>()
            pending = d
            val accepted = try { start(g) } catch (e: Exception) { false }
            val r = if (accepted) withTimeoutOrNull(timeoutMs) { d.await() } else null
            pending = null
            r ?: OpResult(false)
        }

    /**
     * Connects. A direct connect (autoConnect=false) is fast but needs the ring's advertising
     * window; [autoConnect]=true parks the address on the controller allow-list and waits as long
     * as it takes, which is what keeps an overnight link coming back on its own.
     */
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        connectWaiter = waiter
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) return false
        val ok = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        connectWaiter = null
        return ok
    }

    suspend fun discoverServices(): Boolean = op(15_000) { it.discoverServices() }.ok

    suspend fun requestMtu(size: Int): Int {
        op { it.requestMtu(size) }
        return mtu
    }

    fun requestHighPriority() {
        try { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
    }

    fun requestBalancedPriority() {
        try { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) } catch (_: Exception) {}
    }

    fun hasRingService(): Boolean {
        val s = gatt?.getService(RingProtocol.DATA_SERVICE_UUID) ?: return false
        return s.getCharacteristic(RingProtocol.NOTIFY_CHAR_UUID) != null &&
            s.getCharacteristic(RingProtocol.WRITE_CHAR_UUID) != null
    }

    suspend fun read(service: UUID, ch: UUID): ByteArray? {
        val c = gatt?.getService(service)?.getCharacteristic(ch) ?: return null
        val r = op { it.readCharacteristic(c) }
        return if (r.ok) r.value else null
    }

    suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val c = g.getService(RingProtocol.DATA_SERVICE_UUID)?.getCharacteristic(RingProtocol.NOTIFY_CHAR_UUID) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(RingProtocol.CCCD_UUID) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return op { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = v
                @Suppress("DEPRECATION")
                gg.writeDescriptor(d)
            }
        }.ok
    }

    /**
     * Writes one command and waits for the stack to confirm it. Uses write-with-response when the
     * characteristic supports it (confirmed on Gen 2 by OpenCircuit), so a lost command shows up
     * as a failed write instead of vanishing.
     */
    suspend fun write(value: ByteArray): Boolean {
        val c = gatt?.getService(RingProtocol.DATA_SERVICE_UUID)?.getCharacteristic(RingProtocol.WRITE_CHAR_UUID)
            ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return op(WRITE_TIMEOUT_MS) { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }.ok
    }

    val isBonded: Boolean get() = device.bondState == BluetoothDevice.BOND_BONDED

    /**
     * Creates an LE bond (Just Works) if none exists. Per OpenCircuit, the ring answers the auth
     * handshake on an unbonded link but silently drops data commands. A bond made by the official
     * app counts: bonds belong to the phone, not to an app.
     */
    suspend fun ensureBond(timeoutMs: Long = 30_000): Boolean {
        if (isBonded) return true
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (dev?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        return try {
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                false
            } else {
                withTimeoutOrNull(timeoutMs) { done.await() } ?: isBonded
            }
        } catch (e: Exception) {
            false
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    fun close() {
        val g = gatt ?: return
        gatt = null
        _linkUp.value = false
        pending?.complete(OpResult(false))
        connectWaiter?.complete(false)
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
        incoming.close()
    }

    companion object {
        private const val OP_TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 4_000L
        val SYSTEM_ID_UUID: UUID = RingProtocol.SYSTEM_ID_CHAR_UUID
        val DEVICE_INFO_UUID: UUID = RingProtocol.DEVICE_INFO_SERVICE_UUID
        val FIRMWARE_UUID: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    }
}
