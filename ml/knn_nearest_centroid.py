"""
KNN and Nearest Centroid classification with scikit-learn.

Example scenario: identify a liquid from multi-sensor readings
(conductivity, density, temperature) — matching the Multi-Sensor
Liquid Identification System hardware. Swap the synthetic data
for your real sensor CSV when you have it.
"""

import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.neighbors import KNeighborsClassifier, NearestCentroid
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix

# ---------------------------------------------------------------
# Step 1: Get data as X (features) and y (labels)
# X shape = (n_samples, n_features), y shape = (n_samples,)
# Here we generate 60 samples per liquid around realistic values.
# ---------------------------------------------------------------
rng = np.random.default_rng(42)

# Typical readings per liquid: [conductivity mS/cm, density g/mL, temperature degC]
# Temperature hovers around room temp for every liquid, so it carries
# little class information on its own — it's included because the device
# measures it (useful for temperature-compensating the other readings).
liquid_profiles = {
    "water":      [0.5,   1.00, 22.5],
    "salt_water": [50.0,  1.03, 22.5],
    "vinegar":    [2.5,   1.01, 22.5],
    "isopropyl":  [0.002, 0.79, 22.5],
}
X_list, y_list = [], []
for liquid, profile in liquid_profiles.items():
    # Conductivity noise is proportional to the reading (~10%), like a
    # real probe; density and temperature noise are absolute.
    cond = profile[0] * rng.normal(1.0, 0.10, size=60)
    dens = rng.normal(profile[1], 0.005, size=60)
    temp = rng.normal(profile[2], 1.5, size=60)
    X_list.append(np.column_stack([cond, dens, temp]))
    y_list += [liquid] * 60

X = np.vstack(X_list)
y = np.array(y_list)

# ---------------------------------------------------------------
# Step 2: Split into training and test sets
# stratify=y keeps class proportions equal in both sets
# ---------------------------------------------------------------
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.25, random_state=42, stratify=y
)

# ---------------------------------------------------------------
# Step 3a: Log-transform conductivity
# Conductivity spans orders of magnitude (0.002 to 50 mS/cm), so on
# a linear scale salt water dominates the spread and StandardScaler
# squashes the water-vs-vinegar gap to nearly nothing. log10 turns
# "orders of magnitude" into evenly spaced steps: 0.002 -> -2.7,
# 0.5 -> -0.3, 2.5 -> 0.4, 50 -> 1.7.
# ---------------------------------------------------------------
def log_conductivity(X):
    X = np.asarray(X, dtype=float).copy()
    X[:, 0] = np.log10(X[:, 0])
    return X

X_train = log_conductivity(X_train)
X_test = log_conductivity(X_test)

# ---------------------------------------------------------------
# Step 3b: Scale the features
# Both algorithms use distances, so features must be on the same
# scale (temp ~22 would otherwise drown out density ~1).
# Fit the scaler on training data ONLY, then apply to both sets.
# ---------------------------------------------------------------
scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

# ---------------------------------------------------------------
# Step 4: Train the classifiers
# ---------------------------------------------------------------
knn = KNeighborsClassifier(n_neighbors=5)   # vote among 5 closest samples
knn.fit(X_train_scaled, y_train)

centroid = NearestCentroid()                # one mean point per class
centroid.fit(X_train_scaled, y_train)

# ---------------------------------------------------------------
# Step 5: Evaluate on the test set
# ---------------------------------------------------------------
for name, model in [("KNN (k=5)", knn), ("Nearest Centroid", centroid)]:
    y_pred = model.predict(X_test_scaled)
    print(f"=== {name} ===")
    print(f"Accuracy: {accuracy_score(y_test, y_pred):.3f}")
    print(classification_report(y_test, y_pred))
    print("Confusion matrix (rows=true, cols=predicted):")
    print(confusion_matrix(y_test, y_pred, labels=list(liquid_profiles)))
    print()

# ---------------------------------------------------------------
# Step 6: Predict an unknown sample
# Apply the SAME preprocessing as training: log conductivity first,
# then scale with the already-fitted scaler.
# ---------------------------------------------------------------
unknown = [[2.6, 1.012, 23.1]]  # mystery liquid readings
unknown_scaled = scaler.transform(log_conductivity(unknown))

print("Unknown sample:", unknown[0])
print("KNN says:             ", knn.predict(unknown_scaled)[0])
print("Nearest Centroid says:", centroid.predict(unknown_scaled)[0])

# KNN can also give confidence via neighbor vote proportions
proba = knn.predict_proba(unknown_scaled)[0]
for cls, p in zip(knn.classes_, proba):
    print(f"  P({cls}) = {p:.2f}")
