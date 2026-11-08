# GoPro Hero 9 Black — Android Streaming & Retrospective Clip App

An Android application designed to pair with a GoPro HERO9 Black over BLE & Wi-Fi, stream live preview footage with ultra-low latency, and retrospectively clip and save the last $X$ seconds in uncompressed full resolution (up to 5K/4K 60fps).

Styled using **Oppo's Android 17 (ColorOS 17) Fluid UI / Aquamorphic Design System** featuring **Fluid Cloud (流体云)** dynamic pill status cards, frosted glassmorphism, and Aurora engine spring interactions.

---

## 🎯 Architecture & Technical Highlights

1. **Dual-Radio Connection Pipeline**:
   - **BLE (Bluetooth Low Energy)**: Discovery, authentication/bonding (`Service UUID 0000fea6-0000-1000-8000-00805f9b34fb`), battery status, and Wi-Fi AP provisioning.
   - **Wi-Fi AP (`10.5.5.9`)**: Android `WifiNetworkSpecifier` direct socket binding, keeping mobile data active for internet access.

2. **Live Video Streaming**:
   - Camera starts preview via `GET /gopro/camera/stream/start?port=8554`.
   - Incoming **MPEG-TS over UDP on port 8554** rendered via **AndroidX Media3 (ExoPlayer)** or **LibVLC Android** with hardware `MediaCodec` acceleration.
   - Background keep-alive heartbeat dispatched every 2.5 seconds (`GET /gopro/camera/keep_alive`).

3. **Retrospective Full-Resolution Clipping ("Clip Last X Seconds")**:
   - **Hardware HindSight Integration**: Leverages the GoPro HERO9 GP1 sensor's 15s/30s rolling RAM buffer. Triggering shutter commits the full-resolution (4K60/5K30) buffer directly to SD card without continuous card write wear.
   - **Local Ring Buffer (Fast Fallback)**: Maintains the last $X$ seconds of preview stream in local circular memory for immediate 0ms extraction and sharing while high-res master transfers in background via Turbo Transfer (`GET /gopro/media/turbo_transfer/on`).

4. **Oppo ColorOS 17 Fluid UI**:
   - **Fluid Cloud (流体云)**: Top dynamic pill capsule reflecting GoPro telemetry (battery %, SD storage, signal, encoding state).
   - **Aquamorphic Aesthetics**: Oceanic color palette, translucent frosted glass layers (`blur`), and fluid spring animations.

---

## 📂 Repository Structure

```
├── app/                              # Android Application module
│   ├── src/main/java/com/flexingg/goprohero9/
│   │   ├── data/                     # BLE, Wi-Fi Specifier, GoPro HTTP API client
│   │   ├── player/                   # Media3 UDP / LibVLC Video Stream Decoder
│   │   ├── buffer/                   # Circular RAM buffer for retrospective clipping
│   │   └── ui/                       # Jetpack Compose UI (ColorOS 17 Fluid Design)
└── docs/                             # Detailed protocol specifications & research
```

---

## 📚 References & Research

- [Open GoPro Specification](https://gopro.github.io/OpenGoPro/)
- [gopro/OpenGoPro GitHub](https://github.com/gopro/OpenGoPro)
- [KonradIT/goprowifihack](https://github.com/KonradIT/goprowifihack)
- [AndroidX Media3 UDP Playback](https://developer.android.com/jetpack/androidx/releases/media3)
