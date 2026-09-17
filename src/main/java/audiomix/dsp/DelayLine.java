package audiomix.dsp;

/**
 * Single-channel circular delay line with linear interpolation.
 * Internal buffer size is {@code maxSamples + 1} so reads at exactly
 * {@code maxSamples} are valid (delay equal to capacity).
 *
 * <p>Read semantics: {@link #readRaw(int)} and {@link #readInterpolated(double)}
 * read the sample written {@code delaySamples} steps before the current write
 * cursor. The minimum meaningful delay is 1 sample; delays of 0 return
 * uninitialized data (callers must guard against this).</p>
 */
public final class DelayLine {

    private final float[] buf;
    private final int size;   // maxSamples + 1
    private int pos;

    /**
     * Creates a delay line with the given capacity.
     *
     * @param maxSamples maximum delay in samples, must be &ge; 1
     * @throws IllegalArgumentException if {@code maxSamples < 1}
     */
    public DelayLine(int maxSamples) {
        if (maxSamples < 1) throw new IllegalArgumentException("maxSamples=" + maxSamples);
        this.size = maxSamples + 1;
        this.buf = new float[size];
    }

    /**
     * Writes one sample into the delay line.
     *
     * @param v sample value
     */
    public void write(float v) {
        buf[pos] = v;
        pos = (pos + 1) % size;
    }

    /**
     * Reads one sample at integer delay. A delay of {@code k} returns
     * the sample written {@code k} steps before the current write cursor
     * (i.e., the sample from {@code k} steps ago).
     *
     * @param delaySamples delay in whole samples; 1 &le; k &le; {@link #capacity()}
     * @return sample value
     * @throws IllegalArgumentException if {@code delaySamples} is &lt; 1 or &gt; capacity
     */
    public float readRaw(int delaySamples) {
        if (delaySamples < 1 || delaySamples > size - 1) {
            throw new IllegalArgumentException("delaySamples=" + delaySamples);
        }
        return buf[(pos - delaySamples + size) % size];
    }

    /**
     * Reads with linear interpolation for fractional delays.
     *
     * @param delaySamples delay in samples; 1.0 &le; d &le; {@link #capacity()}
     * @return interpolated sample
     * @throws IllegalArgumentException if d is NaN, &lt; 1.0, or exceeds capacity
     */
    public float readInterpolated(double delaySamples) {
        if (!Double.isFinite(delaySamples) || delaySamples < 1.0) {
            throw new IllegalArgumentException("delaySamples=" + delaySamples);
        }
        int k = (int) delaySamples;
        if (k > size - 1) throw new IllegalArgumentException("delaySamples=" + delaySamples);
        float a = readRaw(k);
        float frac = (float) (delaySamples - k);
        if (frac == 0.0f) return a;
        return a + frac * (readRaw(k + 1) - a);
    }

    /** Zeros the internal buffer and resets the write cursor. */
    public void reset() {
        java.util.Arrays.fill(buf, 0.0f);
        pos = 0;
    }

    /**
     * Maximum delay this line can hold.
     *
     * @return capacity in samples
     */
    public int capacity() {
        return size - 1;
    }
}
