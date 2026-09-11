package audiomix.dsp;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ResamplerTest {

    private static final int BLOCK = 512;

    private static float[][] sine(int rate, double freq, double level, int channels, int frames) {
        float[][] data = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            float s = (float) (level * Math.sin(2.0 * Math.PI * freq * i / rate));
            for (int c = 0; c < channels; c++) {
                data[c][i] = s;
            }
        }
        return data;
    }

    private static float[][] stream(Resampler rs, float[][] input, int chunk, int maxOut) {
        int ch = input.length;
        float[][] inChunk = new float[ch][chunk];
        float[][] out = new float[ch][maxOut];
        float[][] acc = new float[ch][0];
        int total = 0;
        int fed = 0;
        while (fed < input[0].length) {
            int n = Math.min(chunk, input[0].length - fed);
            for (int c = 0; c < ch; c++) {
                System.arraycopy(input[c], fed, inChunk[c], 0, n);
            }
            fed += n;
            total = append(acc, out, rs.process(inChunk, n, out, maxOut), total);
        }
        while (true) {
            int produced = rs.flush(out, maxOut);
            if (produced == 0) break;
            total = append(acc, out, produced, total);
        }
        float[][] result = new float[ch][total];
        for (int c = 0; c < ch; c++) {
            System.arraycopy(acc[c], 0, result[c], 0, total);
        }
        return result;
    }

    private static int append(float[][] acc, float[][] out, int produced, int total) {
        if (total + produced > acc[0].length) {
            int grown = Math.max(total + produced, acc[0].length * 2);
            for (int c = 0; c < acc.length; c++) {
                acc[c] = java.util.Arrays.copyOf(acc[c], grown);
            }
        }
        for (int c = 0; c < acc.length; c++) {
            System.arraycopy(out[c], 0, acc[c], total, produced);
        }
        return total + produced;
    }

    private static int zeroCrossings(float[] x) {
        int crossings = 0;
        float prev = 0f;
        for (float s : x) {
            if (Math.signum(prev) != Math.signum(s) && !(prev == 0 && s == 0)) crossings++;
            prev = s;
        }
        return crossings;
    }

    private static float peak(float[][] x) {
        float peak = 0f;
        for (float[] channel : x) {
            for (float s : channel) peak = Math.max(peak, Math.abs(s));
        }
        return peak;
    }

    @Test
    void upsample44100To48000() {
        int inRate = 44100, outRate = 48000, inFrames = 22050;
        float[][] in = sine(inRate, 440.0, 0.25, 2, inFrames);
        Resampler rs = new Resampler(inRate, outRate, 2);
        assertEquals(outRate / (double) inRate, rs.ratio(), 1e-12);

        float[][] out = stream(rs, in, BLOCK, BLOCK);
        assertEquals(24000, out[0].length, 2, "output length = 0.5 s at 48000");
        double freq = (double) zeroCrossings(out[0]) * outRate / (2.0 * out[0].length);
        assertEquals(440.0, freq, 440.0 * 0.005, "dominant frequency");
        assertEquals(0.25f, peak(out), 0.25f * 0.05f, "peak amplitude");
        assertArrayEquals(out[0], out[1], "channels identical");
    }

    @Test
    void downsample48000To44100() {
        int inRate = 48000, outRate = 44100, inFrames = 24000;
        float[][] in = sine(inRate, 440.0, 0.25, 2, inFrames);
        Resampler rs = new Resampler(inRate, outRate, 2);
        assertEquals(outRate / (double) inRate, rs.ratio(), 1e-12);

        float[][] out = stream(rs, in, BLOCK, BLOCK);
        assertEquals(22050, out[0].length, 2, "output length = 0.5 s at 44100");
        double freq = (double) zeroCrossings(out[0]) * outRate / (2.0 * out[0].length);
        assertEquals(440.0, freq, 440.0 * 0.005, "dominant frequency");
        assertEquals(0.25f, peak(out), 0.25f * 0.05f, "peak amplitude");
        assertArrayEquals(out[0], out[1], "channels identical");
    }

    @Test
    void ratioOneIsBitExactIdentity() {
        int rate = 44100, frames = 5000;
        float[][] in = new float[2][frames];
        Random rnd = new Random(42);
        for (int i = 0; i < frames; i++) {
            in[0][i] = (float) rnd.nextDouble() * 2f - 1f;
            in[1][i] = (float) rnd.nextDouble() * 2f - 1f;
        }
        Resampler rs = new Resampler(rate, rate, 2);
        float[][] out = stream(rs, in, 333, 777);
        assertEquals(frames, out[0].length);
        assertArrayEquals(in[0], out[0], "channel 0 bit-exact");
        assertArrayEquals(in[1], out[1], "channel 1 bit-exact");
    }

    @Test
    void streamingDeterminismAcrossChunkSizes() {
        int inRate = 44100, outRate = 48000, inFrames = 20000;
        float[][] in = sine(inRate, 440.0, 0.25, 2, inFrames);

        float[][] by7 = stream(new Resampler(inRate, outRate, 2), in, 7, 1024);
        float[][] by512 = stream(new Resampler(inRate, outRate, 2), in, BLOCK, 1024);
        float[][] by4096 = stream(new Resampler(inRate, outRate, 2), in, 4096, 1024);

        assertEquals(by512[0].length, by7[0].length, 1, "same total length (7)");
        assertEquals(by512[0].length, by4096[0].length, 1, "same total length (4096)");
        for (int i = 0; i < 1000; i++) {
            assertEquals(by512[0][i], by7[0][i], 0f, "first 1000 samples identical (7)");
            assertEquals(by512[0][i], by4096[0][i], 0f, "first 1000 samples identical (4096)");
        }
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new Resampler(0, 48000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Resampler(44100, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Resampler(44100, 48000, 0));
    }
}
