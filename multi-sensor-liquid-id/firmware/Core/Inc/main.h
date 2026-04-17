/**
 * @file    main.h
 * @brief   Multi-Sensor Liquid Identification System — Main Header
 * @author  Group 17 (Johan Johnson, Frederick Hornberger, John McGuire)
 * @date    2026
 *
 * Target: STM32G431KBU6
 */

#ifndef __MAIN_H
#define __MAIN_H

#ifdef __cplusplus
extern "C" {
#endif

#include "stm32g4xx_hal.h"

/* ──────────────────────────────────────────────────────────────────────────
 * Pin Definitions
 * ────────────────────────────────────────────────────────────────────────── */

// Start button
#define BTN_START_Pin           GPIO_PIN_0
#define BTN_START_GPIO_Port     GPIOA

// HX711 Load Cell ADC
#define HX711_SCK_Pin           GPIO_PIN_1
#define HX711_SCK_GPIO_Port     GPIOA
#define HX711_DOUT_Pin          GPIO_PIN_2
#define HX711_DOUT_GPIO_Port    GPIOA

// DS18B20 1-Wire
#define ONEWIRE_Pin             GPIO_PIN_3
#define ONEWIRE_GPIO_Port       GPIOA

// LCD (I2C)
#define LCD_SDA_Pin             GPIO_PIN_7
#define LCD_SDA_GPIO_Port       GPIOB
#define LCD_SCL_Pin             GPIO_PIN_6
#define LCD_SCL_GPIO_Port       GPIOB

// Conductivity DAC output
#define COND_DAC_Pin            GPIO_PIN_4
#define COND_DAC_GPIO_Port      GPIOA

// Conductivity ADC input (from analog front end)
#define COND_ADC_Pin            GPIO_PIN_5
#define COND_ADC_GPIO_Port      GPIOA

/* ──────────────────────────────────────────────────────────────────────────
 * System Parameters
 * ────────────────────────────────────────────────────────────────────────── */

#define FIRMWARE_VERSION        1
#define CONDUCTIVITY_SAMPLES    100
#define LOADCELL_SAMPLES        100
#define EXCITATION_FREQ_HZ      1000
#define SINE_LUT_SIZE           64
#define MAX_SUBSTANCES          16
#define KNN_K                   5
#define MAX_REFERENCE_VECTORS   200
#define SETTLING_THRESHOLD      50
#define SETTLING_COUNT          5
#define SETTLING_TIMEOUT_MS     3000
#define DS18B20_CONV_TIME_MS    750
#define USB_PACKET_SYNC_1       0xAA
#define USB_PACKET_SYNC_2       0x55

/* ──────────────────────────────────────────────────────────────────────────
 * State Machine
 * ────────────────────────────────────────────────────────────────────────── */

typedef enum {
    STATE_IDLE      = 0,
    STATE_ACQUIRE   = 1,
    STATE_COMPUTE   = 2,
    STATE_CLASSIFY  = 3,
    STATE_REPORT    = 4
} SystemState_t;

typedef enum {
    MODE_IDENTIFICATION       = 0,
    MODE_REFERENCE_COLLECTION = 1
} OperatingMode_t;

/* ──────────────────────────────────────────────────────────────────────────
 * Data Structures
 * ────────────────────────────────────────────────────────────────────────── */

typedef struct {
    float raw_conductivity_adc;
    float raw_loadcell_adc;
    float raw_temperature;
} RawSensorData_t;

typedef struct {
    float conductivity_ms_cm;
    float density_g_ml;
    float temperature_c;
    uint8_t validity;           // bit0=cond, bit1=density, bit2=temp
} FeatureVector_t;

typedef struct {
    uint8_t  predicted_index;
    float    confidence;
} ClassificationResult_t;

typedef struct {
    uint32_t record_number;
    uint32_t timestamp_ms;
    RawSensorData_t raw;
    FeatureVector_t features;
    ClassificationResult_t knn_result;
    ClassificationResult_t nc_result;
    uint8_t model_version;
} MeasurementRecord_t;

/* ──────────────────────────────────────────────────────────────────────────
 * Function Prototypes
 * ────────────────────────────────────────────────────────────────────────── */

void SystemInit_Peripherals(void);
void StateMachine_Run(void);

#ifdef __cplusplus
}
#endif

#endif /* __MAIN_H */
