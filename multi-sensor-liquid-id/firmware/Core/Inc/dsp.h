/**
 * @file    dsp.h
 * @brief   Digital signal processing, feature extraction, and classification
 */

#ifndef __DSP_H
#define __DSP_H

#include "main.h"

/* ── Feature Extraction ── */
float DSP_ComputeRMS(const float *samples, uint16_t count);
float DSP_ComputeMean(const float *samples, uint16_t count);
float DSP_CalibratedConductivity(float rms_adc);
float DSP_CalibratedDensity(float mean_loadcell, float tare_offset);
uint8_t DSP_ValidateFeatures(const FeatureVector_t *features);

/* ── kNN Classifier ── */
void KNN_Init(const float *ref_vectors, const uint8_t *ref_labels,
              uint16_t ref_count, uint8_t feature_dim, uint8_t k);
ClassificationResult_t KNN_Classify(const FeatureVector_t *features);

/* ── Nearest-Centroid Classifier ── */
void NC_Init(const float *centroids, const uint8_t *labels,
             uint8_t class_count, uint8_t feature_dim);
ClassificationResult_t NC_Classify(const FeatureVector_t *features);

/* ── Normalization ── */
void Normalize_SetParams(const float *means, const float *stds, uint8_t dim);
void Normalize_Apply(float *features, uint8_t dim);

#endif /* __DSP_H */
