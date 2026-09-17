package audiomix.dsp;

/**
 * Schroeder allpass filter: {@code y[n] = -g*x[n] + x[n-D] + g*y[n-D]}.
 * The delay line stores input history ({@code x[n]}), while the output
 * feedback {@code y[n-D]} is tracked via a small ring buffer of size D.
 *
 * <p>Feedback g must be in [0, 1). The filter preserves signal amplitude
 * at all frequencies (allpass property).</p>
 *
 * <p>Threading: parameter setters are safe to call from any thread.</p>
 */
public final class Allpass {

    private final DelayLine dl;
    private volatile int delaySamples;
    private volatile double feedback;
    private double[] yBuf;
    private int yPos;

    /**
     * Creates an allpass filter with the given maximum delay capacity.
     *
     * @param maxSamples maximum delay in samples, must be &ge; 1
     * @throws IllegalArgumentException if {@code maxSamples < 1}
     */
    public Allpass(int maxSamples) {
        this.dl = new DelayLine(maxSamples);
        this.yBuf = new double[1];
    }

    /**
     * Sets the delay in whole samples. Resets the output feedback ring
     * buffer and {@link #reset()} should be called first if stale samples
     * are undesirable.
     *
     * @param d delay in samples; 1 &le; d &le; capacity
     * @throws IllegalArgumentException if {@code d < 1} or {@code d > capacity}
     */
    public void setDelaySamples(int d) {
        if (d < 1 || d > dl.capacity()) {
            throw new IllegalArgumentException("delaySamples=" + d);
        }
        this.delaySamples = d;
        this.yBuf = new double[d];
        this.yPos = 0;
    }

    /**
     * Sets the feedback coefficient (Schroeder g).
     *
     * @param g feedback; 0 &le; g &lt; 1
     * @throws IllegalArgumentException if out of range
     */
    public void setFeedback(double g) {
        if (g < 0.0 || g >= 1.0) throw new IllegalArgumentException("feedback=" + g);
        this.feedback = g;
    }

    /**
     * Processes one sample through the allpass filter.
     * Writes x[n] into the delay line; reads x[n-D] from the delay line
     * and y[n-D] from the internal feedback ring buffer.
     *
     * @param in input sample
     * @return filtered output sample
     */
    public float process(float in) {
        int d = delaySamples;
        double g = feedback;

        float xnD = dl.readRaw(d);
        double ynD = yBuf[yPos];
        float out = (float) (-g * (double) in + (double) xnD + g * ynD);

        dl.write(in);
        yBuf[yPos] = out;
        yPos = (yPos + 1) % d;
        return out;
    }

    /** Resets filter state; parameters are retained. */
    public void reset() {
        dl.reset();
        java.util.Arrays.fill(yBuf, 0.0);
        yPos = 0;
    }
}
