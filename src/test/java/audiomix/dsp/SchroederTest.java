package audiomix.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchroederTest {

    private static final double TWO_PI = 2.0 * Math.PI;

    // ── Comb ──────────────────────────────────────────────────────────

    @Test
    void combImpulseDecay() {
        int D = 17;
        int N = 4096;
        Comb c = new Comb(N);
        c.setDelaySamples(D);
        c.setFeedback(0.5);
        c.setDamping(0.0);
        assertEquals(D, c.getDelaySamples());

        float[] out = new float[N];
        out[0] = c.process(1.0f);
        for (int i = 1; i < N; i++) out[i] = c.process(0.0f);

        for (int k = 1; k * D < N; k++) {
            assertEquals(Math.pow(0.5, k), out[k * D], 1e-6,
                    "tap at k*D=" + (k * D));
        }

        double energy = 0.0;
        for (float v : out) energy += v * v;
        assertTrue(Double.isFinite(energy) && energy > 0);
    }

    @Test
    void combDampingReducesLaterTaps() {
        int D = 17;
        int N = 4096;

        float[] undamped = runComb(D, 0.5, 0.0, N);
        float[] damped   = runComb(D, 0.5, 0.5, N);

        assertEquals(1.0f, damped[0], 1e-6f, "first tap unchanged");
        for (int k = 1; k * D < N && Math.pow(0.5, k) >= 1e-4; k++) {
            assertTrue(Math.abs(damped[k * D]) < Math.abs(undamped[k * D]),
                    "damped tap at k*D=" + (k * D) + " should be smaller");
        }
    }

    @Test
    void combFeedbackAtLeastOneThrows() {
        Comb c = new Comb(100);
        assertThrows(IllegalArgumentException.class, () -> c.setFeedback(1.0));
        assertThrows(IllegalArgumentException.class, () -> c.setFeedback(1.5));
    }

    // ── Allpass ───────────────────────────────────────────────────────

    @Test
    void allpassImpulseFirstSampleAndEnergy() {
        int D = 5;
        int N = 2048;
        Allpass ap = new Allpass(N);
        ap.setDelaySamples(D);
        ap.setFeedback(0.5);

        float[] out = new float[N];
        out[0] = ap.process(1.0f);
        for (int i = 1; i < N; i++) out[i] = ap.process(0.0f);

        assertEquals(-0.5f, out[0], 1e-7f, "first sample = -g");

        double outEnergy = 0.0;
        for (float v : out) outEnergy += v * v;
        assertEquals(1.0, outEnergy, 0.05, "allpass energy within 5% of input");
    }

    @Test
    void allpassPreservesAmplitude() {
        double freq = 1000.0;
        int sampleRate = 44100;
        int N = 10000;
        double amp = 0.25;
        int settle = 200;

        for (int delay : new int[]{3, 11}) {
            Allpass ap = new Allpass(N);
            ap.setDelaySamples(delay);
            ap.setFeedback(0.5);

            double maxOut = 0.0;
            for (int i = 0; i < N; i++) {
                float in = (float) (amp * Math.sin(TWO_PI * freq * i / sampleRate));
                float out = ap.process(in);
                if (i >= settle) {
                    double a = Math.abs(out);
                    if (a > maxOut) maxOut = a;
                }
            }

            assertEquals(amp, maxOut, amp * 0.05,
                    "amplitude preserved at delay=" + delay);
        }
    }

    @Test
    void resetRestoresInitialState() {
        int D = 7;
        float[] signal = {0.5f, 0.3f, -0.2f, 0.8f, -0.1f, 0.6f, -0.7f,
                0.4f, -0.3f, 0.9f, -0.5f, 0.2f, -0.8f, 0.1f, -0.4f, 0.7f};

        Comb c = new Comb(100);
        c.setDelaySamples(D);
        c.setFeedback(0.5);
        c.setDamping(0.3);

        float[] first = processComb(c, signal);
        c.reset();
        float[] second = processComb(c, signal);
        assertArrayEquals(first, second, 1e-9f, "Comb reset determinism");

        Allpass ap = new Allpass(100);
        ap.setDelaySamples(D);
        ap.setFeedback(0.4);
        first = processAllpass(ap, signal);
        ap.reset();
        second = processAllpass(ap, signal);
        assertArrayEquals(first, second, 1e-9f, "Allpass reset determinism");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private float[] runComb(int D, double fb, double damp, int N) {
        Comb c = new Comb(N);
        c.setDelaySamples(D);
        c.setFeedback(fb);
        c.setDamping(damp);
        float[] out = new float[N];
        out[0] = c.process(1.0f);
        for (int i = 1; i < N; i++) out[i] = c.process(0.0f);
        return out;
    }

    private float[] processComb(Comb c, float[] in) {
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) out[i] = c.process(in[i]);
        return out;
    }

    private float[] processAllpass(Allpass ap, float[] in) {
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) out[i] = ap.process(in[i]);
        return out;
    }
}
