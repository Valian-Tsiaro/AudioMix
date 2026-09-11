package audiomix.source;

import audiomix.core.AudioBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinSourcesTest {

    private static final int BLOCK = 512;

    // ── Sine ──────────────────────────────────────────────────────

    @Test
    void sinePeakAmplitudeAndFrequency() {
        int sr = 48000, ch = 2, totalFrames = 4800;
        double level = 0.25;
        SineSource src = new SineSource(sr, 440.0, level, ch, totalFrames);

        AudioBuffer buf = AudioBuffer.create(ch, BLOCK);
        float globalMax = 0f;
        int zeroCrossings = 0;
        int framesRead = 0;
        float prevSample = 0f;

        while (framesRead < totalFrames) {
            int filled = src.read(buf);
            if (filled == 0) break;
            for (int i = 0; i < filled; i++) {
                float s = buf.get(0, i);
                globalMax = Math.max(globalMax, Math.abs(s));
                if (Math.signum(prevSample) != Math.signum(s) && !(prevSample == 0 && s == 0)) {
                    zeroCrossings++;
                }
                prevSample = s;
            }
            framesRead += filled;
        }

        assertEquals(totalFrames, framesRead);
        assertEquals((float) level, globalMax, 1e-6f, "peak amplitude");

        // dominant frequency from zero crossings: freq ≈ crossings * sr / (2 * framesRead)
        double measuredFreq = (double) zeroCrossings * sr / (2.0 * framesRead);
        assertEquals(440.0, measuredFreq, 0.5, "dominant frequency");

        // channels must be identical
        SineSource src2 = new SineSource(sr, 440.0, level, ch, totalFrames);
        while (src2.read(buf) > 0) {
            for (int i = 0; i < buf.frames; i++) {
                assertEquals(buf.get(0, i), buf.get(1, i), 1e-12f, "channels identical");
            }
        }
    }

    @Test
    void sineExhaustsCorrectly() {
        int sr = 48000, ch = 1, totalFrames = 1000;
        SineSource src = new SineSource(sr, 440.0, 1.0, ch, totalFrames);
        AudioBuffer buf = AudioBuffer.create(ch, BLOCK);

        assertEquals(512, src.read(buf));
        assertEquals(488, src.read(buf));
        assertEquals(0, src.read(buf));
        assertEquals(0, src.read(buf));
    }

    // ── Noise ─────────────────────────────────────────────────────

    @Test
    void noiseDeterminismAndBounds() {
        int sr = 48000, ch = 2, totalFrames = 2000;
        double level = 0.5;
        long seed = 12345L;

        NoiseSource src1 = new NoiseSource(sr, level, ch, seed, totalFrames);
        NoiseSource src2 = new NoiseSource(sr, level, ch, seed, totalFrames);
        AudioBuffer b1 = AudioBuffer.create(ch, BLOCK);
        AudioBuffer b2 = AudioBuffer.create(ch, BLOCK);

        int served = 0;
        boolean sawNegative = false;
        while (served < totalFrames) {
            int f1 = src1.read(b1);
            int f2 = src2.read(b2);
            assertEquals(f1, f2, "same fill length");
            for (int i = 0; i < f1; i++) {
                for (int c = 0; c < ch; c++) {
                    assertEquals(b1.get(c, i), b2.get(c, i), 0f, "identical samples, seed=" + seed);
                    assertTrue(Math.abs(b1.get(c, i)) <= level + 1e-6f, "within level bound");
                    if (b1.get(c, i) < 0) sawNegative = true;
                }
            }
            served += f1;
        }
        assertEquals(totalFrames, served);
        assertTrue(sawNegative, "noise should be symmetric (must contain negative samples)");

        // different seed → different stream
        NoiseSource src3 = new NoiseSource(sr, level, ch, 99999L, totalFrames);
        src1 = new NoiseSource(sr, level, ch, seed, totalFrames);
        boolean differ = false;
        while (src1.read(b1) > 0) {
            src3.read(b2);
            for (int i = 0; i < b1.frames; i++) {
                if (b1.get(0, i) != b2.get(0, i)) { differ = true; break; }
            }
            if (differ) break;
        }
        assertTrue(differ, "different seeds produce different streams");
    }

    // ── Silence ───────────────────────────────────────────────────

    @Test
    void silenceExhaustsCorrectly() {
        int ch = 2, totalFrames = 600;
        SilenceSource src = new SilenceSource(ch, totalFrames);
        AudioBuffer buf = AudioBuffer.create(ch, BLOCK);

        int served = 0;
        while (served < totalFrames) {
            int filled = src.read(buf);
            assertTrue(filled > 0);
            for (int i = 0; i < filled; i++) {
                for (int c = 0; c < ch; c++) {
                    assertEquals(0.0f, buf.get(c, i), "silence is zero");
                }
            }
            served += filled;
        }
        assertEquals(totalFrames, served);
        assertEquals(0, src.read(buf));
        assertEquals(0, src.read(buf));
    }
}
