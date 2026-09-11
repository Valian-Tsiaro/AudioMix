package audiomix.core;

import java.util.Arrays;

public final class AudioBuffer {

    public final float[][] data;
    public final int frames;

    private AudioBuffer(float[][] data, int frames) {
        this.data = data;
        this.frames = frames;
    }

    public static AudioBuffer create(int channels, int frames) {
        if (channels < 1 || frames < 1) {
            throw new IllegalArgumentException("channels=" + channels + " frames=" + frames);
        }
        return new AudioBuffer(new float[channels][frames], frames);
    }

    public int channels() {
        return data.length;
    }

    public void clear() {
        for (float[] row : data) {
            Arrays.fill(row, 0.0f);
        }
    }

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

    public float get(int ch, int i) {
        return data[ch][i];
    }

    public void set(int ch, int i, float v) {
        data[ch][i] = v;
    }
}
