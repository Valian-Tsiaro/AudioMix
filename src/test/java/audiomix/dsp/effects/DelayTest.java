package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Channel;
import audiomix.core.Mixer;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;

import static java.lang.Math.*;
import static org.junit.jupiter.api.Assertions.*;

class DelayTest {

    private static final int SR = 48000;
    private static final int BS = 512;

    private static AudioBuffer impulseBlock(int ch) {
        AudioBuffer b = AudioBuffer.create(ch, BS);
        b.data[0][0] = 1.0f;
        return b;
    }

    private static AudioBuffer silenceBlock(int ch) {
        return AudioBuffer.create(ch, BS);
    }

    // ── single tap ───────────────────────────────────────────────────

    @Test
    void feedbackZeroSingleTap() {
        int delayMs = 250;
        int expectedTap = (int) round(delayMs * SR / 1000.0);
        int len = expectedTap + 10;

        Delay d = new Delay(SR, 1000).delayMs(delayMs).wet(1.0).dry(0.0).feedback(0.0);
        AudioBuffer out = AudioBuffer.create(1, len);
        out.data[0][0] = 1.0f;
        d.process(out);

        int tapIdx = -1;
        int tapCount = 0;
        for (int i = 0; i < len; i++) {
            if (abs(out.data[0][i]) > 0.5) {
                if (tapIdx < 0) tapIdx = i;
                tapCount++;
            }
        }
        assertEquals(1, tapCount, "exactly one tap above threshold");
        assertEquals(expectedTap, tapIdx, 1, "tap position");
        assertEquals(1.0f, out.data[0][tapIdx], 1e-6f, "tap amplitude");
    }

    // ── feedback taps ────────────────────────────────────────────────

    @Test
    void feedbackHalfDecaysTaps() {
        int delayMs = 250;
        int delaySamp = (int) round(delayMs * SR / 1000.0);
        int taps = 4;
        int len = taps * delaySamp + 10;

        Delay d = new Delay(SR, 1000).delayMs(delayMs).wet(1.0).dry(0.0).feedback(0.5);
        AudioBuffer out = AudioBuffer.create(1, len);
        out.data[0][0] = 1.0f;
        d.process(out);

        // dry=0: impulse enters line, output at frame 0 is 0.
        // First delayed tap at delaySamp reads impulse (1.0).
        // Second tap reads feedback*impulse (0.5), third 0.25, ...
        double expectedAmp = 1.0;
        for (int t = 1; t <= taps; t++) {
            int idx = t * delaySamp;
            if (idx >= len) break;
            assertEquals((float) expectedAmp, out.data[0][idx], 1e-6f,
                    "tap " + t + " at frame " + idx);
            expectedAmp *= 0.5;
        }
    }

    // ── dry path ─────────────────────────────────────────────────────

    @Test
    void dryOnlyPassthrough() {
        AudioBuffer in = AudioBuffer.create(1, BS);
        for (int i = 0; i < BS; i++) in.data[0][i] = (float) (i + 1);

        Delay d = new Delay(SR, 1000).delayMs(50.0).wet(0.0).dry(1.0).feedback(0.0);
        d.process(in);

        for (int i = 0; i < BS; i++) {
            assertEquals((float) (i + 1), in.data[0][i], 1e-6f, "frame " + i);
        }
    }

    // ── fractional delay ─────────────────────────────────────────────

    @Test
    void fractionalDelayInterpolates() {
        // 10.5 ms at 48 kHz is an exact 504 samples (frac 0) — not fractional;
        // use 504.5 samples to actually exercise the linear interpolation
        double delaySamples = 504.5;
        double delayMs = delaySamples * 1000.0 / SR;
        int len = 520;

        Delay d = new Delay(SR, 1000).delayMs(delayMs).wet(1.0).dry(0.0).feedback(0.0);
        AudioBuffer out = AudioBuffer.create(1, len);
        out.data[0][0] = 1.0f;
        d.process(out);

        // impulse energy splits evenly across the two neighbors of the tap
        float a = out.data[0][504], b = out.data[0][505];
        assertEquals(0.5f, a, 1e-6f, "frame 504");
        assertEquals(0.5f, b, 1e-6f, "frame 505");
        for (int i = 0; i < len; i++) {
            if (i != 504 && i != 505) assertEquals(0.0f, out.data[0][i], 1e-6f, "frame " + i);
        }
    }

    // ── isIdle ───────────────────────────────────────────────────────

    @Test
    void isIdleAfterDrain() {
        int delayMs = 250;
        int delaySamp = (int) round(delayMs * SR / 1000.0);

        Delay d = new Delay(SR, 250.0).delayMs(delayMs).wet(1.0).dry(0.0).feedback(0.0);
        assertFalse(d.isIdle(), "not idle before any process");

        d.process(impulseBlock(1));
        assertFalse(d.isIdle(), "not idle right after impulse");

        int maxBlocks = (int) ceil((double) delaySamp / BS) * 2 + 10;
        boolean becameIdle = false;
        for (int i = 0; i < maxBlocks; i++) {
            d.process(silenceBlock(1));
            if (d.isIdle()) { becameIdle = true; break; }
        }
        assertTrue(becameIdle, "should go idle after enough silence");
    }

    // ── mixer integration ────────────────────────────────────────────

    @Test
    void mixerRendersTailPastSource() {
        int sr = 48000, bs = 512, srcFrames = 4096;
        int delayMs = 200;
        int expectedTail = (int) round(delayMs * sr / 1000.0);

        Mixer m = new Mixer(sr, bs);
        Channel ch = m.addChannel("d");
        ch.setSource(new SineSource(sr, 440.0, 0.5, 1, srcFrames));
        ch.addEffect(new Delay(sr, 1000.0).delayMs(delayMs).wet(1.0).dry(0.0).feedback(0.0));

        AudioBuffer dest = AudioBuffer.create(2, bs);
        int blocks = 0;
        while (!m.allIdle()) {
            m.processBlock(dest);
            blocks++;
        }
        long totalFrames = (long) blocks * bs;
        assertTrue(totalFrames > srcFrames + expectedTail / 2,
                "output (" + totalFrames + ") must exceed source (" + srcFrames + ") + tail");
    }

    // ── builder validation ───────────────────────────────────────────

    @Test
    void invalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new Delay(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR, -1));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR).delayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR).feedback(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR).feedback(0.96));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR).wet(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Delay(SR).dry(Double.NaN));
    }
}
