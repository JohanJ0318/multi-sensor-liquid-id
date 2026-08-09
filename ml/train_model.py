"""
Train the liquid-identification models (kNN + Nearest Centroid) from a CSV
of real sensor readings, evaluate them, and save the results.

Usage:
    python train_model.py sensor_data.csv
    python train_model.py water=water_session.csv vinegar=vinegar_session.csv ...

Accepts two CSV formats (mix freely, one or more files):

1. Simple format — header row, one row per measurement, label column included:
       conductivity_mS_cm,density_g_mL,temperature_C,label
       0.52,1.002,22.1,water

2. LiquidID Android app session export — pass as liquidname=file.csv, where
   liquidname is the true liquid measured in that session. The '#' metadata
   lines are skipped, the calibrated *_feature columns are used, and rows
   whose validity is not OK are dropped automatically.

Outputs (written next to this script):
    liquid_model.joblib  - trained models + scaler; reload with joblib.load()
    model_params.h       - C header with the model constants for the STM32
"""

import csv
import io
import sys
from pathlib import Path

import joblib
import numpy as np
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import cross_val_score, train_test_split
from sklearn.neighbors import KNeighborsClassifier, NearestCentroid
from sklearn.preprocessing import StandardScaler

FEATURES = ["conductivity_mS_cm", "density_g_mL", "temperature_C"]
K_CANDIDATES = [1, 3, 5, 7, 9]
OUT_DIR = Path(__file__).parent


# Column names in the LiquidID Android app's session export.
# NOTE: conductivity uses the RAW ADC counts, not the converted uS/cm.
# The mS/cm conversion amplifies electrode-drift noise ~6-17x near its
# asymptote (high-conductivity liquids), while the counts stay stable.
# Counts are transformed to r = adc/(4095-adc) so the later log10 step
# yields a feature monotone with true conductivity at both extremes.
APP_FEATURES = ["conductivity_raw", "density_feature", "temperature_feature"]
ADC_FULL_SCALE = 4095.0

# Floor for the conductivity ADC before the divider ratio + log10. Liquids at
# the sensor's bottom (glycerin) read 0 or 1 count; without a floor, log10 of
# a 0-count ratio blows up. Flooring at 0.5 count makes 0 and 1 count land
# adjacent (tight cluster) instead of smeared. Emitted to model_params.h so
# the firmware clamps identically — train/device MUST agree here.
COND_ADC_FLOOR = 0.5
COND_RATIO_FLOOR = COND_ADC_FLOOR / (ADC_FULL_SCALE - COND_ADC_FLOOR)

# Post-standardization feature weights: [conductivity, mass, temperature].
#
# Conductivity (3.0) does the work — it separates all six classes on its own
# at 100% CV accuracy.
#
# Mass (0.25) contributes real density information: glycerin reads ~124 g and
# isopropyl ~80 g against the ~100 g water group, which is genuine physics, not
# an artifact. The weight is deliberately small because the load cell drifts
# up to ±70 g between sessions. Measured drift tolerance on the banked data:
#
#     mass weight   accuracy @ ±40 g   accuracy @ ±70 g
#        0.25            100%               100%
#        0.50            100%              83.9%
#        1.00           80.6/64.5%        64.5/48.4%
#
# 0.25 is the largest weight that survives the full ±70 g drift intact, and it
# nudges the unknown-rejection radius up (0.286 -> 0.290). Raise it once the
# load-cell mount and amplifier are fixed.
#
# Temperature stays at 0.0 ON PURPOSE. Every liquid was measured at room
# temperature (19.6-21.1 C) in a single session each, so within-class spread is
# 0.02-0.15 C while between-class spread is ~1.5 C. That makes ambient
# temperature a near-perfect *session* fingerprint — it classifies at 72% on its
# own — but it carries no information about liquid identity, and the firmware
# already compensates conductivity to 25 C. Weighting it looks free in
# cross-validation and then fails in a warmer room: at [3, 1, 1] a +2 C ambient
# shift drops accuracy to 32%. Only give temperature weight after collecting
# each liquid across a real temperature range.
FEATURE_WEIGHTS = [3.0, 0.25, 0.0]


# ------------------------------------------------------------------
# Load and sanity-check the CSVs
# ------------------------------------------------------------------
def load_file(arg):
    """One CLI argument: either 'path.csv' or 'liquidname=path.csv'."""
    label = None
    if "=" in arg:
        label, path = arg.split("=", 1)
        label = label.strip()
    else:
        path = arg
    if not Path(path).exists():
        sys.exit(f"File not found: {path}")

    # Skip '#' metadata lines (present in app session exports)
    lines = [
        ln for ln in Path(path).read_text().splitlines()
        if ln.strip() and not ln.lstrip().startswith("#")
    ]
    rows = list(csv.DictReader(io.StringIO("\n".join(lines))))
    if not rows:
        sys.exit(f"{path}: no data rows.")

    cols = rows[0].keys()
    if all(c in cols for c in FEATURES):
        feat_cols = FEATURES
    elif all(c in cols for c in APP_FEATURES):
        feat_cols = APP_FEATURES
    else:
        sys.exit(
            f"{path}: unrecognized columns {list(cols)}\n"
            f"Expected {FEATURES} + label, or a LiquidID app session export."
        )

    X, y, dropped, deduped = [], [], 0, 0
    prev_key = None
    for lineno, row in enumerate(rows, start=2):
        # Guard against duplicated BLE deliveries (stacked notifications in
        # older app builds): identical sensor values + near-identical
        # timestamp = the same physical measurement heard more than once.
        key = (row.get("timestamp", "")[:19],
               row.get("conductivity_raw"), row.get("density_raw"),
               row.get("temperature_raw"))
        if key == prev_key:
            deduped += 1
            continue
        prev_key = key
        validity = row.get("validity")
        if validity is not None and validity.strip() != "OK":
            dropped += 1
            continue
        try:
            values = [float(row[c]) for c in feat_cols]
        except (ValueError, TypeError):
            sys.exit(f"{path} row {lineno}: non-numeric sensor value: {row}")
        if feat_cols is APP_FEATURES:
            # counts -> divider ratio r (monotone with conductivity,
            # avoids the mS/cm conversion's noise amplification). Floor at
            # COND_ADC_FLOOR so 0-count readings stay finite and adjacent to
            # 1-count readings (matches the firmware clamp).
            adc = min(max(values[0], COND_ADC_FLOOR), ADC_FULL_SCALE - 1.0)
            values[0] = adc / (ADC_FULL_SCALE - adc)
        X.append(values)
        row_label = label or (row.get("label") or "").strip()
        if not row_label:
            sys.exit(
                f"{path}: rows have no label. Either add a 'label' column, "
                f"or pass the file as liquidname={path}"
            )
        y.append(row_label)

    if dropped:
        print(f"  {path}: skipped {dropped} row(s) with validity != OK")
    if deduped:
        print(f"  {path}: removed {deduped} duplicated BLE delivery row(s)")
    return X, y, ("app" if feat_cols is APP_FEATURES else "simple")


def load_csv(args):
    X_all, y_all, formats = [], [], set()
    for arg in args:
        X, y, fmt = load_file(arg)
        X_all += X
        y_all += y
        formats.add(fmt)
    if len(formats) > 1:
        sys.exit(
            "Cannot mix app session exports with simple-format CSVs: their "
            "conductivity columns are in different units (ADC ratio vs mS/cm)."
        )

    X, y = np.array(X_all), np.array(y_all)
    if len(X) == 0:
        sys.exit("No usable data rows found.")
    if (X[:, 0] <= 0).any():
        # Near-zero conductors (isopropyl, distilled water) can legitimately
        # read 0. Clamp to half the smallest positive reading so the log
        # transform stays defined while keeping them at the bottom of the scale.
        positive = X[X[:, 0] > 0, 0]
        floor = (positive.min() / 2.0) if len(positive) else 1.0
        n = int((X[:, 0] <= 0).sum())
        print(f"Note: clamped {n} zero-conductivity row(s) to {floor:.4g} "
              "(low-conductivity liquid readings)")
        X[X[:, 0] <= 0, 0] = floor

    print(f"Loaded {len(X)} measurements from {len(args)} file(s)")
    classes, counts = np.unique(y, return_counts=True)
    for cls, n in zip(classes, counts):
        flag = "  <-- LOW, collect more (aim for 30+)" if n < 20 else ""
        print(f"  {cls:15s} {n:4d} samples{flag}")
    return X, y


# ------------------------------------------------------------------
# Preprocessing: conductivity spans orders of magnitude, so log it
# before scaling (see knn_nearest_centroid.py for the demonstration)
# ------------------------------------------------------------------
def log_conductivity(X):
    X = np.asarray(X, dtype=float).copy()
    X[:, 0] = np.log10(X[:, 0])
    return X


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    X, y = load_csv(sys.argv[1:])

    if len(np.unique(y)) < 2:
        sys.exit(
            "\nParsing succeeded, but training needs at least 2 different "
            "liquids.\n(Single-file pipeline check: PASSED)"
        )

    min_class = np.unique(y, return_counts=True)[1].min()
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.25, random_state=42, stratify=y
    )

    X_train_l = log_conductivity(X_train)
    X_test_l = log_conductivity(X_test)
    scaler = StandardScaler()
    w = np.array(FEATURE_WEIGHTS)
    X_train_s = scaler.fit_transform(X_train_l) * w
    X_test_s = scaler.transform(X_test_l) * w

    # Unknown-rejection threshold: half the smallest distance between any
    # two points of DIFFERENT classes (in weighted feature space). A query
    # farther than this from every training point sits in the no-man's-land
    # between class bands -> report UNKNOWN instead of guessing.
    diff = X_train_s[:, None, :] - X_train_s[None, :, :]
    d2 = (diff ** 2).sum(axis=-1)
    inter_mask = y_train[:, None] != y_train[None, :]
    min_inter = float(np.sqrt(d2[inter_mask].min()))
    reject_d2 = (0.5 * min_inter) ** 2
    print(f"\nUnknown-rejection radius: {0.5 * min_inter:.3f} "
          f"(half the closest inter-class gap of {min_inter:.3f})")

    # ---- pick k by cross-validation on the training set ----
    cv = min(5, int(min_class * 0.75)) or 2
    print(f"\nChoosing k via {cv}-fold cross-validation:")
    best_k, best_score = None, -1.0
    for k in K_CANDIDATES:
        if k > len(X_train) - 1:
            break
        score = cross_val_score(
            KNeighborsClassifier(n_neighbors=k), X_train_s, y_train, cv=cv
        ).mean()
        print(f"  k={k}: mean accuracy {score:.3f}")
        if score > best_score:
            best_k, best_score = k, score
    print(f"Selected k={best_k}")

    # ---- final training ----
    knn = KNeighborsClassifier(n_neighbors=best_k).fit(X_train_s, y_train)
    centroid = NearestCentroid().fit(X_train_s, y_train)

    # ---- held-out evaluation ----
    for name, model in [(f"kNN (k={best_k})", knn), ("Nearest Centroid", centroid)]:
        y_pred = model.predict(X_test_s)
        print(f"\n=== {name} — held-out test set ===")
        print(f"Accuracy: {accuracy_score(y_test, y_pred):.3f}")
        print(classification_report(y_test, y_pred, zero_division=0))
        print("Confusion matrix (rows=true, cols=predicted):")
        print(confusion_matrix(y_test, y_pred, labels=list(knn.classes_)))
        print("Label order:", list(knn.classes_))

    # ---- save the model bundle ----
    bundle = {
        "knn": knn,
        "nearest_centroid": centroid,
        "scaler": scaler,
        "k": best_k,
        "classes": list(knn.classes_),
        "features": FEATURES,
        "preprocessing": ("app exports: conductivity = log10(adc/(4095-adc)); "
                          "simple CSVs: log10(mS/cm); then StandardScaler, "
                          "then FEATURE_WEIGHTS"),
        "feature_weights": FEATURE_WEIGHTS,
        "reject_d2": reject_d2,
    }
    model_path = OUT_DIR / "liquid_model.joblib"
    joblib.dump(bundle, model_path)
    print(f"\nSaved model to {model_path}")

    # ---- export C constants for the STM32 firmware ----
    header_path = OUT_DIR / "model_params.h"
    write_c_header(header_path, knn, centroid, scaler, best_k, X_train_s,
                   y_train, reject_d2)
    print(f"Wrote firmware constants to {header_path}")


def write_c_header(path, knn, centroid, scaler, k, X_train_s, y_train, reject_d2):
    classes = list(knn.classes_)
    label_idx = np.array([classes.index(c) for c in y_train], dtype=int)

    def c_array(arr, fmt="{:.6f}f"):
        return ", ".join(fmt.format(v) for v in arr)

    lines = [
        "/* Auto-generated by train_model.py - do not edit by hand.",
        " *",
        " * Feature order: log10(r), weight_or_density, temperature_C",
        " *   where r = conductivity_adc / (4095 - conductivity_adc)",
        " *   (raw ADC counts, NOT the converted mS/cm - counts are more",
        " *    stable against electrode drift at high conductivity)",
        " * Firmware pipeline per measurement:",
        " *   1. r = (float)adc / (4095.0f - (float)adc);",
        " *      if (r < COND_RATIO_FLOOR) r = COND_RATIO_FLOOR;",
        " *      f[0] = log10f(r); f[1] = weight; f[2] = temperature;",
        " *   2. for i in 0..2: f[i] = (f[i] - SCALER_MEAN[i]) / SCALER_SCALE[i];",
        " *   3. for i in 0..2: f[i] *= FEATURE_WEIGHTS[i];",
        " *   4. kNN: vote among K nearest TRAIN_POINTS (squared Euclidean);",
        " *      Nearest Centroid: argmin distance to CENTROIDS.",
        " *   5. If the best squared distance exceeds KNN_REJECT_D2, report",
        " *      class 255 (UNKNOWN) instead of guessing.",
        " * TRAIN_POINTS and CENTROIDS are stored in weighted feature space.",
        " */",
        "#ifndef MODEL_PARAMS_H",
        "#define MODEL_PARAMS_H",
        "",
        "#define NUM_FEATURES 3",
        f"#define NUM_CLASSES {len(classes)}",
        f"#define KNN_K {k}",
        f"#define NUM_TRAIN_POINTS {len(X_train_s)}",
        "",
        "/* Conductivity ratio floor (shared with train_model.py) so a "
        "0-count reading is clamped identically on device and in training. */",
        f"#define COND_RATIO_FLOOR {COND_RATIO_FLOOR:.8f}f",
        "",
        "/* Uppercase for the OLED font; underscores become spaces. */",
        "static const char *const CLASS_NAMES[NUM_CLASSES] = {",
        "    " + ", ".join(f'"{c.upper().replace("_", " ")}"' for c in classes),
        "};",
        "",
        "static const float SCALER_MEAN[NUM_FEATURES] = {"
        + c_array(scaler.mean_) + "};",
        "static const float SCALER_SCALE[NUM_FEATURES] = {"
        + c_array(scaler.scale_) + "};",
        "static const float FEATURE_WEIGHTS[NUM_FEATURES] = {"
        + c_array(FEATURE_WEIGHTS) + "};",
        "",
        "/* Unknown-rejection: best squared distance above this -> class 255 */",
        f"#define KNN_REJECT_D2 {reject_d2:.6f}f",
        "",
        "/* Class centroids in scaled feature space, row = class index */",
        "static const float CENTROIDS[NUM_CLASSES][NUM_FEATURES] = {",
    ]
    for row in centroid.centroids_:
        lines.append("    {" + c_array(row) + "},")
    lines += [
        "};",
        "",
        "/* kNN reference set in scaled feature space */",
        "static const float TRAIN_POINTS[NUM_TRAIN_POINTS][NUM_FEATURES] = {",
    ]
    for row in X_train_s:
        lines.append("    {" + c_array(row) + "},")
    lines += [
        "};",
        "",
        "static const unsigned char TRAIN_LABELS[NUM_TRAIN_POINTS] = {",
        "    " + ", ".join(str(i) for i in label_idx),
        "};",
        "",
        "#endif /* MODEL_PARAMS_H */",
        "",
    ]
    path.write_text("\n".join(lines))


if __name__ == "__main__":
    main()
