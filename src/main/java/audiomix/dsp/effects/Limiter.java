package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Effect;

/**
 * Lookahead brickwall limiter with instant attack and linked stereo.
 * The detector runs a sliding window-max over the undelayed input
 * (lookahead samples); the smoothed gain is applied to a
 * delay-compensated copy so the output never exceeds the threshold.
 */
public final class Limiter implements Effect {

    private final int sampleRate;

    private volatile double thresholdDb = -1.0;
    private volatile double releaseMs = 50.0;
    private volatile double lookaheadMs = 5.0;

    private double env;
    private double grDb;
    private volatile double lastMaxGrDb;

    // delay buffer: delaySamples+1 entries so write-then-read gives exact delaySamples latency
    private float[][] delay;
    private int delayPos;
    private int delaySamples;
    private int channels;

    // detector window: primitive ring of peak values over the lookahead span
    private float[] window;
    private int windowPos;

    /**
     * Creates a limiter at the given sample rate with default threshold of -1 dBFS.
     *
     * @param sampleRate project sample rate in Hz, must be positive
     * @throws IllegalArgumentException if sampleRate &le; 0
     */
    public Limiter(int sampleRate) {
        this(sampleRate, -1.0);
    }

    /**
     * Creates a limiter at the given sample rate and threshold.
     *
     * @param sampleRate  project sample rate in Hz, must be positive
     * @param thresholdDb ceiling in dB (output will not exceed this level)
     * @throws IllegalArgumentException if sampleRate &le; 0 or thresholdDb is not finite
     */
    public Limiter(int sampleRate, double thresholdDb) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (!Double.isFinite(thresholdDb)) throw new IllegalArgumentException("threshold=" + thresholdDb);
        this.sampleRate = sampleRate;
        this.thresholdDb = thresholdDb;
    }

    /**
     * Sets the ceiling threshold in dB.
     *
     * @param db threshold in dB
     * @return this
     * @throws IllegalArgumentException if db is not finite
     */
    public Limiter threshold(double db) {
        if (!Double.isFinite(db)) throw new IllegalArgumentException("threshold=" + db);
        this.thresholdDb = db;
        return this;
    }

    /**
     * Sets the release time in milliseconds. The gain decays via a one-pole
     * filter with this time constant.
     *
     * @param ms release time, must be &gt; 0
     * @return this
     * @throws IllegalArgumentException if ms &le; 0 or not finite
     */
    public Limiter release(double ms) {
        if (!Double.isFinite(ms) || ms <= 0) throw new IllegalArgumentException("release=" + ms);
        this.releaseMs = ms;
        return this;
    }

    /**
     * Sets the lookahead time in milliseconds. The output is delayed by
     * this amount so the detector can look ahead.
     *
     * @param ms lookahead time, must be &gt; 0
     * @return this
     * @throws IllegalArgumentException if ms &le; 0 or not finite
     */
    public Limiter lookahead(double ms) {
        if (!Double.isFinite(ms) || ms <= 0) throw new IllegalArgumentException("lookahead=" + ms);
        this.lookaheadMs = ms;
        return this;
    }

    /** Threshold in dB. */
    public double getThresholdDb() { return thresholdDb; }

    /**
     * Maximum gain reduction in dB applied during the most recently
     * processed block. Thread-safe; volatile.
     */
    public double getGainReductionDb() { return lastMaxGrDb; }

    private int computeDelaySamples() {
        return (int) Math.round(lookaheadMs * sampleRate / 1000.0);
    }

    private void ensureBuffers(int ch) {
        int newDelay = Math.max(1, computeDelaySamples());
        int newBufLen = newDelay + 1;

        if (ch != channels || delay == null) {
            channels = ch;
            delaySamples = newDelay;
            delay = new float[ch][newBufLen];
            delayPos = 0;
            window = new float[newDelay];
            windowPos = 0;
            env = 0;
            grDb = 0;
        } else if (newDelay > delaySamples) {
            // ponytail: ring unrotated on grow — brief gain artifact (~bufLen frames) on live lookahead change
            for (int c = 0; c < ch; c++) {
                float[] old = delay[c];
                delay[c] = new float[newBufLen];
                System.arraycopy(old, 0, delay[c], 0, Math.min(old.length, newBufLen));
            }
            float[] oldWin = window;
            window = new float[newDelay];
            System.arraycopy(oldWin, 0, window, 0, Math.min(oldWin.length, newDelay));
            delaySamples = newDelay;
            if (delayPos >= newBufLen) delayPos = 0;
            windowPos = 0;
        } else if (newDelay < delaySamples) {
            // shrink — clear and reset; not worth preserving ring contents for a rare live change
            for (int c = 0; c < ch; c++) {
                delay[c] = new float[newBufLen];
            }
            delaySamples = newDelay;
            delayPos = 0;
            window = new float[newDelay];
            windowPos = 0;
            env = 0;
            grDb = 0;
        }
    }

    @Override
    public void process(AudioBuffer b) {
        int ch = b.channels();
        ensureBuffers(ch);

        double thresh = thresholdDb;
        double envDecay = Math.exp(-1000.0 / (releaseMs * sampleRate));
        double relCoeff = 1.0 - Math.exp(-1000.0 / (releaseMs * sampleRate));
        int bufLen = delaySamples + 1;

        double maxGr = 0.0;

        for (int i = 0; i < b.frames; i++) {
            // linked peak across channels (undelayed input)
            double peak = 0.0;
            for (int c = 0; c < ch; c++) {
                double v = Math.abs(b.data[c][i]);
                if (v > peak) peak = v;
            }

            // sliding window of peaks — max over lookahead window
            window[windowPos] = (float) peak;
            windowPos = (windowPos + 1) % delaySamples;
            // ponytail: O(D) scan per sample, D ≤ ~480 — monotonic-deque max if this shows up in profiles
            double windowMax = 0.0;
            for (int w = 0; w < delaySamples; w++) {
                if (window[w] > windowMax) windowMax = window[w];
            }

            // instant-attack, release-only envelope on windowed peak
            env = Math.max(windowMax, env * envDecay);

            // gain reduction (positive dB = amount of reduction)
            double envDb = env > 0.0 ? 20.0 * Math.log10(env) : Double.NEGATIVE_INFINITY;
            double over = envDb - thresh;
            double grTarget = over > 0.0 ? over : 0.0;

            // instant attack: jump to target; release: one-pole decay toward 0
            if (grTarget > grDb) {
                grDb = grTarget;
            } else {
                grDb *= (1.0 - relCoeff);
            }
            if (grDb < 1e-6) grDb = 0.0;

            double gain = Math.pow(10.0, -grDb / 20.0);

            // write undelayed sample into delay buffer
            for (int c = 0; c < ch; c++) {
                delay[c][delayPos] = b.data[c][i];
            }

            // read delayed sample and apply gain (delay = exactly delaySamples)
            int readPos = (delayPos + 1) % bufLen;
            for (int c = 0; c < ch; c++) {
                b.data[c][i] = (float) (delay[c][readPos] * gain);
            }

            delayPos = (delayPos + 1) % bufLen;

            if (grDb > maxGr) maxGr = grDb;
        }

        lastMaxGrDb = maxGr;
    }
}
