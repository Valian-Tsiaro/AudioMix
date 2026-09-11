package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.core.Source;

import java.util.Random;

/**
 * Deterministic noise source. Two instances created with the same seed
 * produce byte-identical sample streams. Samples are symmetric in
 * {@code [-level, +level]}. {@code sampleRate} is accepted for API
 * consistency with {@link SineSource} but does not affect output.
 */
public final class NoiseSource implements Source {

    private final long lengthFrames;
    private final boolean infinite;
    private final Random rng;
    private final float level;

    private long framesRead;
    private boolean exhausted;

    /**
     * @param sampleRate  samples per second (accepted for API consistency; unused)
     * @param level       peak amplitude bound (≥ 0); each sample is in [-level, level]
     * @param channels    number of channels (≥ 1)
     * @param seed        random seed
     * @param lengthFrames total frames before exhaustion; negative = infinite
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public NoiseSource(int sampleRate, double level, int channels, long seed, long lengthFrames) {
        if (sampleRate < 1) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (level < 0) throw new IllegalArgumentException("level=" + level);
        if (channels < 1) throw new IllegalArgumentException("channels=" + channels);
        this.level = (float) level;
        this.rng = new Random(seed);
        this.lengthFrames = lengthFrames;
        this.infinite = lengthFrames < 0;
    }

    @Override
    public int read(AudioBuffer b) {
        if (exhausted) return 0;
        int toFill = (int) Math.min(b.frames, infinite ? b.frames : Math.max(0, lengthFrames - framesRead));
        for (int i = 0; i < toFill; i++) {
            for (int ch = 0; ch < b.channels(); ch++) {
                b.data[ch][i] = (rng.nextFloat() * 2f - 1f) * level;
            }
        }
        framesRead += toFill;
        if (!infinite && framesRead >= lengthFrames) exhausted = true;
        return toFill;
    }

    @Override
    public void close() { }
}
