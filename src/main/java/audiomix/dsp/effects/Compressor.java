package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Effect;

/**
 * Linked-stereo dynamics compressor with exact, testable gain-reduction
 * math. The detector uses a per-sample peak envelope with instant attack
 * and release-only decay; attack/release parameters smooth the gain
 * reduction itself, not the envelope.
 */
public final class Compressor implements Effect {

    private final int sampleRate;

    private volatile double thresholdDb = -20.0;
    private volatile double ratio = 2.0;
    private volatile double attackMs = 10.0;
    private volatile double releaseMs = 100.0;

    private double env;
    private double grDb;
    private volatile double lastMaxGrDb;

    /**
     * Creates a compressor at the given sample rate.
     *
     * @param sampleRate project sample rate in Hz, must be positive
     * @throws IllegalArgumentException if sampleRate <= 0
     */
    public Compressor(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate=" + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    /**
     * Sets threshold in dB.
     *
     * @param db threshold in dB
     * @return this
     * @throws IllegalArgumentException if db is not finite
     */
    public Compressor threshold(double db) {
        if (!Double.isFinite(db)) throw new IllegalArgumentException("threshold=" + db);
        this.thresholdDb = db;
        return this;
    }

    /**
     * Sets compression ratio.
     *
     * @param r ratio, must be &gt; 1
     * @return this
     * @throws IllegalArgumentException if r &le; 1
     */
    public Compressor ratio(double r) {
        if (!(r > 1.0)) throw new IllegalArgumentException("ratio=" + r);
        this.ratio = r;
        return this;
    }

    /**
     * Sets attack time in milliseconds.
     *
     * @param ms attack time, must be &gt; 0
     * @return this
     * @throws IllegalArgumentException if ms &le; 0 or not finite
     */
    public Compressor attack(double ms) {
        if (!Double.isFinite(ms) || ms <= 0) throw new IllegalArgumentException("attack=" + ms);
        this.attackMs = ms;
        return this;
    }

    /**
     * Sets release time in milliseconds.
     *
     * @param ms release time, must be &gt; 0
     * @return this
     * @throws IllegalArgumentException if ms &le; 0 or not finite
     */
    public Compressor release(double ms) {
        if (!Double.isFinite(ms) || ms <= 0) throw new IllegalArgumentException("release=" + ms);
        this.releaseMs = ms;
        return this;
    }

    /** Threshold in dB. */
    public double getThresholdDb() { return thresholdDb; }

    /** Compression ratio (> 1). */
    public double getRatio() { return ratio; }

    /**
     * Maximum gain reduction in dB applied during the most recently
     * processed block. Thread-safe; volatile.
     */
    public double getGainReductionDb() { return lastMaxGrDb; }

    @Override
    public void process(AudioBuffer b) {
        double thresh = thresholdDb;
        double rat = ratio;
        double envDecay = Math.exp(-1000.0 / (releaseMs * sampleRate));
        double atkCoeff = 1.0 - Math.exp(-1000.0 / (attackMs * sampleRate));
        double relCoeff = 1.0 - Math.exp(-1000.0 / (releaseMs * sampleRate));
        int ch = b.channels();

        double maxGr = 0.0;

        for (int i = 0; i < b.frames; i++) {
            // linked-stereo: peak across channels
            double peak = 0.0;
            for (int c = 0; c < ch; c++) {
                double v = Math.abs(b.data[c][i]);
                if (v > peak) peak = v;
            }

            // instant attack, release-only decay envelope
            env = Math.max(peak, env * envDecay);

            // gain-reduction target from envelope
            double envDb = env > 0.0 ? 20.0 * Math.log10(env) : Double.NEGATIVE_INFINITY;
            double over = envDb - thresh;
            double grTarget = over > 0.0 ? over * (1.0 - 1.0 / rat) : 0.0;

            // smooth GR: toward target via attack, toward 0 via release
            if (grTarget > grDb) {
                grDb += (grTarget - grDb) * atkCoeff;
            } else {
                grDb *= (1.0 - relCoeff);
            }

            if (grDb < 1e-6) grDb = 0.0;

            double gain = Math.pow(10.0, -grDb / 20.0);

            for (int c = 0; c < ch; c++) {
                b.data[c][i] *= gain;
            }

            if (grDb > maxGr) maxGr = grDb;
        }

        lastMaxGrDb = maxGr;
    }
}
