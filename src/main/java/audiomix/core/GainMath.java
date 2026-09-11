package audiomix.core;

/** Utility class for audio gain and panning math. */
public final class GainMath {
    private GainMath() {}

    /**
     * Converts dBFS to linear amplitude.
     *
     * @param db gain in dB (may be negative)
     * @return linear amplitude ≥ 0
     */
    public static double dbToLinear(double db) {
        return Math.pow(10, db / 20.0);
    }

    /**
     * Converts linear amplitude to dBFS.
     *
     * @param lin linear amplitude (≤ 0 yields -∞)
     * @return dBFS, or -∞ if {@code lin ≤ 0}
     */
    public static double linearToDb(double lin) {
        return lin <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(lin);
    }

    /** Clamps {@code v} to {@code [lo, hi]}. */
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Equal-power pan law. Maps pan position to left/right gains
     * whose squares sum to unity.
     *
     * @param x pan position in {@code [-1.0, 1.0]} (clamped internally)
     * @return two-element array {@code {leftGain, rightGain}}
     */
    public static double[] equalPowerPan(double x) {
        x = clamp(x, -1.0, 1.0);
        double theta = (x + 1.0) * Math.PI / 4.0;
        return new double[] { Math.cos(theta), Math.sin(theta) };
    }
}
