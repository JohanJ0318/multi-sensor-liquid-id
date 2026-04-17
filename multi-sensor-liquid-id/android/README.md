# Android Companion App — Liquid ID

## Architecture

The app follows **MVVM** (Model-View-ViewModel) with Kotlin StateFlow for reactive UI updates.

```
┌─────────────────────────────────────────────────────┐
│                     Views (Fragments)                │
│  ┌──────────────┐ ┌────────────┐ ┌───────────────┐  │
│  │ Dashboard    │ │  Results   │ │  Comparison   │  │
│  │ (Live Charts)│ │  (Predict) │ │  (Side-by-side│  │
│  └──────┬───────┘ └─────┬──────┘ └──────┬────────┘  │
│         │               │               │            │
│         └───────────────┼───────────────┘            │
│                         │                            │
│                 ┌───────▼────────┐                   │
│                 │ DeviceViewModel │                   │
│                 │  (StateFlow)   │                   │
│                 └───────┬────────┘                   │
│                         │                            │
│         ┌───────────────┼───────────────┐            │
│         │               │               │            │
│  ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐    │
│  │UsbSerial    │ │SessionLogger│ │PacketParser  │    │
│  │Manager      │ │(CSV/JSON)   │ │(Binary Proto)│    │
│  └─────────────┘ └─────────────┘ └─────────────┘    │
└─────────────────────────────────────────────────────┘
```

## Screens

### 1. Live Dashboard
- Three sensor cards (Conductivity, Density, Temperature)
- Real-time scrolling line charts via MPAndroidChart (last 20 readings)
- Status bar: connection state, operating mode, sensor validity

### 2. Results View
- Large predicted substance name + confidence gauge
- Toggle between kNN and nearest-centroid results
- Feature value breakdown

### 3. Model Comparison
- Side-by-side kNN vs. nearest-centroid panels
- Agreement/disagreement banner
- Normalized feature vector bar chart

## Key Dependencies

| Library | Purpose |
|---------|---------|
| [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) | USB CDC serial communication |
| [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) | Real-time line and bar charts |
| AndroidX Lifecycle | ViewModel + StateFlow |
| Material Components | Bottom navigation, FAB, cards |

## USB Protocol

The app communicates with the STM32 over USB CDC (virtual serial port) at 115200 baud using a binary packet protocol. See `PacketParser.kt` for the full spec.

Auto-launch is configured via `usb_device_filter.xml` — the app opens automatically when an STM32 CDC device (VID `0x0483`, PID `0x5740`) is connected.

## Building

1. Open `android/` directory in Android Studio
2. Sync Gradle (JitPack repo needed for MPAndroidChart)
3. Connect Android device via USB
4. Run on device — USB Host mode requires a physical device (no emulator)

## Session Export

Enable session logging from the settings menu. Exported files are saved to the device's Downloads folder:

- **CSV**: `liquid_id_session_YYYY-MM-DD_HH-mm-ss.csv`
- **JSON**: `liquid_id_session_YYYY-MM-DD_HH-mm-ss.json`

Both formats include metadata headers with session timestamp, model version, and substance label mapping.
