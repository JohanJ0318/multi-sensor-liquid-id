# Multi-Sensor Liquid Identification System

> **UCF Senior Design — Group 17 | Spring/Summer 2026**

A compact tabletop device that identifies liquids by measuring **conductivity**, **density**, and **temperature** simultaneously, then classifying the sample using **kNN** and **nearest-centroid** algorithms running on an STM32G431 microcontroller. Results are displayed on a companion **Android application** over USB CDC.

---

## Team

| Name                  | Role                | Contact |
|-----------------------|---------------------|---------|
| Johan Johnson         | Computer Engineering | —       |
| Frederick Hornberger  | Computer Engineering | —       |
| John McGuire          | Computer Engineering | —       |

**Advisors:** Dr. Reza Abdolvand · Dr. Saleem Sahawneh · Dr. Mike Maddox

---

## System Overview

```
┌──────────────────────────────────────────────────────┐
│                   Liquid Sample                      │
│         ┌──────┐  ┌──────┐  ┌──────┐                │
│         │Cond. │  │Load  │  │Temp  │                │
│         │Probe │  │Cell  │  │Sensor│                │
│         └──┬───┘  └──┬───┘  └──┬───┘                │
│            │         │         │                     │
│  ┌─────────▼─────────▼─────────▼──────────┐         │
│  │          STM32G431KBU6                  │         │
│  │  ┌─────────┐ ┌──────┐ ┌────────────┐   │         │
│  │  │Acquire  │→│DSP / │→│ Classify   │   │         │
│  │  │Sensors  │ │Filter│ │ kNN + NC   │   │         │
│  │  └─────────┘ └──────┘ └─────┬──────┘   │         │
│  │                             │           │         │
│  │                        USB CDC          │         │
│  └─────────────────────────┬───────────────┘         │
│                            │                         │
│                    ┌───────▼────────┐                │
│                    │ Android App    │                │
│                    │ (Kotlin/MVVM)  │                │
│                    │ Dashboard +    │                │
│                    │ Results +      │                │
│                    │ Model Compare  │                │
│                    └────────────────┘                │
└──────────────────────────────────────────────────────┘
```

## Repository Structure

```
multi-sensor-liquid-id/
├── firmware/                  # STM32 embedded firmware (C)
│   ├── Core/
│   │   ├── Inc/               # Header files
│   │   └── Src/               # Source files
│   ├── Drivers/               # HAL & CMSIS drivers
│   ├── Middlewares/            # USB CDC middleware
│   └── STM32CubeIDE/          # IDE project files
│
├── android/                   # Kotlin Android companion app
│   ├── app/src/main/
│   │   ├── java/com/group17/liquidid/
│   │   │   ├── data/          # Models, repositories, USB manager
│   │   │   ├── ui/            # Screens (Dashboard, Results, Compare)
│   │   │   └── viewmodel/     # MVVM ViewModels
│   │   └── res/               # Layouts, drawables, values
│   └── build.gradle.kts
│
├── hardware/                  # Schematics & PCB design files
│   ├── schematics/
│   └── pcb/
│
├── scripts/                   # Offline training & data tools
├── test-data/                 # Reference measurement datasets
├── docs/                      # Documentation & figures
└── README.md
```

## Features

### Embedded Firmware
- **Deterministic state machine**: Idle → Acquire → Compute → Classify → Report
- **Three sensor channels**: AC conductivity (DAC + ADC), HX711 load cell (density), DS18B20 (temperature)
- **DSP pipeline**: 100+ sample averaging, RMS extraction, calibration, validity checks
- **Dual classifiers**: k-Nearest Neighbors and nearest-centroid, both running on-device
- **USB CDC**: Binary packet protocol with sync markers, checksums, and acknowledgments

### Android Application
- **Live Dashboard**: Real-time sensor readings with scrolling charts (MPAndroidChart)
- **Results View**: Predicted substance with confidence gauge, feature breakdown
- **Model Comparison**: Side-by-side kNN vs. nearest-centroid results
- **Session Logging**: CSV and JSON export of measurement sessions
- **MVVM Architecture**: Clean separation via ViewModel + StateFlow

## Hardware

| Component            | Part               | Purpose                          |
|----------------------|--------------------|----------------------------------|
| Microcontroller      | STM32G431KBU6      | Main processor, ADC, DAC, USB    |
| Conductivity probe   | Two-electrode cell  | AC excitation conductivity       |
| Load cell + ADC      | HX711 (24-bit)     | Mass measurement → density       |
| Temperature sensor   | DS18B20            | Digital 1-Wire temperature       |
| Op-amps              | OPA320 + INA333    | Analog front-end conditioning    |

## Communication Protocol

Binary packets over USB CDC @ 115200 baud:

| Packet Type | ID     | Direction    | Purpose                        |
|-------------|--------|--------------|--------------------------------|
| Measurement | `0x01` | Device → App | Sensor data + classifications  |
| Command     | `0x02` | App → Device | Start, mode change, export     |
| Status      | `0x03` | Device → App | Device state on connect        |
| Label Table | `0x04` | Device → App | Substance name mapping         |
| ACK         | `0x05` | Device → App | Command acknowledgment         |

## Getting Started

### Firmware
1. Open `firmware/STM32CubeIDE/` in STM32CubeIDE
2. Connect STM32G431 Nucleo / custom PCB via ST-Link
3. Build and flash

### Android App
1. Open `android/` in Android Studio
2. Connect Android device with USB OTG
3. Build and run — grant USB permissions when prompted

### Offline Training
```bash
cd scripts/
python train_models.py --data ../test-data/reference_measurements.csv
```

## Build Requirements

| Tool             | Version       |
|------------------|---------------|
| STM32CubeIDE     | ≥ 1.14        |
| ARM GCC          | ≥ 12.x        |
| Android Studio   | ≥ Hedgehog    |
| Kotlin           | ≥ 1.9         |
| Min Android SDK  | API 26 (8.0)  |
| Target SDK       | API 34        |
| Python           | ≥ 3.10        |

## License

This project is developed as part of UCF Senior Design (EEL 4914 / EEL 4915). All rights reserved by the authors.

## Acknowledgments

- University of Central Florida, Department of Electrical and Computer Engineering
- Dr. Mark Borowczak (Senior Design Instructor)
- Dr. Reza Abdolvand, Dr. Saleem Sahawneh, Dr. Mike Maddox (Reviewers)
