package audiomix.core;

import java.util.Arrays;

/**
 * Non-interleaved float audio block. Channels are stored as
 * {@code float[channels][frames]}. Samples are nominally in the
 * range ±1.0. Dimensions are immutable; sample data is mutable.
 * Not thread-safe; confined to the audio/render thread.
 */
public final class AudioBuffer {

    /** Raw sample data; mutate in place. */
    public final float[][] data;
    /** Number of frames in this block. */
    public final int frames;

    private AudioBuffer(float[][] data, int frames) {
        this.data = data;
        this.frames = frames;
    }

    /**
     * Creates a zero-filled buffer.
     *
     * @param channels number of channels (≥ 1)
     * @param frames   number of frames (≥ 1)
     * @return new buffer
     * @throws IllegalArgumentException if channels or frames ≤ 0
     */
    public static AudioBuffer create(int channels, int frames) {
        if (channels < 1 || frames < 1) {
            throw new IllegalArgumentException("channels=" + channels + " frames=" + frames);
        }
        return new AudioBuffer(new float[channels][frames], frames);
    }

    /** Returns the number of channels. */
    public int channels() {
        return data.length;
    }

    /** Zeros all samples across all channels. */
    public void clear() {
        for (float[] row : data) {
            Arrays.fill(row, 0.0f);
        }
    }

    /**
     * Copies all samples from {@code src} into this buffer.
     *
     * @param src source buffer (must have same dimensions)
     * @throws IllegalArgumentException if dimensions differ
     */
    public void copyFrom(AudioBuffer src) {
        if (src.channels() != channels() || src.frames != frames) {
            throw new IllegalArgumentException(
                    "dimension mismatch: src=" + src.channels() + "x" + src.frames
                            + " dst=" + channels() + "x" + frames);
        }
        for (int ch = 0; ch < channels(); ch++) {
            System.arraycopy(src.data[ch], 0, data[ch], 0, frames);
        }
    }

    /** Returns sample at channel {@code ch}, frame {@code i}. */
    public float get(int ch, int i) {
        return data[ch][i];
    }

    /** Sets sample at channel {@code ch}, frame {@code i}. */
    public void set(int ch, int i, float v) {
        data[ch][i] = v;
    }
}
