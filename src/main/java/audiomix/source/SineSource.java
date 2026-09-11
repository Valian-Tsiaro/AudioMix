package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.core.Source;

/**
 * Pure sine-wave source using a double phase accumulator.
 * All channels receive the same sample. Exact to float precision
 * because the accumulator is double and only cast at the final write.
 */
public final class SineSource implements Source {

    private static final double TAU = 2.0 * Math.PI;

    private final double level;
    private final double stepPerFrame;
    private final long lengthFrames;
    private final boolean infinite;

    private double phase;
    private long framesRead;
    private boolean exhausted;

    /**
     * @param sampleRate   samples per second (≥ 1)
     * @param freq         frequency in Hz (≥ 0)
     * @param level        peak amplitude (≥ 0)
     * @param channels     number of channels (≥ 1)
     * @param lengthFrames total frames before exhaustion; negative = infinite
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public SineSource(int sampleRate, double freq, double level, int channels, long lengthFrames) {
        if (sampleRate < 1) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (freq < 0) throw new IllegalArgumentException("freq=" + freq);
        if (level < 0) throw new IllegalArgumentException("level=" + level);
        if (channels < 1) throw new IllegalArgumentException("channels=" + channels);
        this.level = level;
        this.stepPerFrame = TAU * freq / sampleRate;
        this.lengthFrames = lengthFrames;
        this.infinite = lengthFrames < 0;
    }

    @Override
    public int read(AudioBuffer b) {
        if (exhausted) return 0;
        int toFill = (int) Math.min(b.frames, infinite ? b.frames : Math.max(0, lengthFrames - framesRead));
        for (int i = 0; i < toFill; i++) {
            float sample = (float) (level * Math.sin(phase));
            for (int ch = 0; ch < b.channels(); ch++) {
                b.data[ch][i] = sample;
            }
            phase += stepPerFrame;
        }
        framesRead += toFill;
        if (!infinite && framesRead >= lengthFrames) exhausted = true;
        return toFill;
    }

    @Override
    public void close() { }
}
