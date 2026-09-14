package audiomix.core;

import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanBalanceTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double LEVEL = 0.25;

    private static SineSource sine(long frames) {
        return new SineSource(SR, 160.0, LEVEL, 1, frames);
    }

    private static SineSource sineStereo(long frames) {
        return new SineSource(SR, 160.0, LEVEL, 2, frames);
    }

    private static void processN(Channel ch, int n) {
        for (int i = 0; i < n; i++) ch.process();
    }

    private static float channelPeak(AudioBuffer buf, int ch) {
        float max = 0;
        for (int i = 0; i < buf.frames; i++) {
            float v = Math.abs(buf.data[ch][i]);
            if (v > max) max = v;
        }
        return max;
    }

    private static float blockPower(AudioBuffer buf) {
        double sum = 0;
        for (int ch = 0; ch < buf.channels(); ch++) {
            for (int i = 0; i < buf.frames; i++) {
                double v = buf.data[ch][i];
                sum += v * v;
            }
        }
        return (float) sum;
    }

    private AudioBuffer mixMono(Channel ch, double pan) {
        ch.setPan(pan);
        ch.setSource(sine(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);
        return dest;
    }

    private AudioBuffer mixStereo(Channel ch, double bal) {
        ch.setBalance(bal);
        ch.setSource(sineStereo(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);
        return dest;
    }

    // ── mono pan ─────────────────────────────────────────────────

    @Test
    void monoPanMinus05RatioAndPower() {
        Channel ch = new Channel("p", 1, SR, BS);
        AudioBuffer dest = mixMono(ch, -0.5);

        double[] ep = GainMath.equalPowerPan(-0.5);
        float lPeak = channelPeak(dest, 0);
        float rPeak = channelPeak(dest, 1);

        assertEquals(ep[0] / ep[1], lPeak / rPeak, 0.02,
                "L/R peak ratio");
        assertEquals(LEVEL * LEVEL, lPeak * lPeak + rPeak * rPeak, LEVEL * LEVEL * 0.01,
                "power invariance");
    }

    @Test
    void powerInvarianceSweep() {
        double refPower = -1;
        for (double x = -1; x <= 1.0001; x += 0.25) {
            Channel ch = new Channel("s", 1, SR, BS);
            AudioBuffer dest = mixMono(ch, x);
            double p = blockPower(dest);
            if (refPower < 0) {
                refPower = p;
            } else {
                assertEquals(refPower, p, refPower * 0.01,
                        "power at x=" + x);
            }
        }
    }

    @Test
    void monoPanCenter() {
        Channel ch = new Channel("c", 1, SR, BS);
        AudioBuffer dest = mixMono(ch, 0.0);

        float lPeak = channelPeak(dest, 0);
        float rPeak = channelPeak(dest, 1);
        float expected = (float) (LEVEL * Math.sqrt(2) / 2);

        assertEquals(expected, lPeak, 1e-3, "L at center");
        assertEquals(expected, rPeak, 1e-3, "R at center");
    }

    @Test
    void monoPanHardLeft() {
        Channel ch = new Channel("hl", 1, SR, BS);
        AudioBuffer dest = mixMono(ch, -1.0);

        for (int i = 0; i < BS; i++) {
            assertEquals(0f, dest.data[1][i], 1e-6f, "R at hard left, sample " + i);
        }
    }

    @Test
    void monoPanHardRight() {
        Channel ch = new Channel("hr", 1, SR, BS);
        AudioBuffer dest = mixMono(ch, 1.0);

        for (int i = 0; i < BS; i++) {
            assertEquals(0f, dest.data[0][i], 1e-6f, "L at hard right, sample " + i);
        }
    }

    // ── stereo balance ───────────────────────────────────────────

    @Test
    void stereoBalanceHalfLeft() {
        Channel ch = new Channel("b", 2, SR, BS);
        AudioBuffer ref = mixStereo(ch, 0.0);
        float refL = channelPeak(ref, 0);

        Channel ch2 = new Channel("b2", 2, SR, BS);
        AudioBuffer dest = mixStereo(ch2, 0.5);

        assertEquals(refL * 0.5, channelPeak(dest, 0), LEVEL * 0.01,
                "L halves at balance 0.5");
        assertEquals(channelPeak(ref, 1), channelPeak(dest, 1), LEVEL * 0.01,
                "R unchanged at balance 0.5");
    }

    // ── illegal state ────────────────────────────────────────────

    @Test
    void panOnStereoThrows() {
        Channel ch = new Channel("x", 2, SR, BS);
        assertThrows(IllegalStateException.class, () -> ch.setPan(0.5));
    }

    @Test
    void balanceOnMonoThrows() {
        Channel ch = new Channel("x", 1, SR, BS);
        assertThrows(IllegalStateException.class, () -> ch.setBalance(0.5));
    }
}
