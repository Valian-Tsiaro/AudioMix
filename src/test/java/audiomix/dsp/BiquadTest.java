package audiomix.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiquadTest {

    private static final int RATE = 48000;
    private static final int BLOCK = 512;
    private static final int LENGTH = 3 * RATE;
    private static final int SETTLE = (int) (LENGTH * 0.6);
    private static final double AMP = 0.25;

    private double sweep(Biquad bq, double inFreq) {
        double peak = 0.0;
        for (int i = 0; i < LENGTH; i++) {
            double phase = 2.0 * Math.PI * inFreq * i / RATE;
            float out = bq.process((float) (AMP * Math.sin(phase)));
            if (i >= SETTLE) {
                peak = Math.max(peak, Math.abs(out));
            }
        }
        return 20.0 * Math.log10(peak / AMP);
    }

    private static void assertDb(double actual, double expected, double tol) {
        assertTrue(Math.abs(actual - expected) <= tol,
                () -> "expected " + expected + " +/- " + tol + " dB but was " + actual);
    }

    private static void assertAtMost(double actual, double max) {
        assertTrue(actual <= max, () -> "expected <= " + max + " dB but was " + actual);
    }

    @Test
    void highpassResponse() {
        Biquad bq = new Biquad();
        bq.configure(EqType.HIGHPASS, 200, 0, 0.707, RATE);
        assertAtMost(sweep(bq, 20), -35);
        assertDb(sweep(bq, 200), -3, 1);
        assertDb(sweep(bq, 4000), 0, 1);
    }

    @Test
    void lowpassResponse() {
        Biquad bq = new Biquad();
        bq.configure(EqType.LOWPASS, 2000, 0, 0.707, RATE);
        assertDb(sweep(bq, 20), 0, 1);
        assertDb(sweep(bq, 2000), -3, 1);
        assertAtMost(sweep(bq, 15000), -35);
    }

    @Test
    void peakBoostAndSkirts() {
        Biquad bq = new Biquad();
        bq.configure(EqType.PEAK, 1000, 6, 1.0, RATE);
        assertDb(sweep(bq, 1000), 6, 1);
        assertDb(sweep(bq, 200), 0, 1.5);
        assertDb(sweep(bq, 5000), 0, 1.5);
    }

    @Test
    void peakCut() {
        Biquad bq = new Biquad();
        bq.configure(EqType.PEAK, 1000, -6, 1.0, RATE);
        assertDb(sweep(bq, 1000), -6, 1);
    }

    @Test
    void lowShelfResponse() {
        Biquad bq = new Biquad();
        bq.configure(EqType.LOW_SHELF, 200, 6, 0.707, RATE);
        assertDb(sweep(bq, 50), 6, 1);
        assertDb(sweep(bq, 5000), 0, 1);
    }

    @Test
    void highShelfResponse() {
        Biquad bq = new Biquad();
        bq.configure(EqType.HIGH_SHELF, 3000, 6, 0.707, RATE);
        assertDb(sweep(bq, 10000), 6, 1);
        assertDb(sweep(bq, 200), 0, 1);
    }

    @Test
    void notchResponse() {
        Biquad bq = new Biquad();
        bq.configure(EqType.NOTCH, 1000, 0, 8, RATE);
        assertAtMost(sweep(bq, 1000), -20);
        assertDb(sweep(bq, 200), 0, 1);
    }

    private float[] sineInputs(int frames, double freq) {
        float[] in = new float[frames];
        for (int i = 0; i < frames; i++) {
            in[i] = (float) (AMP * Math.sin(2.0 * Math.PI * freq * i / RATE));
        }
        return in;
    }

    private float[] run(Biquad bq, float[] in) {
        float[] out = new float[in.length];
        for (int start = 0; start < in.length; start += BLOCK) {
            int n = Math.min(BLOCK, in.length - start);
            for (int i = start; i < start + n; i++) {
                out[i] = bq.process(in[i]);
            }
        }
        return out;
    }

    @Test
    void resetRestoresInitialSequence() {
        Biquad bq = new Biquad();
        bq.configure(EqType.PEAK, 1000, 6, 1.0, RATE);
        float[] in = sineInputs(2000, 1000);
        float[] first = run(bq, in);
        bq.reset();
        float[] second = run(bq, in);
        assertArrayEquals(first, second, 1e-9f);
    }

    @Test
    void invalidArgumentsThrow() {
        Biquad bq = new Biquad();
        assertThrows(IllegalArgumentException.class,
                () -> bq.configure(EqType.PEAK, 0, 0, 1, RATE));
        assertThrows(IllegalArgumentException.class,
                () -> bq.configure(EqType.PEAK, RATE / 2.0, 0, 1, RATE));
        assertThrows(IllegalArgumentException.class,
                () -> bq.configure(EqType.PEAK, RATE, 0, 1, RATE));
        assertThrows(IllegalArgumentException.class,
                () -> bq.configure(EqType.PEAK, 1000, 0, 0, RATE));
    }
}
