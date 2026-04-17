/**
 * @file    dsp.c
 * @brief   Signal processing, feature extraction, kNN and nearest-centroid classifiers
 */

#include "dsp.h"
#include <math.h>
#include <string.h>
#include <float.h>

/* ──────────────────────────────────────────────────────────────────────────
 * Calibration Constants (set during calibration procedure)
 * ────────────────────────────────────────────────────────────────────────── */
static float g_cond_cal_factor  = 1.0f;  // ADC RMS → mS/cm
static float g_cond_cal_offset  = 0.0f;
static float g_mass_cal_factor  = 1.0f;  // raw → grams
static float g_known_volume_ml  = 10.0f; // sample container volume

/* ──────────────────────────────────────────────────────────────────────────
 * Normalization Parameters
 * ────────────────────────────────────────────────────────────────────────── */
static float g_norm_means[3] = {0};
static float g_norm_stds[3]  = {1, 1, 1};
static uint8_t g_norm_dim    = 3;

/* ──────────────────────────────────────────────────────────────────────────
 * kNN Model Storage
 * ────────────────────────────────────────────────────────────────────────── */
static const float  *g_knn_ref_vectors  = NULL;
static const uint8_t *g_knn_ref_labels  = NULL;
static uint16_t       g_knn_ref_count   = 0;
static uint8_t        g_knn_feat_dim    = 3;
static uint8_t        g_knn_k           = 5;

/* ──────────────────────────────────────────────────────────────────────────
 * Nearest-Centroid Model Storage
 * ────────────────────────────────────────────────────────────────────────── */
static const float  *g_nc_centroids     = NULL;
static const uint8_t *g_nc_labels       = NULL;
static uint8_t        g_nc_class_count  = 0;
static uint8_t        g_nc_feat_dim     = 3;

/* ──────────────────────────────────────────────────────────────────────────
 * Feature Extraction
 * ────────────────────────────────────────────────────────────────────────── */

float DSP_ComputeRMS(const float *samples, uint16_t count) {
    if (count == 0) return 0.0f;
    float sum_sq = 0.0f;
    for (uint16_t i = 0; i < count; i++) {
        sum_sq += samples[i] * samples[i];
    }
    return sqrtf(sum_sq / (float)count);
}

float DSP_ComputeMean(const float *samples, uint16_t count) {
    if (count == 0) return 0.0f;
    float sum = 0.0f;
    for (uint16_t i = 0; i < count; i++) {
        sum += samples[i];
    }
    return sum / (float)count;
}

float DSP_CalibratedConductivity(float rms_adc) {
    return (rms_adc * g_cond_cal_factor) + g_cond_cal_offset;
}

float DSP_CalibratedDensity(float mean_loadcell, float tare_offset) {
    float mass_g = (mean_loadcell - tare_offset) * g_mass_cal_factor;
    if (g_known_volume_ml <= 0.0f) return 0.0f;
    return mass_g / g_known_volume_ml;
}

uint8_t DSP_ValidateFeatures(const FeatureVector_t *f) {
    uint8_t valid = 0;

    // Conductivity: expect 0–200 mS/cm
    if (f->conductivity_ms_cm >= 0.0f && f->conductivity_ms_cm <= 200.0f) {
        valid |= 0x01;
    }

    // Density: expect 0.5–2.0 g/mL
    if (f->density_g_ml >= 0.5f && f->density_g_ml <= 2.0f) {
        valid |= 0x02;
    }

    // Temperature: expect -10–100 °C
    if (f->temperature_c >= -10.0f && f->temperature_c <= 100.0f) {
        valid |= 0x04;
    }

    return valid;
}

/* ──────────────────────────────────────────────────────────────────────────
 * Normalization
 * ────────────────────────────────────────────────────────────────────────── */

void Normalize_SetParams(const float *means, const float *stds, uint8_t dim) {
    memcpy(g_norm_means, means, dim * sizeof(float));
    memcpy(g_norm_stds, stds, dim * sizeof(float));
    g_norm_dim = dim;
}

void Normalize_Apply(float *features, uint8_t dim) {
    for (uint8_t i = 0; i < dim && i < g_norm_dim; i++) {
        if (g_norm_stds[i] > 1e-8f) {
            features[i] = (features[i] - g_norm_means[i]) / g_norm_stds[i];
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Euclidean Distance (shared by both classifiers)
 * ────────────────────────────────────────────────────────────────────────── */

static float euclidean_dist(const float *a, const float *b, uint8_t dim) {
    float sum = 0.0f;
    for (uint8_t i = 0; i < dim; i++) {
        float d = a[i] - b[i];
        sum += d * d;
    }
    return sqrtf(sum);
}

/* ──────────────────────────────────────────────────────────────────────────
 * kNN Classifier
 * ────────────────────────────────────────────────────────────────────────── */

void KNN_Init(const float *ref_vectors, const uint8_t *ref_labels,
              uint16_t ref_count, uint8_t feature_dim, uint8_t k) {
    g_knn_ref_vectors = ref_vectors;
    g_knn_ref_labels  = ref_labels;
    g_knn_ref_count   = ref_count;
    g_knn_feat_dim    = feature_dim;
    g_knn_k           = k;
}

ClassificationResult_t KNN_Classify(const FeatureVector_t *features) {
    ClassificationResult_t result = {0, 0.0f};

    if (g_knn_ref_vectors == NULL || g_knn_ref_count == 0) {
        return result;
    }

    // Build feature array and normalize
    float feat[3] = {
        features->conductivity_ms_cm,
        features->density_g_ml,
        features->temperature_c
    };
    Normalize_Apply(feat, g_knn_feat_dim);

    // Compute distances to all reference vectors
    // Use insertion sort to maintain top-k nearest
    float  k_dists[KNN_K];
    uint8_t k_labels_arr[KNN_K];

    for (uint8_t i = 0; i < g_knn_k; i++) {
        k_dists[i] = FLT_MAX;
        k_labels_arr[i] = 0;
    }

    for (uint16_t i = 0; i < g_knn_ref_count; i++) {
        const float *ref = &g_knn_ref_vectors[i * g_knn_feat_dim];
        float dist = euclidean_dist(feat, ref, g_knn_feat_dim);

        // Insert into sorted k-nearest list
        if (dist < k_dists[g_knn_k - 1]) {
            k_dists[g_knn_k - 1] = dist;
            k_labels_arr[g_knn_k - 1] = g_knn_ref_labels[i];

            // Bubble up
            for (int j = g_knn_k - 1; j > 0; j--) {
                if (k_dists[j] < k_dists[j - 1]) {
                    float tmp_d = k_dists[j];
                    k_dists[j] = k_dists[j - 1];
                    k_dists[j - 1] = tmp_d;
                    uint8_t tmp_l = k_labels_arr[j];
                    k_labels_arr[j] = k_labels_arr[j - 1];
                    k_labels_arr[j - 1] = tmp_l;
                } else {
                    break;
                }
            }
        }
    }

    // Majority vote
    uint8_t votes[MAX_SUBSTANCES] = {0};
    for (uint8_t i = 0; i < g_knn_k; i++) {
        if (k_labels_arr[i] < MAX_SUBSTANCES) {
            votes[k_labels_arr[i]]++;
        }
    }

    uint8_t best_label = 0;
    uint8_t best_count = 0;
    for (uint8_t i = 0; i < MAX_SUBSTANCES; i++) {
        if (votes[i] > best_count) {
            best_count = votes[i];
            best_label = i;
        }
    }

    result.predicted_index = best_label;
    result.confidence = (float)best_count / (float)g_knn_k;

    return result;
}

/* ──────────────────────────────────────────────────────────────────────────
 * Nearest-Centroid Classifier
 * ────────────────────────────────────────────────────────────────────────── */

void NC_Init(const float *centroids, const uint8_t *labels,
             uint8_t class_count, uint8_t feature_dim) {
    g_nc_centroids   = centroids;
    g_nc_labels      = labels;
    g_nc_class_count = class_count;
    g_nc_feat_dim    = feature_dim;
}

ClassificationResult_t NC_Classify(const FeatureVector_t *features) {
    ClassificationResult_t result = {0, 0.0f};

    if (g_nc_centroids == NULL || g_nc_class_count == 0) {
        return result;
    }

    float feat[3] = {
        features->conductivity_ms_cm,
        features->density_g_ml,
        features->temperature_c
    };
    Normalize_Apply(feat, g_nc_feat_dim);

    float min_dist = FLT_MAX;
    float second_min_dist = FLT_MAX;
    uint8_t best_class = 0;

    for (uint8_t i = 0; i < g_nc_class_count; i++) {
        const float *centroid = &g_nc_centroids[i * g_nc_feat_dim];
        float dist = euclidean_dist(feat, centroid, g_nc_feat_dim);

        if (dist < min_dist) {
            second_min_dist = min_dist;
            min_dist = dist;
            best_class = i;
        } else if (dist < second_min_dist) {
            second_min_dist = dist;
        }
    }

    result.predicted_index = g_nc_labels[best_class];

    // Confidence = 1 - (nearest / second_nearest)
    // Higher when nearest is much closer than second nearest
    if (second_min_dist > 1e-8f) {
        result.confidence = 1.0f - (min_dist / second_min_dist);
        if (result.confidence < 0.0f) result.confidence = 0.0f;
    } else {
        result.confidence = 1.0f;
    }

    return result;
}
