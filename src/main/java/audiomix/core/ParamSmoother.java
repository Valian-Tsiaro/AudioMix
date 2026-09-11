package audiomix.core;

/**
 * Click-free linear parameter smoother. Moves a value toward a volatile target
 * over a fixed ramp window (~10 ms). {@code nextValue()} is the only method
 * that advances the ramp; all others are safe to call from any thread.
 */
public final class ParamSmoother {

    private static final double SETTLE_EPS = 1e-12;

    private final double rampSamples;
    private volatile double target;
    private volatile double current;
    private volatile double step;
    private volatile double remaining;

    /**
     * Creates a smoother with a fixed ramp length.
     *
     * @param sampleRate sample rate in Hz (&gt; 0)
     * @param rampMs     ramp duration in milliseconds (&gt; 0)
     * @throws IllegalArgumentException if {@code sampleRate} or {@code rampMs} ≤ 0
     */
    public ParamSmoother(int sampleRate, double rampMs) {
        if (sampleRate <= 0 || rampMs <= 0) {
            throw new IllegalArgumentException("sampleRate=" + sampleRate + " rampMs=" + rampMs);
        }
        this.rampSamples = Math.max(1.0, rampMs * sampleRate / 1000.0);
    }

    /** Sets current and target immediately, cancelling any ramp in progress. Ignored if {@code v} is not finite. */
    public synchronized void setValue(double v) {
        if (!Double.isFinite(v)) return;
        current = v;
        target = v;
        step = 0;
        remaining = 0;
    }

    /**
     * Snaps a new target and starts a fresh ramp over the full ramp window.
     * Callable from any thread. Ignored if {@code v} is not finite.
     *
     * @param v new target value
     */
    public synchronized void setTarget(double v) {
        if (!Double.isFinite(v)) return;
        target = v;
        double diff = v - current;
        if (Math.abs(diff) <= SETTLE_EPS) {
            current = v;
            step = 0;
            remaining = 0;
        } else {
            step = diff / rampSamples;
            remaining = rampSamples;
        }
    }

    /** Returns the current value without advancing the ramp. */
    public double currentValue() {
        return current;
    }

    /**
     * Advances the ramp by one sample and returns the new current value.
     * Audio thread only.
     *
     * @return current value after one sample of movement
     */
    public double nextValue() {
        if (remaining <= 0) return current;
        current += step;
        remaining--;
        if ((step > 0 && current >= target) || (step < 0 && current <= target) || remaining <= 0) {
            current = target;
            remaining = 0;
        }
        return current;
    }

    /** Returns true once the current value matches the target within 1e-12. */
    public boolean isSettled() {
        return Math.abs(current - target) <= SETTLE_EPS;
    }
}