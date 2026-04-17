# Firmware — STM32G431KBU6

## Architecture

The firmware runs as a single-threaded deterministic state machine:

```
  ┌──────┐    button/USB     ┌─────────┐
  │ IDLE │ ────────────────→ │ ACQUIRE │
  └──────┘                   └────┬────┘
      ↑                          │
      │                    ┌─────▼─────┐
      │                    │  COMPUTE  │
      │                    └─────┬─────┘
      │                          │
      │                    ┌─────▼─────┐
      │                    │ CLASSIFY  │
      │                    └─────┬─────┘
      │                          │
      │                    ┌─────▼─────┐
      └────────────────────│  REPORT   │
                           └───────────┘
```

## Source Files

| File         | Purpose                                         |
|--------------|--------------------------------------------------|
| `main.c`     | State machine, entry point, GPIO callbacks        |
| `sensors.c`  | DAC excitation, ADC sampling, HX711, DS18B20      |
| `dsp.c`      | RMS, mean, calibration, kNN, nearest-centroid      |
| `comm.c`     | USB CDC packet framing, parsing, TX/RX             |
| `datalog.c`  | Flash circular buffer for measurement records      |

## Building

1. Open the project in STM32CubeIDE
2. Generate peripheral initialization with STM32CubeMX (`.ioc` file)
3. Build → Flash via ST-Link

## Model Deployment

After collecting reference data and running `scripts/train_models.py`, copy the
generated `model_params.h` into `Core/Inc/` and rebuild.
