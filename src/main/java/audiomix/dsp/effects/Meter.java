package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Effect;
import audiomix.core.GainMath;

/**
 * Transparent analysis effect. Publishes per-channel dBFS peak
 * (sticky since last {@link #resetPeak()}), last-block RMS, and
 * a sticky clip flag. All getters are volatile-safe and may be
 * called from any thread without blocking the audio path.
 */
public final class Meter implements Effect {

    private static final class Chan {
        volatile double peakDb = Double.NEGATIVE_INFINITY;
        volatile double rmsDb = Double.NEGATIVE_INFINITY;
        volatile boolean clip;
    }

    private final Chan[] chans;

    /**
     * Creates a meter for the given channel count.
     *
     * @param channels number of channels to monitor (≥ 1)
     * @throws IllegalArgumentException if channels &lt; 1
     */
    public Meter(int channels) {
        if (channels < 1) throw new IllegalArgumentException("channels=" + channels);
        chans = new Chan[channels];
        for (int i = 0; i < channels; i++) chans[i] = new Chan();
    }

    @Override
    public void process(AudioBuffer b) {
        int n = Math.min(b.channels(), chans.length);
        for (int ch = 0; ch < n; ch++) {
            float[] d = b.data[ch];
            float peakLin = 0.0f;
            double sumSq = 0.0;
            for (int i = 0; i < b.frames; i++) {
                float a = Math.abs(d[i]);
                if (a > peakLin) peakLin = a;
                sumSq += d[i] * d[i];
            }
            double rmsLin = Math.sqrt(sumSq / b.frames);

            Chan c = chans[ch];
            if (peakLin >= 1.0f) c.clip = true;
            double peakDb = GainMath.linearToDb(peakLin);
            if (peakDb > c.peakDb) c.peakDb = peakDb;
            c.rmsDb = GainMath.linearToDb(rmsLin);
        }
    }

    /**
     * Peak level in dBFS since the last {@link #resetPeak()}.
     * Returns {@link Double#NEGATIVE_INFINITY} for silence.
     *
     * @param ch channel index
     * @return peak dBFS
     * @throws IllegalArgumentException if ch is out of range
     */
    public double getPeakDbfs(int ch) {
        rangeCheck(ch);
        return chans[ch].peakDb;
    }

    /**
     * RMS level in dBFS of the last processed block.
     * Returns {@link Double#NEGATIVE_INFINITY} for silence.
     *
     * @param ch channel index
     * @return RMS dBFS
     * @throws IllegalArgumentException if ch is out of range
     */
    public double getRmsDbfs(int ch) {
        rangeCheck(ch);
        return chans[ch].rmsDb;
    }

    /**
     * True if any sample on this channel has had |sample| ≥ 1.0
     * since the last {@link #clearClip()}.
     *
     * @param ch channel index
     * @return true if clipped
     * @throws IllegalArgumentException if ch is out of range
     */
    public boolean clipped(int ch) {
        rangeCheck(ch);
        return chans[ch].clip;
    }

    /** Clears all per-channel peak values (not clip flags). */
    public void resetPeak() {
        for (Chan c : chans) c.peakDb = Double.NEGATIVE_INFINITY;
    }

    /** Clears all per-channel clip flags. */
    public void clearClip() {
        for (Chan c : chans) c.clip = false;
    }

    private void rangeCheck(int ch) {
        if (ch < 0 || ch >= chans.length)
            throw new IllegalArgumentException("ch=" + ch);
    }
}
