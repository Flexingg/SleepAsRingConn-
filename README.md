# SleepAsRingConn 💍💤

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Compose-Material%203%20Expressive-blue.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Health Connect](https://img.shields.io/badge/Health%20Connect-1.1.0--alpha11-teal.svg?style=flat&logo=googlefit)](https://developer.android.com/guide/health-and-fitness/health-connect)
[![Sleep as Android](https://img.shields.io/badge/Sleep%20as%20Android-DIY%20Wearable%20API-orange.svg?style=flat)](https://sleep.urbandroid.org)

**SleepAsRingConn** is a standalone, local-first, privacy-focused Android application that connects directly to the **RingConn Gen 2** smart ring over Bluetooth Low Energy (BLE).

It decodes all biometric telemetry on-device without cloud dependencies, writes standard records directly into **Google Health Connect**, and acts as a native DIY wearable bridge for **Sleep as Android**.

---

## 🌟 Key Features

- **🔒 100% Local-First & Private**: Direct BLE GATT connection to the ring. No cloud accounts, no servers, and no telemetry tracking.
- **⚡ On-Device Cryptographic Authentication**: Implements national SM3 (GB/T 32905) challenge-response authentication (`RingAuth`) to pair and activate the ring completely offline.
- **📊 Detailed Sleep Analytics & Hypnogram**:
  - Interactive Canvas-rendered **stepped hypnogram** (Awake, REM, Light, Deep) with color-coded stage blocks.
  - **Tap-to-Inspect Scrubber**: Tap anywhere on the timeline to inspect exact 2.5-minute epochs (Timestamp, Stage, HR BPM, HRV RMSSD ms, SpO₂ %, Respiration Rate, and Motion magnitude).
  - **Overnight Heart Rate & Resting Dip Curve**: Continuous HR graph identifying the lowest nocturnal HR dip.
  - **Blood Oxygen (SpO₂) Stability Graph**: SpO₂ percentage curve (85%–100%) with 90% and 95% target baseline reference lines.
  - **Accurate Sleep Duration**: Sleep time is calculated strictly from non-awake sleep stages (Light + Deep + REM). Awake time is categorized separately alongside total time in bed and sleep efficiency %.
- **🫀 Google Health Connect Export**:
  - `HeartRateRecord` (Series of timestamps + BPM)
  - `HeartRateVariabilityRmssdRecord` (HRV RMSSD in milliseconds)
  - `OxygenSaturationRecord` (SpO₂ percentage)
  - `RespiratoryRateRecord` (Breaths per minute)
  - `BodyTemperatureRecord` (0.1°C skin temperature)
  - `StepsRecord` (Aggregated 15-minute step buckets)
  - `SleepSessionRecord` (Complete sleep sessions with staged hypnograms: Awake, Light, Deep, REM)
- **🌙 Sleep as Android DIY Wearable Bridge**:
  - Responds to `com.urbandroid.sleep.watch.CHECK_CONNECTED` with `CONFIRM_CONNECTED` (and reconnects the ring if it is asked while disconnected).
  - Rebuilds a continuous 10-second actigraph (`DATA_UPDATE` / `MAX_RAW_DATA`) from the ring's 30-second motion counts. See [How the Sleep as Android stream works](#-how-the-sleep-as-android-stream-works).
  - Sends live heart rate every 10 s (`HR_DATA_UPDATE`), and backfills HR, HRV and SpO₂ from history epochs with their real timestamps (`ACTION_EXTRA_DATA_UPDATE`).
  - Flashes the ring's locator LED upon receiving `HINT` broadcasts (anti-snore cues & lucid dreaming prompts).
- **💓 Continuous Live Pulse & Vitals**:
  - Real-time optical sampling with keepalive polling for continuous Heart Rate, SpO₂, Skin Temperature, and Steps.
  - Bidirectional "Light Ring LED" locator button with dynamic on/off state tracking (`24 01 00` on / `24 00 00` off).
- **🎨 Material 3 Expressive UI**:
  - Dynamic Material You theming and color harmonies.
  - Intelligent Bluetooth scanner that automatically prioritizes and highlights `*Ringconn*` smart rings at the top of the scan list.
  - Automatic reconnection to paired rings on launch.
  - Real-time BLE packet inspector and hex diagnostic console.

---

## 🛠️ How It Works & Protocol Reverse-Engineering

The RingConn Gen 2 smart ring communicates over custom Bluetooth LE GATT characteristics. SleepAsRingConn implements a clean, native Kotlin driver based on protocol reverse-engineering findings:

### 1. GATT Architecture
- **Primary Data Service**: `8327ad99-2d87-4a22-a8ce-6dd7971c0437`
- **Notify Characteristic**: `8327ad97-2d87-4a22-a8ce-6dd7971c0437` (CCCD `0x2902`)
- **Write Characteristic**: `8327ad98-2d87-4a22-a8ce-6dd7971c0437` (Write Without Response / `WRITE_TYPE_NO_RESPONSE`)

### 2. SM3 Challenge-Response Authentication
When notifications are enabled on the ring, host writes `01 00 00` (Status 0). The ring replies with challenge frame `81 00 <challenge> <xor>`. The app derives the response:
$$V = \text{MAC}[3] \oplus \text{MAC}[4] \oplus \text{MAC}[5]$$
$$\text{response} = \text{SM3}([V, \text{challenge}])[29..31]$$
Host sends `01 01 <r0> <r1> <r2> 00`. The ring verifies the digest and enters active streaming mode (`81 01 00 80`).

### 3. Descriptor Frames (`0x10` / `0x87`)
Solicited via `D0 00 00` / `07 00 00` or pushed spontaneously:
- `[1]`: Ring Battery percentage (0–100%)
- `[2]`: State / Mode byte (`0x04` = On Charger, `0x02`/`0x03` = Worn Idle, `0x06` = Sport Active)
- `[4:6]`: 16-bit BE Step count (Quarter-hour bucket, cleared at `:00`, `:15`, `:30`, `:45`)
- `[6:8]`, `[8:10]`: Dual-channel skin temperature in $0.1^\circ\text{C}$ (e.g. `01 64` = 35.6°C)
- `[14:16]`: Battery raw voltage in mV (16-bit BE, e.g. `4001` mV)
- `[17]`: Charging Case status (low 7 bits = case battery %, bit `0x80` = case charging, `0xFF` = out of case)

### 4. Bulk History Drain (`0x4C`)
Drains 23-byte records across 150-second epochs (seconds since `1577793600` / 2019-12-31 12:00:00 UTC):
- `[4]`: Heart Rate (BPM)
- `[5]`: HRV / RMSSD (ms)
- `[6]`: Signal Confidence quality
- `[7]`: Respiratory Rate $\times 8$ ($\div 8 \rightarrow \text{brpm}$)
- `[8]`: SpO₂ percentage (or `0x12`/`0x13`/`0x11` activity / awake markers)
- `[10:20]`: Activity intensity magnitude blob (`acti_counts`)

Dual history channels are drained concurrently:
- **Channel `0x00`**: Sleep / Overnight log
- **Channel `0x03`**: Daytime / All-Day log & daytime SpO₂

---

## 📱 Sleep as Android Configuration

To use your RingConn Gen 2 with **Sleep as Android**:

1. In **SleepAsRingConn**:
   - Navigate to the **Integrations** tab.
   - In the *Sleep as Android* card, tap **Copy Package Name** (`com.randallengineering.sleepasringconn`).
2. Open **Sleep as Android**:
   - Go to **Settings** (gear icon) → **Wearables**.
   - Tap **Sleep tracking** → select **"Gear, Galaxy Gear, DIY or other"**.
   - Scroll down to **Wearable integration (DIY)**.
   - Tap **Custom package name** → paste `com.randallengineering.sleepasringconn` → tap **OK**.
3. In the same wearable settings, enable **heart rate** (and **SpO₂** if you want it). Sleep as Android only asks for what is enabled there.
4. Start sleep tracking. Movement appears in the graph about 2-4 minutes behind real time (see below), so **Test sensor** stays flat for the first few minutes.

### 🔬 How the Sleep as Android stream works

The RingConn Gen 2 has no live accelerometer stream. Its only motion data is five 30-second activity counts inside each 2.5-minute history epoch (`0x4C`). Sleep as Android, meanwhile, appends every `DATA_UPDATE` value as the next 10-second slot, with no timestamps. Earlier versions sent "whatever arrived in the last 10 s", which was almost always nothing, so the actigraph came out flat.

What the bridge does now:

- **Drains the sleep channel every 90 s while tracking**, so new epochs arrive within a few minutes. Each page is saved to the database before it is acknowledged, because the acknowledgement deletes it from the ring.
- **Plays the motion back on a wall-clock timeline** (`ActigraphTimeline`). Each 30-second count, minus the ring's resting floor, fills its three 10-second slots. Exactly one value is sent per 10 s of real time, so the graph stays aligned and continuous. It is shifted only by the ring's 2-4 minute reporting delay.
- **Never sends 0.0**: when a batch starts with a non-zero value, Sleep as Android drops every zero in it, which used to compress the timeline.
- **Keeps the stream alive through gaps**: if the ring goes quiet, neutral values are sent once the gap is 6 minutes old, so Sleep as Android's 4-minute "no data, restart tracking" timer never fires mid-gap. A repeated `START_TRACKING` no longer resets anything.
- **Corrects the ring clock**: record counters are documented with two anchors 4 h apart, so the first drain checks the newest record against the phone clock and corrects whole-hour offsets.
- **Heart rate**: live HR runs all night with a watchdog that re-enters live mode if the ring stops answering. Any 2.5-minute stretch that live HR missed is backfilled from the epoch's HR at its real timestamp.

The Integrations tab shows what has been sent and how far behind the actigraph is.

---

## 🏗️ Architecture & Project Structure

```
SleepAsRingConn/
├── app/
│   ├── src/main/java/com/randallengineering/sleepasringconn/
│   │   ├── MainActivity.kt                 # Compose Root, Navigation & Runtime Permissions
│   │   ├── SleepAsRingConnApp.kt           # Application lifecycle
│   │   ├── ble/
│   │   │   └── BleConnectionManager.kt     # GATT lifecycle, MTU, packet dispatch & maintainer
│   │   ├── protocol/
│   │   │   ├── SM3.kt                      # Pure Kotlin SM3 hash implementation
│   │   │   ├── RingAuth.kt                 # Challenge-response derivation
│   │   │   ├── RingProtocol.kt             # Protocol constants, command arrays & XOR checks
│   │   │   ├── DeviceStatus.kt             # 0x10 / 0x87 descriptor parser
│   │   │   └── BulkRecord.kt               # 0x4C page and 23-byte epoch parser
│   │   ├── analytics/
│   │   │   └── SleepStagingEngine.kt       # Hypnogram classifier & sleep score engine
│   │   ├── data/
│   │   │   ├── Entities.kt                 # Room entities (Epochs, Status, Sessions)
│   │   │   └── AppDatabase.kt              # Room DAOs and database singleton
│   │   ├── healthconnect/
│   │   │   └── HealthConnectManager.kt     # Google Health Connect batch exporter
│   │   ├── sleepasandroid/
│   │   │   ├── SleepAsAndroidBridge.kt     # Wearable Integration API broadcaster & stream ticker
│   │   │   └── SleepAsAndroidReceiver.kt   # Inbound broadcast intent receiver
│   │   ├── service/
│   │   │   └── RingSyncService.kt          # Android 14+ connected device foreground service
│   │   └── ui/
│   │       ├── screens/
│   │       │   ├── DashboardScreen.kt      # Vitals, live pulse, LED control, prioritized scanner
│   │       │   ├── SleepScreen.kt          # Canvas hypnogram, HR/HRV trends, SpO2 stability
│   │       │   ├── IntegrationsScreen.kt   # Health Connect & Sleep as Android manager
│   │       │   └── DiagnosticsScreen.kt    # Real-time BLE packet terminal & hex logs
│   │       └── theme/
│   │           ├── Color.kt                # Material 3 color palette
│   │           └── Theme.kt                # Expressive theme wrapper
│   └── src/test/java/com/randallengineering/sleepasringconn/
│       └── ProtocolTest.kt                 # SM3 KAT, RingAuth, XOR, Descriptor & Bulk unit tests
├── gradle/
│   └── libs.versions.toml                  # Version catalog
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 💻 Building from Source

### Prerequisites
- Android Studio Ladybug / Meerkat or newer
- JDK 17+
- Android SDK 35 (Android 15) with platform-tools / adb

### Build & Test
```bash
# Clone the repository
git clone https://github.com/Flexingg/SleepAsRingConn.git
cd SleepAsRingConn

# Run unit test suite
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Install via ADB to a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🤝 Acknowledgments

- Reverse-engineering research and insights from the [OpenCircuit](https://github.com/perezjuanj/OpenCircuit) project.
- Connection and sync patterns informed by [RingLink](https://github.com/zazaulola/ringlink) and [RingConn-Gen-2-Android](https://github.com/Arkabas/RingConn-Gen-2-Android) (serialised GATT writes, bonding, destructive-ACK handling, the 4-hour counter anchor).
- Sleep as Android intent handling checked against [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)'s `SleepAsAndroidSender` and the official [wearable API docs](https://sleep.urbandroid.org/docs/devs/wearable_api.html).
- [Sleep as Android](https://sleep.urbandroid.org) for their open Wearable Integration API.
- [Google Health Connect](https://developer.android.com/guide/health-and-fitness/health-connect) for on-device health data standardization.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
