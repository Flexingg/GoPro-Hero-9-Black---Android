# GoPro HERO9 Black — Android Streaming & Retrospective Clip App

[![Build & Release Android APK](https://github.com/Flexingg/GoPro-Hero-9-Black---Android/actions/workflows/build.yml/badge.svg)](https://github.com/Flexingg/GoPro-Hero-9-Black---Android/actions/workflows/build.yml)
[![GitHub Release](https://img.shields.io/github/v/release/Flexingg/GoPro-Hero-9-Black---Android?color=00E5FF)](https://github.com/Flexingg/GoPro-Hero-9-Black---Android/releases/tag/v0.0.6)

An Android application engineered to pair with the **GoPro HERO9 Black** over BLE & 5GHz Wi-Fi, stream live preview footage with ultra-low latency, and retrospectively clip and save the last $X$ seconds (15s, 30s, 60s) in full uncompressed resolution (4K 60fps / 5K 30fps).

Styled using **Oppo's Android 17 (ColorOS 17) "Living Flow Design" (流体设计)** featuring **Fluid Cloud (流体云)** dynamic pill status cards, frosted glassmorphism with 1px luminous contour strokes, spring physics interactions, and a real-time **Stream & Network Inspector**.

---

## 🚀 Download v0.0.6 Release

Download the pre-built APKs directly from the [GitHub v0.0.6 Release](https://github.com/Flexingg/GoPro-Hero-9-Black---Android/releases/tag/v0.0.6):

- 📦 [**Download `app-debug.apk`**](https://github.com/Flexingg/GoPro-Hero-9-Black---Android/releases/download/v0.0.6/app-debug.apk) (Ready for direct sideloading and testing)
- 📦 [**Download `app-release.apk`**](https://github.com/Flexingg/GoPro-Hero-9-Black---Android/releases/download/v0.0.6/app-release.apk) (Optimized release build)

---

## 🎯 Architecture & Technical Highlights

1. **Dual-Radio Connection Pipeline**:
   - **BLE (Bluetooth Low Energy)**: Autonomous discovery and bonding (`Service UUID 0000fea6-0000-1000-8000-00805f9b34fb`). Dispatches AP enable command `[0x03, 0x17, 0x01, 0x01]` to UUID `b5f90072-...` and reads SSID (`b5f90002-...`) and WPA2 passphrase (`b5f90003-...`).
   - **Wi-Fi AP (`10.5.5.9:8080`)**: Android `WifiNetworkSpecifier` direct socket binding via `connectivityManager.bindProcessToNetwork(network)`, keeping mobile cellular internet fully active.

2. **Network-Bound UDP Stream Relay & Loopback Routing**:
   - Explicitly binds `DatagramSocket` to the Android Wi-Fi `Network` interface using `network.bindSocket(socket)`, preventing Android cellular routing from dropping camera UDP packets.
   - Dual heartbeat dispatched directly from the bound listening socket:
     - UDP: `_GPHD_:1:0:2:0.000000\n` (HERO9 format) & `_GPHD_:0:0:2:0.000000\n` to `10.5.5.9:8554` every 1.5s to establish router NAT pinholes.
     - HTTP: `GET /gopro/camera/keep_alive` & `/gp/gpControl/status` every 2.5s.
   - Relays incoming MPEG-TS packets to `127.0.0.1:8555` (localhost loopback) where Media3 ExoPlayer consumes them with zero interface conflicts.
   - Live stream initiation tries modern OpenGoPro endpoints (`/stream/start?port=8554`, `/stream/start`) and legacy gpStream fallbacks.

3. **In-App Living Flow Diagnostics & Network Inspector**:
   - Real-time packet telemetry: Ingested packet count, live bitrate (Mbps), MPEG-TS sync byte verification (`0x47`), and ExoPlayer frame rendering status.
   - Manual controls for immediate heartbeat injection, stream restarting, and direct vs relay routing toggles.
   - Built-in guidance regarding GoPro hardware encoding constraints (HERO9 hardware encoder stops preview whenever recording to SD card starts).

3. **Dual-Tier Retrospective Clipping ("Clip Last 15s / 30s / 60s")**:
   - **Instant Preview (0ms)**: Memory circular ring-buffer dumps incoming MPEG-TS stream packets directly to local disk for instant preview and sharing.
   - **4K60/5K Uncompressed Master**: Triggers the GoPro HERO9 GP1 sensor's **HindSight** hardware buffer flush (`shutter/start` -> flush -> `shutter/stop`), commits the uncompressed master file to camera SD storage, and transfers it to the phone via camera **Turbo Transfer** (`GET /gopro/media/turbo_transfer/on`).

4. **ColorOS 17 Living Flow Design (流体设计)**:
   - **Fluid Cloud (流体云)**: Top dynamic island capsule displaying camera model, live status, battery pill, and expanding on tap with spring physics to show full telemetry (BLE radio, Wi-Fi IP, HindSight mode, FPS).
   - **Luminous Frosted Glass**: Multi-layered dark glass surfaces with top-lit 1px luminous contour stroke.
   - **Spring Physics**: Damped springs for press (stiffness 800, damping 0.8), rebound (stiffness 450, damping 0.55), and dynamic cloud morphs.
   - **Precision Horizon Indicator**: Live pitch and roll guidance overlay.

---

## 📱 How to Test & Pair with GoPro HERO9 Black

1. **On your GoPro HERO9 Black**:
   - Ensure camera Bluetooth and Wireless Connections are turned **ON** (`Preferences` -> `Connections` -> `Wireless Connections: ON`).
   - Leave camera on the standard video mode (with HindSight enabled in camera preset for 4K master buffering).
2. **On your Android Device**:
   - Install `app-debug.apk`.
   - Launch the app and grant Bluetooth, Location, and Storage permissions.
   - Tap **"Scan & Pair Camera"**.
   - The app will automatically discover your HERO9, negotiate BLE bonding, enable the camera's Wi-Fi AP, bind the socket network, and start the live 720p60 UDP viewfinder.
   - Tap **15s**, **30s**, or **60s** duration pills, then tap the giant glowing **"CLIP"** button to capture retrospective footage!

---

## 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/Flexingg/GoPro-Hero-9-Black---Android.git
cd GoPro-Hero-9-Black---Android

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

Compiled APKs will be located in:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
