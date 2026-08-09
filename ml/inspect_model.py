"""
Open liquid_model.joblib and print what's inside.

Usage:
    python inspect_model.py
"""

from pathlib import Path

import joblib
import numpy as np

bundle = joblib.load(Path(__file__).parent / "liquid_model.joblib")

print("Keys in the model file:", list(bundle))
print()
print("Classes:        ", bundle["classes"])
print("Features:       ", bundle["features"])
print("Preprocessing:  ", bundle["preprocessing"])
print("Chosen k:       ", bundle["k"])
print()

scaler = bundle["scaler"]
print("Scaler mean (log-cond, density, temp):", np.round(scaler.mean_, 4))
print("Scaler scale (std devs):              ", np.round(scaler.scale_, 4))
print()

centroid = bundle["nearest_centroid"]
print("Nearest Centroid — one learned point per class (scaled space):")
for cls, c in zip(centroid.classes_, centroid.centroids_):
    print(f"  {cls:12s} {np.round(c, 3)}")
print()

knn = bundle["knn"]
print(f"kNN — memorized training set: {knn._fit_X.shape[0]} points, "
      f"{knn._fit_X.shape[1]} features each")
print("First 5 stored points (scaled space):")
print(np.round(knn._fit_X[:5], 3))
print()

# The whole point of a saved model: predict without retraining.
example = np.array([[2.6, 1.012, 23.1]], dtype=float)  # cond, density, temp
example[:, 0] = np.log10(example[:, 0])
example_scaled = scaler.transform(example)
print("Example prediction for [2.6 mS/cm, 1.012 g/mL, 23.1 C]:")
print("  kNN:             ", knn.predict(example_scaled)[0])
print("  Nearest Centroid:", centroid.predict(example_scaled)[0])
