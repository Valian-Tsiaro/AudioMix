package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.core.Source;

/**
 * Zero-filled source. All channels are set to silence (0.0f).
 * Exhausts after exactly {@code lengthFrames} frames.
 */
public final class SilenceSource implements Source {

    private final long lengthFrames;
    private final boolean infinite;

    private long framesRead;
    private boolean exhausted;

    /**
     * @param channels    number of channels (≥ 1)
     * @param lengthFrames total frames before exhaustion; negative = infinite
     * @throws IllegalArgumentException if channels ≤ 0
     */
    public SilenceSource(int channels, long lengthFrames) {
        if (channels < 1) throw new IllegalArgumentException("channels=" + channels);
        this.lengthFrames = lengthFrames;
        this.infinite = lengthFrames < 0;
    }

    @Override
    public int read(AudioBuffer b) {
        if (exhausted) return 0;
        int toFill = (int) Math.min(b.frames, infinite ? b.frames : Math.max(0, lengthFrames - framesRead));
        for (int i = 0; i < toFill; i++) {
            for (int ch = 0; ch < b.channels(); ch++) {
                b.data[ch][i] = 0.0f;
            }
        }
        framesRead += toFill;
        if (!infinite && framesRead >= lengthFrames) exhausted = true;
        return toFill;
    }

    @Override
    public void close() { }
}
