package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Effect;
import audiomix.dsp.DelayLine;

/**
 * Stereo or mono delay with feedback and independent wet/dry mix.
 * Delay time changes take effect at block boundaries (no time smoothing).
 *
 * <p>Thread safety: builder setters may be called from any thread at any
 * time (volatile, consumed at block boundaries). {@link #process} runs on
 * the audio/render thread. Delays shorter than one sample render as silence
 * in the wet path.</p>
 *
 * <p>If the processed buffer's channel count changes, the internal delay
 * lines are reallocated and their state (including the draining tail)
 * is lost.</p>
 */
public final class Delay implements Effect {

    private final int sampleRate;
    private final double maxDelayMs;
    private final int maxDelaySamples;

    private volatile double delayMs = 250.0;
    private volatile double feedback = 0.0;
    private volatile double wet = 1.0;
    private volatile double dry = 1.0;

    private DelayLine[] lines;
    private int channels;
    private long quietSamples;

    /**
     * Creates a delay effect with configurable maximum delay.
     *
     * @param sampleRate project sample rate in Hz, must be positive
     * @param maxDelayMs maximum delay in milliseconds, must be positive
     * @throws IllegalArgumentException if parameters are invalid
     */
    public Delay(int sampleRate, double maxDelayMs) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (!Double.isFinite(maxDelayMs) || maxDelayMs <= 0)
            throw new IllegalArgumentException("maxDelayMs=" + maxDelayMs);
        this.sampleRate = sampleRate;
        this.maxDelayMs = maxDelayMs;
        this.maxDelaySamples = (int) Math.ceil(maxDelayMs * sampleRate / 1000.0);
    }

    /**
     * Creates a delay effect with a 1000 ms maximum delay.
     *
     * @param sampleRate project sample rate in Hz, must be positive
     * @throws IllegalArgumentException if sampleRate &le; 0
     */
    public Delay(int sampleRate) {
        this(sampleRate, 1000.0);
    }

    /**
     * Sets delay time in milliseconds.
     *
     * @param ms delay, must be &gt; 0 and &le; maxDelayMs
     * @return this
     * @throws IllegalArgumentException if ms is invalid or exceeds maxDelayMs
     */
    public Delay delayMs(double ms) {
        if (!Double.isFinite(ms) || ms <= 0 || ms > maxDelayMs)
            throw new IllegalArgumentException("delayMs=" + ms + " max=" + maxDelayMs);
        this.delayMs = ms;
        return this;
    }

    /**
     * Sets feedback amount.
     *
     * @param f 0..0.95
     * @return this
     * @throws IllegalArgumentException if f out of range
     */
    public Delay feedback(double f) {
        if (!Double.isFinite(f) || f < 0.0 || f > 0.95)
            throw new IllegalArgumentException("feedback=" + f);
        this.feedback = f;
        return this;
    }

    /**
     * Sets wet gain.
     *
     * @param w wet multiplier applied to the delayed signal
     * @return this
     * @throws IllegalArgumentException if w is not finite
     */
    public Delay wet(double w) {
        if (!Double.isFinite(w)) throw new IllegalArgumentException("wet=" + w);
        this.wet = w;
        return this;
    }

    /**
     * Sets dry gain.
     *
     * @param d dry multiplier applied to the input signal
     * @return this
     * @throws IllegalArgumentException if d is not finite
     */
    public Delay dry(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("dry=" + d);
        this.dry = d;
        return this;
    }

    /**
     * True once the wet output has stayed below 1e-9 for {@code maxDelayMs}
     * worth of samples of silence. Called on the render thread at block
     * tick; thread-safe.
     *
     * @return false while a tail still rings
     */
    @Override
    public boolean isIdle() {
        return quietSamples >= maxDelaySamples;
    }

    @Override
    public void process(AudioBuffer b) {
        if (lines == null || channels != b.channels()) {
            channels = b.channels();
            lines = new DelayLine[channels];
            for (int c = 0; c < channels; c++) lines[c] = new DelayLine(maxDelaySamples);
        }

        double dMs = delayMs;
        double fb = feedback;
        double w = wet;
        double d = dry;
        double delaySamp = Math.max(0.0, Math.min(dMs * sampleRate / 1000.0, maxDelaySamples));

        double blockPeak = 0.0;

        for (int c = 0; c < channels; c++) {
            DelayLine line = lines[c];
            float[] ch = b.data[c];

            for (int i = 0; i < b.frames; i++) {
                float x = ch[i];
                // ponytail: sub-sample delays (< 1 sample) render as silent wet;
                // interpolator needs delay >= 1, add sub-sample support if ever audible.
                float delayed = delaySamp >= 1.0 ? line.readInterpolated(delaySamp) : 0.0f;
                ch[i] = (float) (d * x + w * delayed);
                line.write((float) (x + fb * delayed));

                double wetAbs = Math.abs(w * delayed);
                if (wetAbs > blockPeak) blockPeak = wetAbs;
            }
        }

        if (blockPeak < 1e-9) {
            quietSamples += b.frames;
        } else {
            quietSamples = 0;
        }
    }
}
