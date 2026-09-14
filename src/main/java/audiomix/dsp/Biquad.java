package audiomix.dsp;

/**
 * One biquad section designed from the RBJ Audio-EQ-Cookbook formulas,
 * processed in Direct Form 1 with double internal state.
 *
 * <p>Threading: {@link #configure} and {@link #reset} are not safe to call
 * while another thread is in {@link #process}; the audio path must serialize
 * per-instance access.
 */
public final class Biquad {

    private double b0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    /**
     * Designs the coefficients for the given response.
     *
     * @param type       filter shape
     * @param freq       center or corner frequency in Hz, exclusive of 0 and Nyquist
     * @param gainDb     boost or cut in dB, only used by PEAK and the shelves
     * @param q          quality factor, must be positive
     * @param sampleRate project sample rate in Hz
     * @throws IllegalArgumentException if {@code freq <= 0}, {@code freq >= sampleRate/2}, or {@code q <= 0}
     */
    public void configure(EqType type, double freq, double gainDb, double q, int sampleRate) {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        if (!(freq > 0.0 && freq < sampleRate / 2.0) || !(q > 0.0)) {
            throw new IllegalArgumentException(
                    "freq=" + freq + " q=" + q + " rate=" + sampleRate);
        }
        double w0 = 2.0 * Math.PI * freq / sampleRate;
        double cos = Math.cos(w0);
        double sin = Math.sin(w0);

        Coefs c = switch (type) {
            case HIGHPASS -> {
                double alpha = sin / (2.0 * q);
                yield new Coefs(
                        1.0 + alpha,
                        (1.0 + cos) / 2.0,
                        -(1.0 + cos),
                        (1.0 + cos) / 2.0,
                        -2.0 * cos,
                        1.0 - alpha);
            }
            case LOWPASS -> {
                double alpha = sin / (2.0 * q);
                yield new Coefs(
                        1.0 + alpha,
                        (1.0 - cos) / 2.0,
                        1.0 - cos,
                        (1.0 - cos) / 2.0,
                        -2.0 * cos,
                        1.0 - alpha);
            }
            case NOTCH -> new Coefs(
                    1.0 + sin / (2.0 * q),
                    1.0,
                    -2.0 * cos,
                    1.0,
                    -2.0 * cos,
                    1.0 - sin / (2.0 * q));
            case PEAK -> {
                double a = Math.pow(10.0, gainDb / 40.0);
                double alpha = sin / (2.0 * q);
                yield new Coefs(
                        1.0 + alpha / a,
                        1.0 + alpha * a,
                        -2.0 * cos,
                        1.0 - alpha * a,
                        -2.0 * cos,
                        1.0 - alpha / a);
            }
            case LOW_SHELF -> {
                double a = Math.pow(10.0, gainDb / 40.0);
                double sqrtA = Math.sqrt(2.0 * a) * sin;
                yield new Coefs(
                        (a + 1.0) + (a - 1.0) * cos + sqrtA,
                        a * ((a + 1.0) - (a - 1.0) * cos + sqrtA),
                        2.0 * a * ((a - 1.0) - (a + 1.0) * cos),
                        a * ((a + 1.0) - (a - 1.0) * cos - sqrtA),
                        -2.0 * ((a - 1.0) + (a + 1.0) * cos),
                        (a + 1.0) + (a - 1.0) * cos - sqrtA);
            }
            case HIGH_SHELF -> {
                double a = Math.pow(10.0, gainDb / 40.0);
                double sqrtA = Math.sqrt(2.0 * a) * sin;
                yield new Coefs(
                        (a + 1.0) - (a - 1.0) * cos + sqrtA,
                        a * ((a + 1.0) + (a - 1.0) * cos + sqrtA),
                        -2.0 * a * ((a - 1.0) + (a + 1.0) * cos),
                        a * ((a + 1.0) + (a - 1.0) * cos - sqrtA),
                        2.0 * ((a - 1.0) - (a + 1.0) * cos),
                        (a + 1.0) - (a - 1.0) * cos - sqrtA);
            }
        };
        this.b0 = c.b0() / c.a0();
        this.b1 = c.b1() / c.a0();
        this.b2 = c.b2() / c.a0();
        this.a1 = c.a1() / c.a0();
        this.a2 = c.a2() / c.a0();
    }

    /**
     * Processes one sample.
     *
     * @param in input sample, nominal range +/-1.0
     * @return filtered sample, nominal range +/-1.0
     */
    public float process(float in) {
        double x0 = in;
        double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;
        return (float) y0;
    }

    /** Clears filter state; the configured coefficients are retained. */
    public void reset() {
        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
    }

    private record Coefs(double a0, double b0, double b1, double b2, double a1, double a2) {
    }
}
