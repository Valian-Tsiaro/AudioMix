package audiomix.dsp;

/**
 * Schroeder comb filter with one-pole damping in the feedback path.
 * Implements {@code y[n] = x[n] + f * lp(y[n-D])} where {@code lp} is a
 * one-pole lowpass whose coefficient derives from the damping parameter.
 *
 * <p>Damping 0.0 means pure feedback (no filtering); damping 1.0 freezes
 * the feedback path. Feedback must be in [0, 1).</p>
 *
 * <p>Threading: parameter setters are safe to call from any thread;
 * they store values consumed at block boundaries.</p>
 */
public final class Comb {

    private final DelayLine dl;
    private volatile int delaySamples;
    private volatile double feedback;
    private volatile double damping;
    private double lpState;

    /**
     * Creates a comb filter with the given maximum delay capacity.
     *
     * @param maxSamples maximum delay in samples, must be &ge; 1
     * @throws IllegalArgumentException if {@code maxSamples < 1}
     */
    public Comb(int maxSamples) {
        this.dl = new DelayLine(maxSamples);
    }

    /**
     * Sets the delay in whole samples. Does not clear filter history;
     * callers should {@link #reset()} first if stale samples are undesirable.
     *
     * @param d delay in samples; 1 &le; d &le; capacity
     * @throws IllegalArgumentException if {@code d < 1} or {@code d > capacity}
     */
    public void setDelaySamples(int d) {
        if (d < 1 || d > dl.capacity()) {
            throw new IllegalArgumentException("delaySamples=" + d);
        }
        this.delaySamples = d;
    }

    /**
     * Returns the current delay in samples.
     *
     * @return delay in samples
     */
    public int getDelaySamples() {
        return delaySamples;
    }

    /**
     * Sets the feedback coefficient.
     *
     * @param f feedback; 0 &le; f &lt; 1
     * @throws IllegalArgumentException if out of range
     */
    public void setFeedback(double f) {
        if (f < 0.0 || f >= 1.0) throw new IllegalArgumentException("feedback=" + f);
        this.feedback = f;
    }

    /**
     * Sets the damping amount. 0.0 = pure feedback (no filtering),
     * 1.0 = maximum damping (feedback path fully lowpassed).
     *
     * @param d damping; 0 &le; d &le; 1
     * @throws IllegalArgumentException if out of range
     */
    public void setDamping(double d) {
        if (d < 0.0 || d > 1.0) throw new IllegalArgumentException("damping=" + d);
        this.damping = d;
    }

    /**
     * Processes one sample through the comb filter.
     *
     * @param in input sample
     * @return filtered output sample
     */
    public float process(float in) {
        int d = delaySamples;
        double fb = feedback;
        double coeff = 1.0 - damping;

        float delayed = dl.readRaw(d);
        lpState += coeff * ((double) delayed - lpState);
        float out = (float) ((double) in + fb * lpState);
        dl.write(out);
        return out;
    }

    /** Resets filter state; parameters are retained. */
    public void reset() {
        dl.reset();
        lpState = 0.0;
    }
}
