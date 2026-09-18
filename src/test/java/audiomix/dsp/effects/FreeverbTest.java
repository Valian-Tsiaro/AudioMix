package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Channel;
import audiomix.core.Mixer;
import audiomix.io.WavReader;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static java.lang.Math.*;
import static org.junit.jupiter.api.Assertions.*;

class FreeverbTest {

    private static final int SR = 48000;
    private static final int BS = 512;

    private static AudioBuffer silenceBlock(int ch) {
        return AudioBuffer.create(ch, BS);
    }

    private static AudioBuffer impulseStereo() {
        AudioBuffer b = AudioBuffer.create(2, BS);
        b.data[0][0] = 1.0f;
        b.data[1][0] = 1.0f;
        return b;
    }

    private static double blockRms(float[] ch) {
        double sum = 0;
        for (float v : ch) sum += v * v;
        return sqrt(sum / ch.length);
    }

    // ── tail decay ──────────────────────────────────────────────────

    @Test
    void impulseTailDecays() {
        int tailSecs = 9;
        int totalFrames = tailSecs * SR;
        int totalBlocks = (totalFrames + BS - 1) / BS;
        float[] left = new float[totalFrames];
        float[] right = new float[totalFrames];

        // roomSize 0.9 + damp 0.0 → long decay for the > 1 s assertion
        Freeverb rv = new Freeverb(SR).roomSize(0.9).damp(0.0);
        for (int blk = 0; blk < totalBlocks; blk++) {
            AudioBuffer out = AudioBuffer.create(2, BS);
            if (blk == 0) {
                out.data[0][0] = 1.0f;
                out.data[1][0] = 1.0f;
            }
            rv.process(out);
            int off = blk * BS;
            int n = min(BS, totalFrames - off);
            System.arraycopy(out.data[0], 0, left, off, n);
            System.arraycopy(out.data[1], 0, right, off, n);
        }

        // tail above -80 dBFS (1e-4 linear) spans > 1 s total
        int aboveThresh = 0;
        for (int i = 1; i < totalFrames; i++) {
            if (abs(left[i]) > 1e-4 || abs(right[i]) > 1e-4) aboveThresh++;
        }
        assertTrue(aboveThresh > SR,
                "tail > 1 s above -80 dBFS, got " + aboveThresh + " frames");

        // per-block RMS decreasing trend over first 20 post-impulse blocks
        int nBlocks = 20;
        double[] rms = new double[nBlocks];
        for (int i = 0; i < nBlocks; i++) {
            int off = (i + 1) * BS; // skip block 0 (impulse)
            float[] ch = new float[BS];
            System.arraycopy(left, off, ch, 0, BS);
            rms[i] = blockRms(ch);
        }
        // linear regression slope must be negative
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < nBlocks; i++) {
            sumX  += i;
            sumY  += rms[i];
            sumXY += i * rms[i];
            sumX2 += (double) i * i;
        }
        double slope = (nBlocks * sumXY - sumX * sumY) / (nBlocks * sumX2 - sumX * sumX);
        assertTrue(slope < 0, "RMS trend slope must be negative, got " + slope);
    }

    // ── isIdle ──────────────────────────────────────────────────────

    @Test
    void isIdleDrains() {
        Freeverb rv = new Freeverb(SR);
        assertFalse(rv.isIdle(), "not idle before any process");

        // impulse
        rv.process(impulseStereo());
        assertFalse(rv.isIdle(), "not idle right after impulse");

        // roomSize 0.2 — drain <= 4 s
        Freeverb rv2 = new Freeverb(SR).roomSize(0.2);
        rv2.process(impulseStereo());
        int maxBlocks = (int) ceil(4.0 * SR / BS) + 1;
        boolean idle = false;
        for (int i = 0; i < maxBlocks; i++) {
            rv2.process(silenceBlock(2));
            if (rv2.isIdle()) { idle = true; break; }
        }
        assertTrue(idle, "roomSize 0.2 should idle within 4 s");

        // default roomSize 0.5 — drain <= 8 s
        Freeverb rv3 = new Freeverb(SR);
        rv3.process(impulseStereo());
        maxBlocks = (int) ceil(8.0 * SR / BS) + 1;
        idle = false;
        for (int i = 0; i < maxBlocks; i++) {
            rv3.process(silenceBlock(2));
            if (rv3.isIdle()) { idle = true; break; }
        }
        assertTrue(idle, "roomSize 0.5 should idle within 8 s");
    }

    // ── DC stability ────────────────────────────────────────────────

    @Test
    void dcStability() {
        int frames = 144000;
        int blocks = (frames + BS - 1) / BS;
        Freeverb rv = new Freeverb(SR);

        for (int b = 0; b < blocks; b++) {
            AudioBuffer buf = AudioBuffer.create(2, BS);
            for (int i = 0; i < BS; i++) { buf.data[0][i] = 0.5f; buf.data[1][i] = 0.5f; }
            rv.process(buf);
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < BS; i++) {
                    float v = buf.data[ch][i];
                    assertFalse(Float.isNaN(v), "NaN at block " + b + " ch " + ch + " frame " + i);
                    assertFalse(Float.isInfinite(v), "Inf at block " + b + " ch " + ch + " frame " + i);
                    assertTrue(abs(v) < 1.5f,
                            "|sample| < 1.5 at block " + b + " ch " + ch + " frame " + i + ": " + v);
                }
            }
        }
    }

    // ── stereo decorrelation ────────────────────────────────────────

    @Test
    void stereoSpreadDecorrelates() {
        int secs = 8;
        int totalFrames = secs * SR;
        int totalBlocks = (totalFrames + BS - 1) / BS;
        float[] left = new float[totalFrames];
        float[] right = new float[totalFrames];

        Freeverb rv = new Freeverb(SR);
        for (int blk = 0; blk < totalBlocks; blk++) {
            AudioBuffer out = AudioBuffer.create(2, BS);
            if (blk == 0) { out.data[0][0] = 1.0f; out.data[1][0] = 1.0f; }
            rv.process(out);
            int off = blk * BS;
            int n = min(BS, totalFrames - off);
            System.arraycopy(out.data[0], 0, left, off, n);
            System.arraycopy(out.data[1], 0, right, off, n);
        }

        boolean differ = false;
        for (int i = 0; i < totalFrames; i++) {
            if (left[i] != right[i]) { differ = true; break; }
        }
        assertTrue(differ, "L and R must differ somewhere in the tail");
    }

    // ── wet 0 / dry 1 bypass ────────────────────────────────────────

    @Test
    void wetZeroDryOneBypass() {
        AudioBuffer in = AudioBuffer.create(2, BS);
        for (int i = 0; i < BS; i++) { in.data[0][i] = (float) (i + 1); in.data[1][i] = (float) (i + 1); }

        Freeverb rv = new Freeverb(SR).wet(0.0).dry(1.0);
        rv.process(in);

        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < BS; i++) {
                assertEquals((float) (i + 1), in.data[ch][i], 0.0f,
                        "bypass ch " + ch + " frame " + i);
            }
        }
    }

    // ── mono buffer ─────────────────────────────────────────────────

    @Test
    void monoBuffer() {
        int totalFrames = 2 * SR;
        int totalBlocks = (totalFrames + BS - 1) / BS;
        float[] out = new float[totalFrames];

        Freeverb rv = new Freeverb(SR);
        for (int blk = 0; blk < totalBlocks; blk++) {
            AudioBuffer buf = AudioBuffer.create(1, BS);
            if (blk == 0) buf.data[0][0] = 1.0f;
            rv.process(buf);
            int off = blk * BS;
            int n = min(BS, totalFrames - off);
            System.arraycopy(buf.data[0], 0, out, off, n);
        }

        // verify tail exists (mono path works)
        boolean hasTail = false;
        for (int i = BS; i < totalFrames; i++) {
            if (abs(out[i]) > 1e-6) { hasTail = true; break; }
        }
        assertTrue(hasTail, "mono buffer must produce a reverb tail");

        // isIdle drains — same logic as stereo
        Freeverb rv2 = new Freeverb(SR).roomSize(0.2);
        AudioBuffer impulseMono = AudioBuffer.create(1, BS);
        impulseMono.data[0][0] = 1.0f;
        rv2.process(impulseMono);
        assertFalse(rv2.isIdle(), "mono not idle right after impulse");

        int maxBlocks = (int) ceil(4.0 * SR / BS) + 1;
        boolean idle = false;
        for (int i = 0; i < maxBlocks; i++) {
            rv2.process(silenceBlock(1));
            if (rv2.isIdle()) { idle = true; break; }
        }
        assertTrue(idle, "mono roomSize 0.2 should idle within 4 s");
    }

    // ── send-bus integration ────────────────────────────────────────

    @Test
    void sendBusRender(@TempDir Path tmpDir) {
        int srcFrames = 4096;
        Mixer m = new Mixer(SR, BS);
        Channel sineCh = m.addChannel("sine");
        sineCh.setSource(new SineSource(SR, 440.0, 0.5, 1, srcFrames));

        m.addAuxBus("reverb");
        sineCh.addSend(m.getAux("reverb"), 0.4);
        m.getAux("reverb").addEffect(new Freeverb(SR));

        Meter masterMeter = new Meter(2);
        m.getMaster().addEffect(masterMeter);

        Path file = tmpDir.resolve("out.wav");
        m.renderToFile(file.toString());

        try (WavReader reader = new WavReader(file)) {
            assertTrue(reader.getFrameCount() > srcFrames,
                    "output length " + reader.getFrameCount() + " must exceed source " + srcFrames);

            AudioBuffer buf = AudioBuffer.create(2, BS);
            int read;
            while ((read = reader.read(buf)) > 0) {
                for (int i = 0; i < read; i++) {
                    for (int ci = 0; ci < 2; ci++) {
                        assertFalse(Float.isNaN(buf.data[ci][i]), "NaN at frame " + i + " ch " + ci);
                        assertFalse(Float.isInfinite(buf.data[ci][i]), "Inf at frame " + i + " ch " + ci);
                    }
                }
            }
        }

        assertTrue(Double.isFinite(masterMeter.getPeakDbfs(0)), "master meter L peak must be finite");
        assertTrue(Double.isFinite(masterMeter.getPeakDbfs(1)), "master meter R peak must be finite");
    }

    // ── builder validation ──────────────────────────────────────────

    @Test
    void invalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(0));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(-1));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(SR).roomSize(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(SR).roomSize(1.1));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(SR).damp(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(SR).damp(1.1));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(SR).wet(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Freeverb(SR).dry(Double.NaN));
    }
}
