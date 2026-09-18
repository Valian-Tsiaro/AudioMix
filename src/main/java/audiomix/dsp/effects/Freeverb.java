package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Effect;
import audiomix.dsp.Allpass;
import audiomix.dsp.Comb;

/**
 * Schroeder reverb (Freeverb topology): 8 parallel damped combs summed
 * into 4 series allpasses, per channel. Left/right engines use the
 * same comb tunings; the right side is offset by 23 samples for stereo
 * decorrelation. Tunings are scaled from the classic
 * 44.1 kHz values by the project sample rate.
 *
 * <p>Input is scaled by 0.015 into the combs to prevent
 * runaway. Damping is internally scaled ×0.4 (classic {@code scaledamp}).
 * Default roomSize = 0.5, damp = 0.5, wet = 1.0, dry = 0.0
 * (send-bus usage).</p>
 *
 * <p>Threading: all builder setters are safe to call from any thread;
 * they store volatile values consumed at block boundaries.</p>
 */
public final class Freeverb implements Effect {

    private static final int[] COMB_TUNINGS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] AP_TUNINGS   = {556, 441, 341, 225};
    private static final int STEREO_SPREAD  = 23;
    private static final double INPUT_SCALE = 0.015;
    private static final double AP_G        = 0.5;
    private static final double SCALED_DAMP = 0.4;
    private static final double MIN_IDLE    = 1e-9;

    private final int sampleRate;
    private final int[] scaledCombL;
    private final int[] scaledCombR;
    private final int[] scaledAP;
    private final int[] scaledAPR;

    private volatile double roomSize = 0.5;
    private volatile double damp     = 0.5;
    private volatile double wet      = 1.0;
    private volatile double dry      = 0.0;

    private Comb[][]   combs;
    private Allpass[][] allpasses;
    private int currentChannels;
    private long quietSamples;

    /**
     * Creates a reverb for the given project sample rate.
     * Comb and allpass delay lines are pre-scaled from 44.1 kHz tunings.
     *
     * @param sampleRate project sample rate in Hz (&gt; 0)
     * @throws IllegalArgumentException if sampleRate &le; 0
     */
    public Freeverb(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        this.sampleRate = sampleRate;
        this.scaledCombL = new int[COMB_TUNINGS.length];
        this.scaledCombR = new int[COMB_TUNINGS.length];
        this.scaledAP    = new int[AP_TUNINGS.length];
        this.scaledAPR   = new int[AP_TUNINGS.length];
        for (int i = 0; i < COMB_TUNINGS.length; i++) {
            scaledCombL[i] = Math.max(1, (int) Math.round(COMB_TUNINGS[i] * sampleRate / 44100.0));
            scaledCombR[i] = scaledCombL[i] + STEREO_SPREAD;
        }
        for (int i = 0; i < AP_TUNINGS.length; i++) {
            scaledAP[i]  = Math.max(1, (int) Math.round(AP_TUNINGS[i] * sampleRate / 44100.0));
            scaledAPR[i] = scaledAP[i] + STEREO_SPREAD;
        }
    }

    /**
     * Sets the room size. Comb feedback = 0.7 + 0.28 * r.
     *
     * @param r 0..1
     * @return this
     * @throws IllegalArgumentException if r is not in [0, 1]
     */
    public Freeverb roomSize(double r) {
        if (!Double.isFinite(r) || r < 0 || r > 1)
            throw new IllegalArgumentException("roomSize=" + r);
        this.roomSize = r;
        return this;
    }

    /**
     * Sets the damping amount. Internally scaled by the classic
     * {@code scaledamp = 0.4} factor: the one-pole retention becomes
     * {@code d × 0.4}, so {@code d = 0} is no damping and {@code d = 1}
     * is dark but not frozen.
     *
     * @param d 0..1
     * @return this
     * @throws IllegalArgumentException if d is not in [0, 1]
     */
    public Freeverb damp(double d) {
        if (!Double.isFinite(d) || d < 0 || d > 1)
            throw new IllegalArgumentException("damp=" + d);
        this.damp = d;
        return this;
    }

    /**
     * Sets the wet gain (reverb-only signal multiplier).
     *
     * @param w wet gain
     * @return this
     * @throws IllegalArgumentException if w is not finite
     */
    public Freeverb wet(double w) {
        if (!Double.isFinite(w)) throw new IllegalArgumentException("wet=" + w);
        this.wet = w;
        return this;
    }

    /**
     * Sets the dry gain (input pass-through multiplier).
     *
     * @param d dry gain
     * @return this
     * @throws IllegalArgumentException if d is not finite
     */
    public Freeverb dry(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("dry=" + d);
        this.dry = d;
        return this;
    }

    /**
     * True once the wet output has stayed below 1e-9 for at least 2
     * seconds of accumulated samples.
     *
     * @return false while the reverb tail still rings
     */
    @Override
    public boolean isIdle() {
        return quietSamples >= 2L * sampleRate;
    }

    private void initEngines(int channels) {
        int sides = Math.min(channels, 2);
        int maxCombCap = 0;
        for (int d : scaledCombR) if (d > maxCombCap) maxCombCap = d;
        int maxAPCap = 0;
        for (int d : scaledAPR) if (d > maxAPCap) maxAPCap = d;

        combs      = new Comb[sides][COMB_TUNINGS.length];
        allpasses  = new Allpass[sides][AP_TUNINGS.length];

        for (int s = 0; s < sides; s++) {
            int[] cd = s == 1 ? scaledCombR : scaledCombL;
            for (int i = 0; i < COMB_TUNINGS.length; i++) {
                combs[s][i] = new Comb(maxCombCap);
                combs[s][i].setDelaySamples(cd[i]);
            }
            int[] apd = s == 1 ? scaledAPR : scaledAP;
            for (int i = 0; i < AP_TUNINGS.length; i++) {
                allpasses[s][i] = new Allpass(maxAPCap);
                allpasses[s][i].setDelaySamples(apd[i]);
                allpasses[s][i].setFeedback(AP_G);
            }
        }
        currentChannels = channels;
    }

    @Override
    public void process(AudioBuffer b) {
        if (combs == null || currentChannels != b.channels()) {
            initEngines(b.channels());
        }

        int sides = Math.min(b.channels(), 2);
        double fb   = 0.7 + 0.28 * roomSize;
        double d    = damp;
        double w    = wet;
        double dg   = dry;
        double blockPeak = 0.0;

        for (Comb[] sideCombs : combs) {
            for (Comb comb : sideCombs) {
                comb.setFeedback(fb);
                comb.setDamping(d * SCALED_DAMP);
            }
        }

        for (int ch = 0; ch < b.channels(); ch++) {
            int side = ch < sides ? ch : 0;
            Comb[]   c = combs[side];
            Allpass[] ap = allpasses[side];
            float[] data = b.data[ch];

            for (int i = 0; i < b.frames; i++) {
                float x = data[i];
                float input = x * (float) INPUT_SCALE;
                float sum = 0.0f;
                for (Comb comb : c) {
                    sum += comb.process(input);
                }
                float wetSig = sum;
                for (Allpass apf : ap) {
                    wetSig = apf.process(wetSig);
                }
                data[i] = (float) (dg * x + w * wetSig);
                double abs = Math.abs(w * wetSig);
                if (abs > blockPeak) blockPeak = abs;
            }
        }

        if (blockPeak < MIN_IDLE) {
            quietSamples += b.frames;
        } else {
            quietSamples = 0;
        }
    }
}
