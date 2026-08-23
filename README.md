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
  - Responds to `com.urbandroid.sleep.watch.CHECK_CONNECTED` with `CONFIRM_CONNECTED`.
  - Dispatches aggregated movement batches (`com.urbandroid.sleep.watch.DATA_UPDATE` with `MAX_RAW_DATA`).
  - Dispatches heart rate updates (`com.urbandroid.sleep.watch.HR_DATA_UPDATE`).
  - Dispatches live SpO₂, Respiration Rate, and HRV via `com.urbandroid.sleep.ACTION_EXTRA_DATA_UPDATE`.
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
3. Tap **Test sensor** in Sleep as Android:
   - The test sensor screen will connect immediately and graph live movement and Heart Rate.
4. When you start sleep tracking in Sleep as Android, the app will automatically stream movement batches, Heart Rate, SpO₂, and Respiration Rate throughout the night.

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

- Reverse-engineering research and insights from the [OpenCircuit](https://github.com/OpenCircuit) project.
- [Sleep as Android](https://sleep.urbandroid.org) for their open Wearable Integration API.
- [Google Health Connect](https://developer.android.com/guide/health-and-fitness/health-connect) for on-device health data standardization.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
