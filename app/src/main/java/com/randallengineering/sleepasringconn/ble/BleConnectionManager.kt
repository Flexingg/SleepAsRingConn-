package com.randallengineering.sleepasringconn.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import com.randallengineering.sleepasringconn.data.EpochEntity
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.DeviceStatus
import com.randallengineering.sleepasringconn.protocol.LiveHrSettler
import com.randallengineering.sleepasringconn.protocol.LiveSample
import com.randallengineering.sleepasringconn.protocol.RingAuth
import com.randallengineering.sleepasringconn.protocol.RingProtocol
import com.randallengineering.sleepasringconn.protocol.SportRecord
import com.randallengineering.sleepasringconn.sensor.MotionSensorManager
import com.randallengineering.sleepasringconn.sleepasandroid.ActigraphTimeline
import com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the connection to the ring.
 *
 * Structure (after RingLink and RingConn-Gen-2-Android):
 *  - [GattTransport] serialises every GATT operation and confirms each write.
 *  - One session coroutine per connection: connect, discover, bond, MTU, read System ID, enable
 *    notifications, authenticate (and verify the ring accepted it).
 *  - One dispatcher coroutine decodes every notification in arrival order.
 *  - One supervisor coroutine owns the link's "mode": history drain, live HR, one-shot SpO2 or
 *    idle. Because only it switches modes, a sync can no longer kill live HR half-way through a
 *    poll, and live HR comes back by itself after every drain.
 */
@SuppressLint("MissingPermission")
object BleConnectionManager {
    private const val TAG = "BleConnectionManager"
    private const val PREFS = "ringconn_prefs"
    private const val PREF_KEY_MAC = "paired_ring_mac"

    /** Ring answers a poll about every 2 s; polling faster resets its HR window. */
    private const val LIVE_POLL_MS = 2_000L
    /** No 0x15 frame for this long while live: the ring left live mode, re-enter it. */
    /**
     * The ring ends a live session on its own after ~30-40 s (like the vendor's "measure now").
     * Once it has been answering, 6 s of silence (3 missed polls) means the session is over.
     */
    private const val LIVE_STALL_MS = 6_000L
    /** Grace after entering live mode before silence counts as a stall. */
    private const val LIVE_ENTRY_GRACE_MS = 15_000L
    private const val PREF_SAA_LIVE_HR = "saa_live_hr_during_tracking"
    private const val LIVE_HR_STALE_MS = 60_000L
    private const val IDLE_FETCH_MS = 60_000L
    /** Sleep-channel drain cadence while Sleep as Android is tracking. */
    private const val TRACKING_DRAIN_MS = 90_000L
    /** While SaA shows its live graph (it sets batch size 1), drain more often. */
    private const val TRACKING_DRAIN_VISIBLE_MS = 30_000L
    /** Background full drain cadence otherwise. */
    private const val BACKGROUND_DRAIN_MS = 30 * 60_000L

    private var appContext: Context? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ------------------------------------------------------------------ public state

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    /** GATT link is up (may still be authenticating). */
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Link is up and the ring accepted authentication: data commands will be honoured. */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _latestDeviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val latestDeviceStatus: StateFlow<DeviceStatus?> = _latestDeviceStatus.asStateFlow()

    private val _liveHeartRate = MutableStateFlow<Int?>(null)
    val liveHeartRate: StateFlow<Int?> = _liveHeartRate.asStateFlow()

    private val _liveSpo2 = MutableStateFlow<Int?>(null)
    val liveSpo2: StateFlow<Int?> = _liveSpo2.asStateFlow()

    private val _liveHrv = MutableStateFlow<Int?>(null)
    val liveHrv: StateFlow<Int?> = _liveHrv.asStateFlow()

    /** Live measurement is wanted (by the dashboard or by Sleep as Android tracking). */
    private val _isLiveMonitoring = MutableStateFlow(false)
    val isLiveMonitoring: StateFlow<Boolean> = _isLiveMonitoring.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * Whether live HR runs during Sleep as Android tracking. Off by default: continuous live mode
     * keeps the ring's optical sensor busy, and its own 2.5-minute epochs then carry no HR, HRV or
     * SpO2 (seen on FR02.018: every epoch recorded during live mode was empty).
     */
    private val _liveHrDuringTracking = MutableStateFlow(false)
    val liveHrDuringTracking: StateFlow<Boolean> = _liveHrDuringTracking.asStateFlow()

    private val _isRingLedOn = MutableStateFlow(false)
    val isRingLedOn: StateFlow<Boolean> = _isRingLedOn.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    // ------------------------------------------------------------------ session state

    @Volatile private var targetAddress: String? = null
    private var connectionJob: Job? = null
    @Volatile private var transport: GattTransport? = null
    @Volatile private var commandQueue: Channel<ByteArray>? = null

    // Mode requests, read by the supervisor.
    @Volatile private var userLiveWanted = false
    @Volatile private var liveSpo2Mode = false
    @Volatile private var sleepTracking = false
    @Volatile private var fullSyncRequested = true
    @Volatile private var quickDrainRequested = false
    @Volatile private var spo2Request: CompletableDeferred<Int?>? = null

    // Auth handshake hand-off from the dispatcher.
    @Volatile private var authChallenge: CompletableDeferred<Byte>? = null
    @Volatile private var authConfirm: CompletableDeferred<Unit>? = null

    // History drain hand-off from the dispatcher.
    @Volatile private var syncInbox: Channel<ByteArray>? = null
    /** History pages the ring pushes outside a drain (e.g. in live mode); saved, then acked. */
    @Volatile private var strayPages: Channel<ByteArray>? = null

    private val liveSettler = LiveHrSettler()
    @Volatile private var lastLiveFrameAt = 0L
    @Volatile private var lastLiveHrAt = 0L
    @Volatile private var lastLiveSpo2At = 0L
    private var lastReportedRingSteps: Int? = null
    @Volatile private var lastFullDrainAt = 0L
    private val rxCounts = IntArray(256)
    private var lastHrLogAt = 0L
    private var lastWarmupLogAt = 0L
    private var lastStatusLogAt = 0L
    private var lastWriteFailLogAt = 0L
    private var descriptorRejectLogged = false

    // ------------------------------------------------------------------ init / scan

    fun init(context: Context) {
        appContext = context.applicationContext
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        registerBluetoothStateReceiver(context.applicationContext)
        _liveHrDuringTracking.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_SAA_LIVE_HR, false)
        ensureConnected()

        // Seed the dashboard with the latest stored vitals until live values arrive.
        scope.launch {
            try {
                val recent = AppDatabase.getDatabase(context).epochDao().getRecentEpochsList(50)
                if (_liveHeartRate.value == null) recent.firstOrNull { it.heartRate != null }?.heartRate?.let { _liveHeartRate.value = it }
                if (_liveSpo2.value == null) recent.firstOrNull { it.spo2Percent != null }?.spo2Percent?.let { _liveSpo2.value = it }
                if (_liveHrv.value == null) recent.firstOrNull { it.hrvRmssd != null }?.hrvRmssd?.let { _liveHrv.value = it }
            } catch (e: Exception) {
                Log.w(TAG, "Preloading vitals failed", e)
            }
        }
    }

    /** Lets other components (e.g. the Sleep as Android bridge) write to the Diagnostics log. */
    fun logEvent(msg: String) = log(msg)

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _recentLogs.value = _recentLogs.value.takeLast(199) + "[$ts] $msg"
    }

    private var isReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                log("Bluetooth turned on")
                ensureConnected()
            }
        }
    }

    private fun registerBluetoothStateReceiver(context: Context) {
        if (isReceiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                context, bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth state receiver registration failed", e)
        }
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run { log("BLE scanner not available"); return }
        _discoveredDevices.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("Scanning for RingConn devices...")
        try {
            scanner.startScan(listOf(ScanFilter.Builder().build()), settings, scanCallback)
        } catch (e: Exception) {
            log("Scan failed to start: ${e.message}")
        }
    }

    fun stopScan() {
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            if (name.isEmpty()) return
            val current = _discoveredDevices.value.toMutableList()
            val i = current.indexOfFirst { it.address == device.address }
            if (i >= 0) current[i] = device else {
                current.add(device)
                if (name.contains("RingConn", ignoreCase = true)) log("Found $name (${device.address})")
            }
            _discoveredDevices.value = current.sortedWith(
                compareByDescending<BluetoothDevice> { (it.name ?: "").contains("RingConn", ignoreCase = true) }
                    .thenBy { it.name ?: it.address }
            )
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE scan failed: $errorCode")
        }
    }

    // ------------------------------------------------------------------ connection lifecycle

    fun connect(device: BluetoothDevice) {
        stopScan()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_KEY_MAC, device.address)?.apply()
        startConnectionLoop(device.address, force = true)
    }

    /** Connects to the saved ring if no connection (or reconnect loop) is running. */
    fun ensureConnected() {
        val mac = targetAddress
            ?: appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(PREF_KEY_MAC, null)
            ?: return
        startConnectionLoop(mac, force = false)
    }

    private fun startConnectionLoop(address: String, force: Boolean) {
        if (!force && targetAddress == address && connectionJob?.isActive == true) return
        val previous = connectionJob
        targetAddress = address
        connectionJob = scope.launch {
            previous?.cancelAndJoin()
            var failures = 0
            while (isActive && targetAddress == address) {
                val adapter = bluetoothAdapter
                if (adapter == null || !adapter.isEnabled) {
                    _connectionState.value = "Bluetooth off"
                    delay(10_000)
                    continue
                }
                val device = try { adapter.getRemoteDevice(address) } catch (e: Exception) { null }
                if (device == null) { delay(10_000); continue }
                // First a direct connect (fast, bounded). If the ring missed it, alternate with a
                // background connect, which waits on the controller for the next advertisement.
                val background = failures % 2 == 1
                val reachedReady = runSession(device, background)
                failures = if (reachedReady) 0 else failures + 1
                if (isActive && targetAddress == address) {
                    delay(if (reachedReady) 1_000L else minOf(2_000L * failures, 15_000L))
                }
            }
        }
    }

    fun disconnect() {
        log("Disconnecting by user request")
        targetAddress = null
        val job = connectionJob
        connectionJob = null
        scope.launch {
            job?.cancelAndJoin()
            transport?.close()
            transport = null
            resetLinkState("Disconnected")
        }
    }

    private fun resetLinkState(state: String) {
        _isConnected.value = false
        _isReady.value = false
        _isSyncing.value = false
        _isRingLedOn.value = false
        _connectionState.value = state
        commandQueue?.close()
        commandQueue = null
        syncInbox = null
        lastLiveHrAt = 0L
        lastLiveFrameAt = 0L
    }

    /**
     * One connection, start to finish. Returns true if it got as far as an authenticated link.
     */
    private suspend fun runSession(device: BluetoothDevice, background: Boolean): Boolean {
        val ctx = appContext ?: return false
        val t = GattTransport(ctx, device)
        transport = t
        var reachedReady = false
        val name = device.name ?: device.address
        try {
            _connectionState.value = if (background) "Waiting for $name..." else "Connecting to $name..."
            log(if (background) "Background connect armed for ${device.address}" else "Connecting to ${device.address}")
            if (!t.connect(autoConnect = background, timeoutMs = if (background) 10 * 60_000L else 30_000L)) {
                log("Connect attempt did not complete")
                return false
            }
            _isConnected.value = true
            _connectionState.value = "Connected. Configuring..."

            if (!t.discoverServices() && !t.discoverServices()) { log("Service discovery failed"); return false }
            if (!t.hasRingService()) { log("RingConn data service not found"); return false }

            if (!t.isBonded) {
                _connectionState.value = "Bonding..."
                log("No LE bond yet: requesting one (the ring drops data commands on unbonded links)")
                log(if (t.ensureBond()) "Bonded" else "WARNING: bonding failed; data may be ignored by the ring")
            }

            val mtu = t.requestMtu(512)
            t.requestHighPriority()
            log("MTU $mtu, bonded ${t.isBonded}, ${t.writeModeDescription()}")

            val systemId = t.read(GattTransport.DEVICE_INFO_UUID, GattTransport.SYSTEM_ID_UUID)
            t.read(GattTransport.DEVICE_INFO_UUID, GattTransport.FIRMWARE_UUID)
                ?.toString(Charsets.UTF_8)?.trim('\u0000', ' ')?.let { log("Firmware $it") }

            if (!t.enableNotifications()) { log("Enabling notifications failed"); return false }

            val queue = Channel<ByteArray>(Channel.UNLIMITED)
            commandQueue = queue
            val dispatcher = scope.launch {
                for (f in t.incoming) {
                    // One bad frame must never stop the dispatcher: everything else depends on it.
                    try { handleFrame(f) } catch (e: Exception) {
                        Log.e(TAG, "Frame handler failed", e)
                        log("Frame handler error on ${f.hex()}: ${e.javaClass.simpleName} ${e.message}")
                    }
                }
            }
            val writer = scope.launch { for (cmd in queue) send(t, cmd) }
            val stray = Channel<ByteArray>(Channel.UNLIMITED)
            strayPages = stray
            val strayWorker = scope.launch {
                // The ring pushes pages during live mode and waits for each ACK; leaving them
                // unacknowledged stalls its live measurement. Save first, then ACK.
                for (f in stray) {
                    try {
                        when (f[0].toInt() and 0xFF) {
                            0x4C -> {
                                val records = BulkRecord.parsePage(f)
                                if (records.isNotEmpty()) {
                                    persistEpochs(records, RingProtocol.CHANNEL_SLEEP.toInt())
                                    onEpochsPersisted(records)
                                }
                                send(t, RingProtocol.CMD_PAGE_ACK_4C)
                            }
                            0x47 -> send(t, RingProtocol.CMD_PAGE_ACK_47)
                            0x4D -> send(t, RingProtocol.CMD_PAGE_ACK_4D)
                        }
                    } catch (e: Exception) {
                        log("Stray page handling failed: ${e.javaClass.simpleName} ${e.message}")
                    }
                }
            }

            try {
                _connectionState.value = "Connected & Authenticating"
                val macs = RingAuth.macCandidates(
                    RingAuth.parseMacString(device.address),
                    systemId?.let { RingAuth.macFromSystemId(it) },
                    device.name,
                )
                if (!authenticate(t, macs)) log("WARNING: ring never confirmed authentication; continuing anyway")
                _isReady.value = true
                reachedReady = true
                _connectionState.value = "Connected & Streaming"
                log("Ready")
                supervise(t)
            } finally {
                dispatcher.cancel()
                writer.cancel()
                strayWorker.cancel()
                stray.close()
                strayPages = null
            }
            return reachedReady
        } finally {
            t.close()
            if (transport === t) transport = null
            resetLinkState(if (targetAddress != null) "Reconnecting..." else "Disconnected")
            if (reachedReady) log("Link closed")
        }
    }

    /**
     * `01 00 00` -> `81 00 <chal>`; answer `01 01 r0 r1 r2 00`; the ring confirms with `81 01 ...`.
     * Tries each MAC candidate; a reply with the wrong MAC simply gets no confirmation.
     */
    private suspend fun authenticate(t: GattTransport, macs: List<ByteArray>): Boolean {
        for (mac in macs.ifEmpty { listOf(ByteArray(6)) }) {
            repeat(2) { attempt ->
                val chal = CompletableDeferred<Byte>().also { authChallenge = it }
                val confirm = CompletableDeferred<Unit>().also { authConfirm = it }
                send(t, RingProtocol.CMD_STATUS_0)
                val c = withTimeoutOrNull(4_000) { chal.await() }
                if (c == null) {
                    log("No auth challenge (attempt ${attempt + 1})")
                    return@repeat
                }
                send(t, RingAuth.createAuthCommand(c, mac))
                if (withTimeoutOrNull(3_000) { confirm.await() } != null) {
                    log("Authenticated (challenge 0x%02X)".format(c))
                    authChallenge = null; authConfirm = null
                    return true
                }
                log("Auth reply not confirmed with MAC ${mac.hex(":")}")
            }
        }
        authChallenge = null; authConfirm = null
        return false
    }

    /** Supervisor-side write; failures are logged (rate-limited) instead of silently ignored. */
    private suspend fun send(t: GattTransport, cmd: ByteArray): Boolean {
        val ok = t.write(cmd)
        if (!ok) {
            val now = System.currentTimeMillis()
            if (now - lastWriteFailLogAt > 5_000) {
                lastWriteFailLogAt = now
                log("Write failed: ${cmd.hex()}")
            }
        }
        return ok
    }

    private fun logRxSummary(windowSec: Int) {
        val parts = ArrayList<String>()
        synchronized(rxCounts) {
            for (op in 0 until 256) if (rxCounts[op] > 0) parts.add("%02X x%d".format(op, rxCounts[op]))
            rxCounts.fill(0)
        }
        log("RX last ${windowSec}s: ${if (parts.isEmpty()) "nothing" else parts.joinToString(", ")}")
    }

    // ------------------------------------------------------------------ supervisor

    private suspend fun supervise(t: GattTransport) {
        var liveActive = false
        var liveActiveSpo2 = false
        var liveEnteredAt = 0L
        var lastPoll = 0L
        var lastIdleFetch = 0L
        var lastDrain = 0L
        var lastFullDrain = 0L
        var lastRxSummary = System.currentTimeMillis()
        var lastLiveFetch = 0L

        suspend fun leaveLive() {
            if (liveActive) {
                send(t, RingProtocol.CMD_LIVE_STOP)
                liveActive = false
            }
        }

        send(t, RingProtocol.CMD_FETCH) // prompt a descriptor (battery, temp, steps)

        while (t.linkUp.value) {
            val now = System.currentTimeMillis()
            if (now - lastRxSummary >= 30_000L) {
                logRxSummary(((now - lastRxSummary) / 1000).toInt())
                lastRxSummary = now
            }

            // 1. History drains: requested, periodic, or the frequent sleep-channel drain that
            //    feeds the Sleep as Android actigraph.
            val fullDue = fullSyncRequested || now - lastFullDrain >= BACKGROUND_DRAIN_MS
            val drainEvery = if (SleepAsAndroidBridge.batchSize <= 1) TRACKING_DRAIN_VISIBLE_MS else TRACKING_DRAIN_MS
            val trackingDue = sleepTracking && (quickDrainRequested || now - lastDrain >= drainEvery)
            if (fullDue || trackingDue) {
                fullSyncRequested = false
                quickDrainRequested = false
                // Live mode is not stopped for a drain: the old, working build never did, and
                // `06 00 00` is inferred rather than captured. Polling simply pauses.
                liveActive = false
                val channels = if (fullDue) {
                    listOf(RingProtocol.CHANNEL_SLEEP, RingProtocol.CHANNEL_ALL_DAY)
                } else {
                    listOf(RingProtocol.CHANNEL_SLEEP)
                }
                runSync(t, channels)
                lastDrain = System.currentTimeMillis()
                if (fullDue) { lastFullDrain = lastDrain; lastFullDrainAt = lastDrain }
                continue
            }

            // 2. One-shot SpO2 measurement.
            val req = spo2Request
            if (req != null) {
                spo2Request = null
                leaveLive()
                req.complete(runSpo2Once(t))
                continue
            }

            // 3. Live measurement, with a watchdog that re-enters the mode if the ring goes quiet.
            if (liveWanted()) {
                val wantSpo2 = liveSpo2Mode && !sleepTracking
                val stalled = liveActive && now - liveEnteredAt > LIVE_ENTRY_GRACE_MS &&
                    now - lastLiveFrameAt > LIVE_STALL_MS
                if (!liveActive || stalled || liveActiveSpo2 != wantSpo2) {
                    if (stalled) Log.d(TAG, "Live session ended; starting a new one")
                    enterLive(t, if (wantSpo2) RingProtocol.CMD_LIVE_SPO2_MODE else RingProtocol.CMD_LIVE_HR_MODE)
                    liveActive = true
                    liveActiveSpo2 = wantSpo2
                    liveEnteredAt = System.currentTimeMillis()
                    lastLiveFetch = liveEnteredAt
                    lastPoll = liveEnteredAt
                } else if (now - lastPoll >= LIVE_POLL_MS) {
                    // Every 60 s ask for a descriptor too (battery, skin temperature, steps).
                    if (now - lastLiveFetch >= IDLE_FETCH_MS) {
                        send(t, RingProtocol.CMD_FETCH)
                        lastLiveFetch = now
                    }
                    send(t, RingProtocol.CMD_POLL)
                    lastPoll = now
                }
                expireStaleLiveValues(now)
            } else {
                leaveLive()
                if (now - lastIdleFetch >= IDLE_FETCH_MS) {
                    send(t, RingProtocol.CMD_FETCH)
                    lastIdleFetch = now
                }
                expireStaleLiveValues(now)
            }
            delay(250)
        }
    }

    private suspend fun enterLive(t: GattTransport, modeCmd: ByteArray) {
        log(if (modeCmd.contentEquals(RingProtocol.CMD_LIVE_SPO2_MODE)) "Entering live SpO2 mode" else "Entering live HR mode")
        send(t, RingProtocol.CMD_STATUS_QUERY) // the ring wants d0 before a mode change
        delay(300)
        send(t, modeCmd)
        delay(300)
        send(t, RingProtocol.CMD_FETCH)
        lastLiveFrameAt = System.currentTimeMillis()
    }

    private fun expireStaleLiveValues(now: Long) {
        if (lastLiveHrAt > 0 && now - lastLiveHrAt > LIVE_HR_STALE_MS && _liveHeartRate.value != null) {
            log("Live HR expired (no reading for 60 s)")
            _liveHeartRate.value = null
            liveSettler.clear()
            lastLiveHrAt = 0L
        }
        if (lastLiveSpo2At > 0 && now - lastLiveSpo2At > 120_000L && _liveSpo2.value != null) {
            _liveSpo2.value = null
            lastLiveSpo2At = 0L
        }
    }

    private suspend fun runSpo2Once(t: GattTransport): Int? {
        val mark = System.currentTimeMillis()
        enterLive(t, RingProtocol.CMD_LIVE_SPO2_MODE)
        var result: Int? = null
        while (System.currentTimeMillis() - mark < 45_000 && t.linkUp.value) {
            delay(LIVE_POLL_MS)
            send(t, RingProtocol.CMD_POLL)
            if (lastLiveSpo2At >= mark) { result = _liveSpo2.value; break }
        }
        send(t, RingProtocol.CMD_LIVE_STOP)
        log(if (result != null) "One-shot SpO2: $result %" else "One-shot SpO2 timed out")
        return result
    }

    // ------------------------------------------------------------------ history drain

    private suspend fun runSync(t: GattTransport, channels: List<Byte>) {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        syncInbox = inbox
        _isSyncing.value = true
        try {
            var total = 0
            for (ch in channels) total += drainChannel(t, ch, inbox)
            log("History drain done (${channels.joinToString { "0x%02X".format(it) }}): $total epochs")
        } catch (e: Exception) {
            log("History drain aborted: ${e.message}")
        } finally {
            syncInbox = null
            inbox.close()
            _isSyncing.value = false
        }
    }

    /**
     * Drains one channel. Every `0x4C` page is persisted BEFORE it is acknowledged: the ACK
     * advances the ring's resume pointer and the ring discards the page.
     */
    private suspend fun drainChannel(t: GattTransport, channel: Byte, inbox: Channel<ByteArray>): Int {
        val chInt = channel.toInt() and 0xFF
        send(t, RingProtocol.createSyncUpToNowCommand(channel))
        var opened = false
        var emptyHint = false
        var pages = 0
        var epochs = 0
        var quiet = 0
        var nudged = false
        var lastHeader: ByteArray? = null
        val deadline = System.currentTimeMillis() + 180_000L

        while (System.currentTimeMillis() < deadline && t.linkUp.value) {
            val f = withTimeoutOrNull(1_000) { inbox.receive() }
            if (f == null) {
                quiet++
                if (!opened && quiet >= 4) {
                    log("No sync-open ack on channel 0x%02X; kicking with 07".format(chInt))
                    // Open ack lost: kick the stream anyway.
                    opened = true; quiet = 0
                    send(t, RingProtocol.CMD_FETCH)
                    continue
                }
                val cap = when {
                    pages > 0 -> 3
                    emptyHint -> 2
                    else -> 6
                }
                if (quiet < cap) continue
                if (pages > 0 && !nudged) {
                    nudged = true; quiet = 0
                    send(t, RingProtocol.CMD_FETCH)
                    continue
                }
                break
            }
            quiet = 0
            when (f[0].toInt() and 0xFF) {
                0x82 -> {
                    opened = true
                    emptyHint = f.size > 1 && f[1] == 0xFF.toByte()
                    Log.d(TAG, "Sync open ack ch 0x%02X: %s".format(chInt, f.hex()))
                    send(t, RingProtocol.CMD_FETCH)
                }
                0x4C -> {
                    val records = BulkRecord.parsePage(f)
                    if (records.isNotEmpty()) {
                        persistEpochs(records, chInt)
                        onEpochsPersisted(records)
                    }
                    send(t, RingProtocol.CMD_PAGE_ACK_4C)
                    pages++; epochs += records.size; nudged = false
                }
                0x47 -> { send(t, RingProtocol.CMD_PAGE_ACK_47); pages++; nudged = false }
                0x4D -> {
                    val recs = SportRecord.parse4D(f)
                    send(t, RingProtocol.CMD_PAGE_ACK_4D); pages++; nudged = false
                    log("Sport page: ${recs.size} records")
                }
                0x87, 0x10 -> {
                    // Only a genuinely new header re-kicks the stream, or we loop forever.
                    if (lastHeader == null || !f.contentEquals(lastHeader)) {
                        lastHeader = f.copyOf()
                        send(t, RingProtocol.CMD_FETCH)
                    }
                }
                0x50 -> break
            }
        }
        if (epochs > 0) log("Channel 0x%02X: %d epochs in %d pages".format(chInt, epochs, pages))
        return epochs
    }

    private suspend fun persistEpochs(records: List<BulkRecord>, channel: Int) {
        val ctx = appContext ?: return
        try {
            AppDatabase.getDatabase(ctx).epochDao().insertAll(records.map { r ->
                EpochEntity(
                    counter = r.counter,
                    timestampMillis = r.timestampMillis,
                    channel = channel,
                    layout = r.layout.name,
                    heartRate = r.heartRate,
                    hrvRmssd = r.hrvRmssd,
                    confidence = r.confidence,
                    respiratoryRate = r.respiratoryRate,
                    spo2Percent = r.spo2Percent,
                    rawBytes = r.raw,
                )
            })
        } catch (e: Exception) {
            Log.e(TAG, "Persisting epochs failed", e)
        }
    }

    private fun onEpochsPersisted(records: List<BulkRecord>) {
        val sorted = records.sortedBy { it.counter }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        log("Epochs ${fmt.format(java.util.Date(sorted.first().timestampMillis))}-" +
            "${fmt.format(java.util.Date(sorted.last().timestampMillis))} (${sorted.size}), last motion ${sorted.last().motion}")
        log("  with HR ${sorted.count { it.heartRate != null }}, HRV ${sorted.count { it.hrvRmssd != null }}, " +
            "SpO2 ${sorted.count { it.spo2Percent != null }}, layouts ${sorted.groupingBy { it.layout.name }.eachCount()}")
        sorted.lastOrNull { it.hrvRmssd != null }?.hrvRmssd?.let { _liveHrv.value = it }
        // Until a live reading arrives, show the newest measured values from history.
        if (_liveHeartRate.value == null) sorted.lastOrNull { it.heartRate != null }?.heartRate?.let { _liveHeartRate.value = it }
        if (lastLiveSpo2At == 0L) sorted.lastOrNull { it.spo2Percent != null }?.spo2Percent?.let { _liveSpo2.value = it }
        appContext?.let { ctx ->
            val levels = BulkRecord.floorSubtractedMotion(sorted).map { ActigraphTimeline.toSaaValue(it) }
            MotionSensorManager.getInstance(ctx).reportRingBatchMotions(levels)
        }
        SleepAsAndroidBridge.onEpochs(sorted)
    }

    // ------------------------------------------------------------------ frame dispatch

    private fun handleFrame(f: ByteArray) {
        if (f.isEmpty()) return
        val op = f[0].toInt() and 0xFF
        synchronized(rxCounts) { rxCounts[op]++ }
        when (op) {
            0x81 -> {
                if (f.size >= 3 && f[1] == 0x00.toByte()) authChallenge?.complete(f[2])
                else authConfirm?.complete(Unit)
            }
            0x11 -> sendCommand(RingProtocol.CMD_HEARTBEAT_ACK)
            0x10, 0x87 -> {
                onDescriptor(f)
                syncInbox?.trySend(f)
            }
            0x82, 0x4C, 0x47, 0x4D, 0x50 -> {
                val inbox = syncInbox
                when {
                    inbox != null -> inbox.trySend(f)
                    op == 0x4C || op == 0x47 || op == 0x4D -> strayPages?.trySend(f)
                    else -> Log.d(TAG, "Ignoring 0x%02X outside a drain".format(op))
                }
            }
            0x15 -> onLiveSample(f)
            0x4E -> {
                sendCommand(RingProtocol.CMD_PAGE_ACK_4E)
                SportRecord.parse4E(f)?.heartRate?.let { onLockedHeartRate(it) }
            }
            0x86 -> Log.d(TAG, "Mode ack ${f.hex()}")
            else -> Log.d(TAG, "Unhandled 0x%02X: %s".format(op, f.hex()))
        }
    }

    private fun onLiveSample(f: ByteArray) {
        lastLiveFrameAt = System.currentTimeMillis()
        LiveSample.parseHeartRate(f)?.let { onLockedHeartRate(it); return }
        if (LiveSample.isWarmup(f) && System.currentTimeMillis() - lastWarmupLogAt >= 60_000L) {
            lastWarmupLogAt = System.currentTimeMillis()
            log("Live HR warming up (sensor value ${f[2].toInt() and 0xFF})")
        }
        LiveSample.parseSpo2(f)?.let { spo2 ->
            _liveSpo2.value = spo2
            lastLiveSpo2At = System.currentTimeMillis()
        }
    }

    private fun onLockedHeartRate(raw: Int) {
        if (raw !in 30..220) return
        val now = System.currentTimeMillis()
        lastLiveHrAt = now
        val hr = liveSettler.add(raw)
        if (now - lastHrLogAt >= 30_000L) {
            lastHrLogAt = now
            log("Live HR $raw bpm${if (hr == null) " (settling)" else ", settled $hr"}")
        }
        if (hr == null) return
        _liveHeartRate.value = hr
        HrBroadcastManager.broadcastHeartRate(hr)
        SleepAsAndroidBridge.onLiveHeartRate(hr)
    }

    private fun onDescriptor(f: ByteArray) {
        val status = DeviceStatus.parse(f)
        if (status == null) {
            if (!descriptorRejectLogged) {
                descriptorRejectLogged = true
                log("Status frame not decoded (${f.size} B): ${f.hex()}")
            }
            return
        }
        _latestDeviceStatus.value = status
        if (System.currentTimeMillis() - lastStatusLogAt >= 5 * 60_000L) {
            lastStatusLogAt = System.currentTimeMillis()
            val temp = status.skinTemperature?.let { "%.1f°C".format(it.celsius) } ?: "n/a"
            val state = "%02X".format(status.stateByte)
            log("Ring status: battery ${status.batteryPercent}%, skin $temp, state 0x$state, steps ${status.quarterHourSteps}")
        }
        val ctx = appContext ?: return
        val prev = lastReportedRingSteps
        lastReportedRingSteps = status.quarterHourSteps
        val motion = MotionSensorManager.getInstance(ctx)
        if (prev != null && status.quarterHourSteps > prev && !status.isOnCharger) {
            val delta = status.quarterHourSteps - prev
            motion.reportRingMotion((0.25f + delta * 0.15f).coerceAtMost(3f), "Ring pedometer (+$delta steps)")
        }
        scope.launch {
            try {
                AppDatabase.getDatabase(ctx).deviceStatusDao().insert(
                    DeviceStatusEntity(
                        timestampMillis = status.timestamp,
                        batteryPercent = status.batteryPercent,
                        stateByte = status.stateByte,
                        isOnCharger = status.isOnCharger,
                        isSportMode = status.isSportMode,
                        quarterHourSteps = status.quarterHourSteps,
                        skinTemperatureC = status.skinTemperature?.celsius,
                        batteryVoltageMv = status.batteryVoltageMv,
                        caseBatteryPercent = status.caseBattery?.percent,
                        caseIsCharging = status.caseBattery?.isCharging,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Persisting descriptor failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ public commands

    /** Queues a raw command; dropped if no authenticated link. */
    fun sendCommand(cmd: ByteArray) {
        commandQueue?.trySend(cmd)
    }

    /**
     * Requests a full drain of both history channels (runs as soon as the link is free).
     * Automatic callers (screens, workers) are limited to one drain per 5 minutes, since every
     * drain pauses live HR; [force] is for the explicit "Sync" buttons.
     */
    fun syncHistory(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastFullDrainAt < 5 * 60_000L) return
        fullSyncRequested = true
        if (!_isConnected.value) ensureConnected()
    }

    private fun liveWanted(): Boolean = userLiveWanted || (sleepTracking && _liveHrDuringTracking.value)

    fun setLiveHrDuringTracking(enabled: Boolean) {
        _liveHrDuringTracking.value = enabled
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_SAA_LIVE_HR, enabled)?.apply()
        _isLiveMonitoring.value = liveWanted()
        log("Live HR during sleep tracking: ${if (enabled) "on" else "off (epoch vitals only)"}")
    }

    /** Who currently wants live mode ("dashboard", "hr_broadcast", ...). */
    private val liveOwners = java.util.Collections.synchronizedSet(HashSet<String>())

    fun startLiveMonitoring(hrMode: Boolean = true, owner: String = "ui") {
        liveOwners.add(owner)
        userLiveWanted = true
        liveSpo2Mode = !hrMode
        _isLiveMonitoring.value = true
    }

    /** Withdraws [owner]'s request; live mode stops only when nobody else still wants it. */
    fun stopLiveMonitoring(owner: String = "ui") {
        liveOwners.remove(owner)
        userLiveWanted = liveOwners.isNotEmpty()
        if (!userLiveWanted) liveSpo2Mode = false
        _isLiveMonitoring.value = liveWanted()
    }

    /**
     * Sleep as Android tracking on/off. While on: live HR is kept running (with watchdog) and the
     * sleep channel is drained every 90 s so motion reaches the actigraph within a few minutes.
     * Turning it off triggers a full drain.
     */
    fun setSleepTracking(enabled: Boolean) {
        if (sleepTracking == enabled) return
        sleepTracking = enabled
        _isLiveMonitoring.value = liveWanted()
        log(
            if (!enabled) "Sleep tracking ended: full drain"
            else if (_liveHrDuringTracking.value) "Sleep tracking: live HR + 90 s history drains"
            else "Sleep tracking: 90 s history drains (HR/HRV/SpO2 from ring epochs)"
        )
        if (enabled) {
            quickDrainRequested = true // get the newest epochs right away
            ensureConnected()
        } else {
            syncHistory()
        }
    }

    fun launchSpo2Measurement() {
        scope.launch { measureSpo2Once() }
    }

    /** One-shot SpO2 (up to 45 s). Returns null on timeout or when not connected. */
    suspend fun measureSpo2Once(): Int? {
        if (!_isReady.value) return null
        val req = CompletableDeferred<Int?>()
        spo2Request = req
        return withTimeoutOrNull(120_000) { req.await() }
    }

    fun toggleFindRingLed(enable: Boolean? = null) {
        val target = enable ?: !_isRingLedOn.value
        _isRingLedOn.value = target
        log("Ring LED ${if (target) "on" else "off"}")
        sendCommand(if (target) RingProtocol.CMD_FIND_RING_LED_ON else RingProtocol.CMD_FIND_RING_LED_OFF)
    }

    private fun ByteArray.hex(sep: String = " "): String = joinToString(sep) { "%02X".format(it) }
}
