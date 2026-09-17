package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import org.junit.jupiter.api.Test;

import static java.lang.Math.*;
import static org.junit.jupiter.api.Assertions.*;

class CompressorTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double FREQ = 440.0;

    private static AudioBuffer sineBlock(int ch, double level) {
        AudioBuffer b = AudioBuffer.create(ch, BS);
        for (int c = 0; c < ch; c++) {
            for (int i = 0; i < BS; i++) {
                b.data[c][i] = (float) (level * sin(2.0 * PI * FREQ * i / SR));
            }
        }
        return b;
    }

    private static AudioBuffer sineBlockStereo(double leftLevel, double rightLevel) {
        AudioBuffer b = AudioBuffer.create(2, BS);
        for (int i = 0; i < BS; i++) {
            b.data[0][i] = (float) (leftLevel * sin(2.0 * PI * FREQ * i / SR));
            b.data[1][i] = (float) (rightLevel * sin(2.0 * PI * FREQ * i / SR));
        }
        return b;
    }

    private static double peakDb(AudioBuffer b, int ch) {
        double peak = 0.0;
        for (int i = 0; i < b.frames; i++) {
            peak = max(peak, abs(b.data[ch][i]));
        }
        return peak > 0.0 ? 20.0 * log10(peak) : Double.NEGATIVE_INFINITY;
    }

    // ── exact math ──────────────────────────────────────────────────

    @Test
    void exactMathSteadyState() {
        Compressor c = new Compressor(SR).threshold(-18).ratio(4)
                .attack(1.0).release(50.0);
        // over = -6.02 - (-18) = 11.98, GR = 11.98 * (1 - 1/4) = 8.985
        // output ≈ -6.02 - 8.985 = -15.0 dBFS
        for (int i = 0; i < 20; i++) {
            c.process(sineBlock(1, 0.5));
        }
        AudioBuffer out = sineBlock(1, 0.5);
        c.process(out);

        assertEquals(-15.0, peakDb(out, 0), 0.5);
        assertEquals(8.985, c.getGainReductionDb(), 0.5);
    }

    // ── below threshold ─────────────────────────────────────────────

    @Test
    void belowThresholdPassesUnchanged() {
        Compressor c = new Compressor(SR).threshold(-18).ratio(4)
                .attack(1.0).release(50.0);
        // -30 dBFS ≈ 0.0316
        for (int i = 0; i < 10; i++) c.process(sineBlock(1, 0.0316));
        AudioBuffer out = sineBlock(1, 0.0316);
        c.process(out);

        assertEquals(20.0 * log10(0.0316), peakDb(out, 0), 0.1);
        assertEquals(0.0, c.getGainReductionDb(), 0.1);
    }

    // ── ratio 2 ─────────────────────────────────────────────────────

    @Test
    void ratioTwoOutputLevel() {
        Compressor c = new Compressor(SR).threshold(-18).ratio(2)
                .attack(1.0).release(50.0);
        // over = 11.98, GR = 11.98 * (1 - 1/2) = 5.99
        // output ≈ -6.02 - 5.99 = -12.01 dBFS
        for (int i = 0; i < 20; i++) c.process(sineBlock(1, 0.5));
        AudioBuffer out = sineBlock(1, 0.5);
        c.process(out);

        assertEquals(-12.0, peakDb(out, 0), 0.5);
    }

    // ── attack timing ───────────────────────────────────────────────

    @Test
    void attackTiming() {
        Compressor c = new Compressor(SR).threshold(-18).ratio(4)
                .attack(10.0).release(50.0);
        // run to steady state to measure the actual GR target (envelope droop at 440 Hz shifts it slightly)
        for (int i = 0; i < 20; i++) c.process(sineBlock(1, 0.5));
        double steadyGr = c.getGainReductionDb();

        // fresh compressor, measure attack from silence — 3× attack = 30 ms ≈ 3 blocks
        Compressor c2 = new Compressor(SR).threshold(-18).ratio(4)
                .attack(10.0).release(50.0);
        for (int i = 0; i < 3; i++) c2.process(sineBlock(1, 0.5));
        assertTrue(c2.getGainReductionDb() >= steadyGr * 0.9,
                "GR " + c2.getGainReductionDb() + " dB should be >= 90% steady-state " + steadyGr + " dB after 3× attack");
    }

    // ── release timing ──────────────────────────────────────────────

    @Test
    void releaseTiming() {
        Compressor c = new Compressor(SR).threshold(-18).ratio(4)
                .attack(1.0).release(50.0);
        // build steady-state GR
        for (int i = 0; i < 20; i++) c.process(sineBlock(1, 0.5));

        // drop below threshold — fresh quiet block each iteration
        int blocksForRelease = (int) ceil(50.0 * 3 / (BS * 1000.0 / SR)); // 3× release time in blocks
        for (int i = 0; i < blocksForRelease; i++) {
            c.process(sineBlock(1, 0.0316));
        }
        assertTrue(c.getGainReductionDb() < 0.5,
                "GR after release " + c.getGainReductionDb() + " should be < 0.5");
    }

    // ── stereo link ─────────────────────────────────────────────────

    @Test
    void stereoLinkSameGainOnBothChannels() {
        Compressor c = new Compressor(SR).threshold(-18).ratio(4)
                .attack(1.0).release(50.0);
        for (int i = 0; i < 20; i++) {
            c.process(sineBlockStereo(0.5, 0.0316));
        }
        AudioBuffer out = sineBlockStereo(0.5, 0.0316);
        c.process(out);

        // linked: both channels get the same gain — output difference = input difference
        double lIn = 20.0 * log10(0.5);   // -6.02
        double rIn = 20.0 * log10(0.0316); // -30.0
        double inDiff = lIn - rIn;          // ~24 dB

        double outDiff = peakDb(out, 0) - peakDb(out, 1);
        assertEquals(inDiff, outDiff, 0.5, "L-R difference preserved — same gain applied to both");

        assertEquals(-15.0, peakDb(out, 0), 0.5, "L channel");
        assertEquals(-39.0, peakDb(out, 1), 0.5, "R channel — same 9 dB GR from its own level");
    }

    // ── thread safety ───────────────────────────────────────────────

    @Test
    void setterThreadSafety() throws Exception {
        Compressor c = new Compressor(SR).threshold(-18).ratio(4)
                .attack(1.0).release(50.0);
        Thread setter = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                c.threshold(-20 + (i % 5));
                c.ratio(2 + (i % 3));
            }
        });
        setter.start();
        for (int i = 0; i < 200; i++) {
            AudioBuffer buf = sineBlock(1, 0.5);
            c.process(buf);
            for (int f = 0; f < buf.frames; f++) {
                assertTrue(Double.isFinite(buf.data[0][f]),
                        "non-finite sample at block " + i + " frame " + f);
            }
        }
        setter.join();
    }

    // ── isIdle ───────────────────────────────────────────────────────

    @Test
    void isIdleAlwaysTrue() {
        assertTrue(new Compressor(SR).isIdle());
        Compressor c = new Compressor(SR);
        c.process(sineBlock(1, 0.5));
        assertTrue(c.isIdle());
    }
}
