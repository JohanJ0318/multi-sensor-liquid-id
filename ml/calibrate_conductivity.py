"""
Fit the ADC -> conductivity conversion from calibration solution
measurements.

Model (voltage divider + series/electrode resistance):
    r = adc / (4095 - adc)
    sigma_mS_cm = A * r / (1 - B * r)

B captures the saturation seen at high conductivity (series wiring +
electrode polarization resistance). With one calibration point, B = 0
(pure divider). With two or more points, both constants are fitted by
least squares (the model is linear in A and B: sigma = A*r + B*(r*sigma)).

Readings are temperature-compensated to 25 C (~2%/C), since calibration
solutions are specified at 25 C.

Usage — one or more calibration points, each as adc:mS_cm:tempC
    python calibrate_conductivity.py 2535:1.413:24.0
    python calibrate_conductivity.py 2535:1.413:24.0 2905:12.88:26.9
"""

import sys

import numpy as np

ADC_FULL_SCALE = 4095.0
TEMP_COEFF_PER_C = 0.02  # ~2%/C, standard for aqueous solutions
REF_TEMP_C = 25.0


def parse_point(arg):
    try:
        adc_s, sigma_s, temp_s = arg.split(":")
        adc, sigma, temp = float(adc_s), float(sigma_s), float(temp_s)
    except ValueError:
        sys.exit(f"Bad point '{arg}' — expected adc:mS_cm:tempC, e.g. 2535:1.413:24.0")
    if not (0 < adc < ADC_FULL_SCALE):
        sys.exit(f"ADC value {adc} must be between 1 and 4094")
    return adc, sigma, temp


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)

    points = [parse_point(a) for a in sys.argv[1:]]

    ratios, sigmas_at_temp = [], []
    for adc, sigma_25, temp in points:
        sigma_t = sigma_25 * (1.0 + TEMP_COEFF_PER_C * (temp - REF_TEMP_C))
        ratios.append(adc / (ADC_FULL_SCALE - adc))
        sigmas_at_temp.append(sigma_t)

    r = np.array(ratios)
    s = np.array(sigmas_at_temp)

    if len(points) == 1:
        # Single point: pure divider model, B = 0
        A = float(s[0] / r[0])
        B = 0.0
    else:
        # sigma = A*r + B*(r*sigma) — linear least squares in (A, B)
        design = np.column_stack([r, r * s])
        (A, B), *_ = np.linalg.lstsq(design, s, rcond=None)
        A, B = float(A), float(B)

    print(f"Calibration constants:  A = {A:.6f} mS/cm   B = {B:.6f}")
    if B > 0:
        asymptote_r = 1.0 / B
        asymptote_adc = ADC_FULL_SCALE * asymptote_r / (1.0 + asymptote_r)
        print(f"Model asymptote near ADC {asymptote_adc:.0f} — readings above "
              "this are pegged/clamped, not proportional.")
    print()

    print("Fit check:")
    for (adc, sigma_25, temp), ri, si in zip(points, r, s):
        denom = max(1.0 - B * ri, 1e-6)
        pred = A * ri / denom
        err = 100.0 * (pred - si) / si
        print(f"  ADC {adc:6.0f} @ {temp:5.2f}C: label {sigma_25:.3f} -> "
              f"at-temp {si:.3f}, model {pred:.3f} mS/cm  ({err:+.1f}%)")

    print("\nFirmware constants (paste into main.c):")
    print(f"#define COND_CAL_A_MS_CM   {A:.6f}f")
    print(f"#define COND_CAL_B         {B:.6f}f")
    print("#define COND_MAX_MS_CM     20.0f")


if __name__ == "__main__":
    main()
