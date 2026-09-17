package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import org.junit.jupiter.api.Test;

import static java.lang.Math.*;
import static org.junit.jupiter.api.Assertions.*;

class LimiterTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double FREQ = 440.0;
    private static final double THRESHOLD_DB = -1.0;

    private static AudioBuffer sineBlock(int ch, double level) {
        AudioBuffer b = AudioBuffer.create(ch, BS);
        for (int c = 0; c < ch; c++) {
            for (int i = 0; i < BS; i++) {
                b.data[c][i] = (float) (level * sin(2.0 * PI * FREQ * i / SR));
            }
        }
        return b;
    }

    private static AudioBuffer stereoBlock(double leftLevel, double rightLevel) {
        AudioBuffer b = AudioBuffer.create(2, BS);
        for (int i = 0; i < BS; i++) {
            b.data[0][i] = (float) (leftLevel * sin(2.0 * PI * FREQ * i / SR));
            b.data[1][i] = (float) (rightLevel * sin(2.0 * PI * FREQ * i / SR));
        }
        return b;
    }

    private static double peakDb(AudioBuffer b) {
        double peak = 0.0;
        for (int c = 0; c < b.channels(); c++) {
            for (int i = 0; i < b.frames; i++) {
                peak = max(peak, abs(b.data[c][i]));
            }
        }
        return peak > 0.0 ? 20.0 * log10(peak) : Double.NEGATIVE_INFINITY;
    }

    private static double peakDb(AudioBuffer b, int ch) {
        double peak = 0.0;
        for (int i = 0; i < b.frames; i++) {
            peak = max(peak, abs(b.data[ch][i]));
        }
        return peak > 0.0 ? 20.0 * log10(peak) : Double.NEGATIVE_INFINITY;
    }

    // ── full scale: output peak ≈ threshold ──────────────────────────

    @Test
    void fullScaleSineLimited() {
        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);
        for (int i = 0; i < 20; i++) lim.process(sineBlock(1, 1.0));
        AudioBuffer out = sineBlock(1, 1.0);
        lim.process(out);

        double peakDb = peakDb(out);
        assertTrue(peakDb <= THRESHOLD_DB + 0.1,
                "peak " + peakDb + " dB should be <= threshold + 0.1");
        assertTrue(peakDb >= THRESHOLD_DB - 1.0,
                "peak " + peakDb + " dB should be >= threshold - 1 (limiting, not muting)");
        assertEquals(1.0, lim.getGainReductionDb(), 0.5);
    }

    // ── below threshold: transparent ─────────────────────────────────

    @Test
    void transparentBelowThreshold() {
        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);
        // -20 dBFS ≈ 0.1
        for (int i = 0; i < 10; i++) lim.process(sineBlock(1, 0.1));
        AudioBuffer out = sineBlock(1, 0.1);
        lim.process(out);

        assertEquals(-20.0, peakDb(out), 0.1);
        assertEquals(0.0, lim.getGainReductionDb(), 0.1);
    }

    // ── latency: impulse at t=0 → output at t = lookahead ────────────

    @Test
    void impulseLatency() {
        int lookaheadSamples = (int) Math.round(5.0 * SR / 1000.0); // 240
        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);

        AudioBuffer b = AudioBuffer.create(1, BS);
        b.data[0][100] = 1.0f;
        lim.process(b);

        // impulse at frame 100, delayed by 240 → output at frame 340 in same block
        int peakFrame = 0;
        float peakVal = 0;
        for (int i = 0; i < BS; i++) {
            if (abs(b.data[0][i]) > abs(peakVal)) {
                peakVal = b.data[0][i];
                peakFrame = i;
            }
        }

        assertEquals(340, peakFrame, 1,
                "impulse should appear at frame 340 (100 + " + lookaheadSamples + ") ±1");
        assertTrue(abs(peakVal) > 0.5, "output impulse amplitude " + peakVal);
    }

    // ── stereo link: same GR on both channels ────────────────────────

    @Test
    void stereoLinkLHotRQuiet() {
        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);
        // L hot (0 dBFS), R quiet (-30 dBFS)
        for (int i = 0; i < 20; i++) lim.process(stereoBlock(1.0, 0.0316));
        AudioBuffer out = stereoBlock(1.0, 0.0316);
        lim.process(out);

        double lInDb = 20.0 * log10(1.0);   // 0
        double rInDb = 20.0 * log10(0.0316); // -30
        double inDiff = lInDb - rInDb;        // ~30 dB

        double outDiff = peakDb(out, 0) - peakDb(out, 1);
        assertEquals(inDiff, outDiff, 0.5, "L-R difference preserved — same GR on both");
    }

    // ── transient catch: no burst sample exceeds threshold + 0.1 dB ──

    @Test
    void transientCatch() {
        int burstFrames = (int) (0.01 * SR); // 10 ms = 480 frames
        int silenceFrames = 480;
        int totalFrames = (burstFrames + silenceFrames) * 3;
        int totalBlocks = (totalFrames + BS - 1) / BS;

        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);
        double maxAllowed = pow(10.0, (THRESHOLD_DB + 0.1) / 20.0);
        for (int blk = 0; blk < totalBlocks; blk++) {
            AudioBuffer b = AudioBuffer.create(1, BS);
            int blkStart = blk * BS;
            for (int i = 0; i < BS; i++) {
                int t = blkStart + i;
                int cycle = burstFrames + silenceFrames;
                int pos = t % cycle;
                if (pos < burstFrames) {
                    b.data[0][i] = (float) sin(2.0 * PI * FREQ * t / SR);
                }
            }
            lim.process(b);
            for (int i = 0; i < BS; i++) {
                int t = blkStart + i;
                int cycle = burstFrames + silenceFrames;
                int pos = t % cycle;
                if (pos < burstFrames) {
                    assertTrue(abs(b.data[0][i]) <= maxAllowed + 1e-9,
                            "burst sample at t=" + t + " = " + b.data[0][i]
                                    + " exceeds threshold+" + 0.1 + " dB");
                }
            }
        }
    }

    // ── GR reported accurately ───────────────────────────────────────

    @Test
    void gainReductionReported() {
        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);
        for (int i = 0; i < 20; i++) lim.process(sineBlock(1, 1.0));
        lim.process(sineBlock(1, 1.0));

        assertEquals(1.0, lim.getGainReductionDb(), 0.5,
                "full-scale sine: GR ≈ 1 dB (threshold -1)");
    }

    // ── thread safety ────────────────────────────────────────────────

    @Test
    void setterThreadSafety() throws Exception {
        Limiter lim = new Limiter(SR).threshold(THRESHOLD_DB);
        Thread setter = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                lim.threshold(-3 + (i % 5));
                lim.release(20 + (i % 80));
                lim.lookahead(1 + (i % 10));
            }
        });
        setter.start();
        for (int i = 0; i < 200; i++) {
            AudioBuffer buf = sineBlock(1, 1.0);
            lim.process(buf);
            for (int f = 0; f < buf.frames; f++) {
                assertTrue(Double.isFinite(buf.data[0][f]),
                        "non-finite sample at block " + i + " frame " + f);
            }
        }
        setter.join();
    }

    // ── isIdle always true ───────────────────────────────────────────

    @Test
    void isIdleAlwaysTrue() {
        assertTrue(new Limiter(SR).isIdle());
        Limiter lim = new Limiter(SR);
        lim.process(sineBlock(1, 1.0));
        assertTrue(lim.isIdle());
    }
}
