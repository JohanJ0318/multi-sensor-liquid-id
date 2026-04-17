/**
 * @file    main.c
 * @brief   Multi-Sensor Liquid Identification System — Main State Machine
 * @author  Group 17
 *
 * Firmware entry point and deterministic state machine:
 *   Idle → Acquire → Compute → Classify → Report → Idle
 */

#include "main.h"
#include "sensors.h"
#include "dsp.h"
#include "comm.h"
#include "datalog.h"
#include <string.h>
#include <math.h>

/* ──────────────────────────────────────────────────────────────────────────
 * Peripheral Handles (defined by STM32CubeMX)
 * ────────────────────────────────────────────────────────────────────────── */
extern ADC_HandleTypeDef  hadc1;
extern DAC_HandleTypeDef  hdac1;
extern TIM_HandleTypeDef  htim2;    // DAC excitation timer
extern TIM_HandleTypeDef  htim6;    // ADC trigger timer
extern I2C_HandleTypeDef  hi2c1;    // LCD
extern UART_HandleTypeDef huart2;   // Debug (optional)

/* ──────────────────────────────────────────────────────────────────────────
 * State Variables
 * ────────────────────────────────────────────────────────────────────────── */
static volatile SystemState_t   g_state = STATE_IDLE;
static volatile OperatingMode_t g_mode  = MODE_IDENTIFICATION;
static volatile uint8_t         g_start_triggered = 0;
static uint32_t                 g_record_counter = 0;

/* Sensor buffers */
static float    g_cond_samples[CONDUCTIVITY_SAMPLES];
static int32_t  g_loadcell_samples[LOADCELL_SAMPLES];

/* Current measurement */
static RawSensorData_t         g_raw;
static FeatureVector_t         g_features;
static ClassificationResult_t  g_knn_result;
static ClassificationResult_t  g_nc_result;

/* Substance label table */
static const char *g_substance_labels[] = {
    "Distilled Water",
    "Saltwater (3.5%)",
    "Isopropyl Alcohol",
    "Vinegar",
    "Olive Oil",
    "Ethanol (70%)",
    "Milk",
    "Unknown"
};
static const uint8_t g_substance_count = 8;

/* Calibration constants (placeholder — set during calibration) */
static float g_tare_offset = 0.0f;

/* ──────────────────────────────────────────────────────────────────────────
 * GPIO EXTI Callback — Start Button
 * ────────────────────────────────────────────────────────────────────────── */
void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin) {
    if (GPIO_Pin == BTN_START_Pin) {
        if (g_state == STATE_IDLE) {
            g_start_triggered = 1;
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * State Handlers
 * ────────────────────────────────────────────────────────────────────────── */

static void State_Idle(void) {
    // Check for start trigger (button or USB command)
    PendingCommand_t cmd = Comm_GetPendingCommand();
    if (cmd.pending) {
        switch (cmd.command_code) {
            case CMD_START_MEASUREMENT:
                g_start_triggered = 1;
                Comm_SendAck(CMD_START_MEASUREMENT);
                break;
            case CMD_SET_MODE_IDENT:
                g_mode = MODE_IDENTIFICATION;
                Comm_SendAck(CMD_SET_MODE_IDENT);
                break;
            case CMD_SET_MODE_REF:
                g_mode = MODE_REFERENCE_COLLECTION;
                Comm_SendAck(CMD_SET_MODE_REF);
                break;
            case CMD_REQUEST_EXPORT:
                DataLog_ExportAll();
                Comm_SendAck(CMD_REQUEST_EXPORT);
                break;
            case CMD_REQUEST_STATUS:
                Comm_SendStatus(g_state, g_mode, DataLog_GetCount(), 0.0f);
                Comm_SendLabelTable(g_substance_labels, g_substance_count);
                Comm_SendAck(CMD_REQUEST_STATUS);
                break;
            default:
                break;
        }
    }

    if (g_start_triggered) {
        g_start_triggered = 0;
        g_state = STATE_ACQUIRE;
    }
}

static void State_Acquire(void) {
    // 1. Start temperature conversion first (750ms conversion time)
    DS18B20_StartConversion();

    // 2. Conductivity acquisition (AC excitation + ADC sampling)
    Conductivity_StartExcitation();
    Conductivity_AcquireSamples(g_cond_samples, CONDUCTIVITY_SAMPLES);
    Conductivity_StopExcitation();

    // 3. Load cell acquisition (wait for settling, then burst read)
    int32_t settled_val;
    uint8_t settled = LoadCell_WaitSettled(&settled_val, SETTLING_TIMEOUT_MS);
    if (settled) {
        LoadCell_AcquireBurst(g_loadcell_samples, LOADCELL_SAMPLES);
    } else {
        // Fill with last settled value (flagged invalid in Compute)
        for (int i = 0; i < LOADCELL_SAMPLES; i++) {
            g_loadcell_samples[i] = settled_val;
        }
    }

    // 4. Read temperature (conversion should be done by now)
    while (!DS18B20_IsConversionDone()) {
        // Brief busy-wait (overlapped with load cell acquisition)
    }
    g_raw.raw_temperature = DS18B20_ReadTemperature();

    // Store raw values
    g_raw.raw_conductivity_adc = DSP_ComputeRMS(g_cond_samples, CONDUCTIVITY_SAMPLES);

    float loadcell_float[LOADCELL_SAMPLES];
    for (int i = 0; i < LOADCELL_SAMPLES; i++) {
        loadcell_float[i] = (float)g_loadcell_samples[i];
    }
    g_raw.raw_loadcell_adc = DSP_ComputeMean(loadcell_float, LOADCELL_SAMPLES);

    g_state = STATE_COMPUTE;
}

static void State_Compute(void) {
    // Apply calibration to extract features
    g_features.conductivity_ms_cm = DSP_CalibratedConductivity(g_raw.raw_conductivity_adc);
    g_features.density_g_ml = DSP_CalibratedDensity(g_raw.raw_loadcell_adc, g_tare_offset);
    g_features.temperature_c = g_raw.raw_temperature;  // DS18B20 gives °C directly

    // Validate
    g_features.validity = DSP_ValidateFeatures(&g_features);

    g_state = STATE_CLASSIFY;
}

static void State_Classify(void) {
    if (g_features.validity == 0x07) {
        // All features valid — run both classifiers
        g_knn_result = KNN_Classify(&g_features);
        g_nc_result  = NC_Classify(&g_features);
    } else {
        // Invalid features — skip classification
        g_knn_result.predicted_index = g_substance_count - 1; // "Unknown"
        g_knn_result.confidence = 0.0f;
        g_nc_result.predicted_index = g_substance_count - 1;
        g_nc_result.confidence = 0.0f;
    }

    g_state = STATE_REPORT;
}

static void State_Report(void) {
    // Build measurement record
    MeasurementRecord_t record;
    record.record_number = g_record_counter++;
    record.timestamp_ms  = HAL_GetTick();
    record.raw           = g_raw;
    record.features      = g_features;
    record.knn_result    = g_knn_result;
    record.nc_result     = g_nc_result;
    record.model_version = FIRMWARE_VERSION;

    // Transmit to Android app
    Comm_SendMeasurement(&record);

    // Log to flash
    DataLog_Write(&record);

    // Return to idle
    g_state = STATE_IDLE;
}

/* ──────────────────────────────────────────────────────────────────────────
 * Main State Machine Loop
 * ────────────────────────────────────────────────────────────────────────── */

void StateMachine_Run(void) {
    switch (g_state) {
        case STATE_IDLE:    State_Idle();     break;
        case STATE_ACQUIRE: State_Acquire();  break;
        case STATE_COMPUTE: State_Compute();  break;
        case STATE_CLASSIFY: State_Classify(); break;
        case STATE_REPORT:  State_Report();   break;
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Entry Point
 * ────────────────────────────────────────────────────────────────────────── */

int main(void) {
    HAL_Init();
    SystemInit_Peripherals();

    // Initialize subsystems
    Conductivity_Init(&hdac1, &hadc1, &htim2);
    LoadCell_Init();
    DS18B20_Init();
    Comm_Init();
    DataLog_Init();

    // TODO: Load kNN/NC model parameters from flash
    // KNN_Init(ref_vectors, ref_labels, ref_count, 3, KNN_K);
    // NC_Init(centroids, centroid_labels, class_count, 3);

    // Send initial status + labels on USB connect
    Comm_SendStatus(g_state, g_mode, DataLog_GetCount(), 0.0f);
    Comm_SendLabelTable(g_substance_labels, g_substance_count);

    // Main loop
    while (1) {
        StateMachine_Run();
    }
}
