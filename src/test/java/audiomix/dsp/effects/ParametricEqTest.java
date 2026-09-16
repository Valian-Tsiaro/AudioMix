package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.dsp.EqType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParametricEqTest {

    private static final int RATE = 48000;
    private static final int BLOCK = 512;
    private static final int LENGTH = 3 * RATE;
    private static final int SETTLE = (int) (LENGTH * 0.6);
    private static final double AMP = 0.25;

    private static void assertDb(double actual, double expected, double tol) {
        assertTrue(Math.abs(actual - expected) <= tol,
                () -> "expected " + expected + " +/- " + tol + " dB but was " + actual);
    }

    /** Measure after only settled frames. */
    private double settledDb(ParametricEq eq, double freq, int channels) {
        double peak = 0.0;
        AudioBuffer buf = AudioBuffer.create(channels, BLOCK);
        for (int start = 0; start < LENGTH; start += BLOCK) {
            int n = Math.min(BLOCK, LENGTH - start);
            for (int ch = 0; ch < channels; ch++) {
                for (int i = 0; i < n; i++) {
                    double phase = 2.0 * Math.PI * freq * (start + i) / RATE;
                    buf.data[ch][i] = (float) (AMP * Math.sin(phase));
                }
            }
            eq.process(buf);
            if (start >= SETTLE) {
                for (int ch = 0; ch < channels; ch++) {
                    for (int i = 0; i < n; i++) {
                        peak = Math.max(peak, Math.abs(buf.data[ch][i]));
                    }
                }
            }
        }
        return 20.0 * Math.log10(peak / AMP);
    }

    @Test
    void specShapeEqExample() {
        ParametricEq eq = new ParametricEq(RATE);
        eq.band(EqType.PEAK, 1000, 3.0, 1.0);
        assertDb(settledDb(eq, 1000, 1), 3.0, 0.5);
    }

    @Test
    void bandsStack() {
        ParametricEq eq = new ParametricEq(RATE);
        eq.band(EqType.PEAK, 1000, 3.0, 1.0);
        eq.band(EqType.PEAK, 5000, -3.0, 1.0);
        assertDb(settledDb(eq, 1000, 1), 3.0, 0.5);
        assertDb(settledDb(eq, 5000, 1), -3.0, 0.5);
    }

    @Test
    void removeBandFlattens() {
        ParametricEq eq = new ParametricEq(RATE);
        ParametricEq.Band band = eq.addBand(EqType.PEAK, 1000, 3.0, 1.0);
        assertDb(settledDb(eq, 1000, 1), 3.0, 0.5);

        eq.removeBand(band);
        assertDb(settledDb(eq, 1000, 1), 0.0, 0.5);
    }

    @Test
    void runtimeAddMidRender() {
        ParametricEq eq = new ParametricEq(RATE);
        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        double peak = 0.0;
        int settleBlock = (int) (SETTLE / BLOCK);
        boolean bandAdded = false;

        for (int start = 0; start < LENGTH; start += BLOCK) {
            int n = Math.min(BLOCK, LENGTH - start);
            double phase0 = 2.0 * Math.PI * 1000.0 * start / RATE;
            for (int i = 0; i < n; i++) {
                buf.data[0][i] = (float) (AMP * Math.sin(phase0 + 2.0 * Math.PI * 1000.0 * i / RATE));
            }

            if (!bandAdded && start >= settleBlock * BLOCK / 2) {
                eq.addBand(EqType.PEAK, 1000, 6.0, 1.0);
                bandAdded = true;
            }

            eq.process(buf);
            if (start > settleBlock * BLOCK) {
                for (int i = 0; i < n; i++) {
                    peak = Math.max(peak, Math.abs(buf.data[0][i]));
                }
            }
        }
        double db = 20.0 * Math.log10(peak / AMP);
        assertDb(db, 6.0, 0.5);
    }

    @Test
    void liveSetGainConverges() {
        ParametricEq eq = new ParametricEq(RATE);
        ParametricEq.Band band = eq.addBand(EqType.PEAK, 1000, 3.0, 1.0);
        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        double peak = 0.0;
        boolean gainChanged = false;

        for (int start = 0; start < LENGTH; start += BLOCK) {
            int n = Math.min(BLOCK, LENGTH - start);
            double phase0 = 2.0 * Math.PI * 1000.0 * start / RATE;
            for (int i = 0; i < n; i++) {
                buf.data[0][i] = (float) (AMP * Math.sin(phase0 + 2.0 * Math.PI * 1000.0 * i / RATE));
            }
            if (!gainChanged && start >= (int) (LENGTH * 0.25)) {
                band.setGainDb(6.0);
                gainChanged = true;
            }
            eq.process(buf);
            if (start > (int) (LENGTH * 0.4)) {
                for (int i = 0; i < n; i++) {
                    peak = Math.max(peak, Math.abs(buf.data[0][i]));
                }
            }
        }
        double db = 20.0 * Math.log10(peak / AMP);
        assertDb(db, 6.0, 0.5);
    }

    @Test
    void stereoIdenticalMonoToStereoLazyBiquads() {
        ParametricEq eq = new ParametricEq(RATE);
        eq.band(EqType.PEAK, 1000, 3.0, 1.0);

        AudioBuffer mono = AudioBuffer.create(1, BLOCK);
        for (int i = 0; i < BLOCK; i++) {
            mono.data[0][i] = (float) (AMP * Math.sin(2.0 * Math.PI * 1000.0 * i / RATE));
        }
        eq.process(mono);

        assertDb(settledDb(eq, 1000, 2), 3.0, 0.5);
    }

    @Test
    void stereoChannelsIdentical() {
        ParametricEq eq = new ParametricEq(RATE);
        eq.band(EqType.PEAK, 1000, 3.0, 1.0);

        double peakCh0 = 0.0, peakCh1 = 0.0;
        AudioBuffer buf = AudioBuffer.create(2, BLOCK);
        for (int start = 0; start < LENGTH; start += BLOCK) {
            int n = Math.min(BLOCK, LENGTH - start);
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < n; i++) {
                    double phase = 2.0 * Math.PI * 1000.0 * (start + i) / RATE;
                    buf.data[ch][i] = (float) (AMP * Math.sin(phase));
                }
            }
            eq.process(buf);
            if (start >= SETTLE) {
                for (int i = 0; i < n; i++) {
                    peakCh0 = Math.max(peakCh0, Math.abs(buf.data[0][i]));
                    peakCh1 = Math.max(peakCh1, Math.abs(buf.data[1][i]));
                }
            }
        }
        double db0 = 20.0 * Math.log10(peakCh0 / AMP);
        double db1 = 20.0 * Math.log10(peakCh1 / AMP);
        assertDb(db0, 3.0, 0.5);
        assertDb(db1, 3.0, 0.5);
        assertTrue(Math.abs(db0 - db1) < 0.1,
                () -> "channels should be identical, got ch0=" + db0 + " ch1=" + db1);
    }

    @Test
    void clearBandsFlatPassthrough() {
        ParametricEq eq = new ParametricEq(RATE);
        eq.band(EqType.PEAK, 1000, 6.0, 1.0);
        assertDb(settledDb(eq, 1000, 1), 6.0, 0.5);

        eq.clearBands();
        AudioBuffer out = AudioBuffer.create(1, BLOCK);
        for (int i = 0; i < BLOCK; i++) {
            out.data[0][i] = (float) (0.1 * Math.sin(2.0 * Math.PI * 440.0 * i / RATE));
        }
        AudioBuffer ref = AudioBuffer.create(1, BLOCK);
        ref.copyFrom(out);
        eq.process(out);

        for (int i = 0; i < BLOCK; i++) {
            final int fi = i;
            assertEquals(ref.data[0][i], out.data[0][i], 1e-6f,
                    () -> "frame " + fi + ": expected " + ref.data[0][fi] + " got " + out.data[0][fi]);
        }
    }

    @Test
    void isIdleAlwaysTrue() {
        ParametricEq eq = new ParametricEq(RATE);
        assertTrue(eq.isIdle());
        eq.band(EqType.PEAK, 1000, 6.0, 1.0);
        assertTrue(eq.isIdle());
    }

    @Test
    void getBandsReturnsCopy() {
        ParametricEq eq = new ParametricEq(RATE);
        ParametricEq.Band b = eq.addBand(EqType.PEAK, 1000, 3.0, 1.0);
        List<ParametricEq.Band> bands = eq.getBands();
        assertEquals(1, bands.size());
        assertSame(b, bands.get(0));
        assertThrows(UnsupportedOperationException.class, () -> bands.clear());
    }

    @Test
    void invalidArgsThrow() {
        ParametricEq eq = new ParametricEq(RATE);
        assertThrows(IllegalArgumentException.class,
                () -> eq.addBand(EqType.PEAK, 0, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> eq.addBand(EqType.PEAK, RATE / 2.0, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> eq.addBand(EqType.PEAK, 1000, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> eq.addBand(null, 1000, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> eq.addBand(EqType.PEAK, 1000, Double.NaN, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> eq.addBand(EqType.PEAK, 1000, Double.POSITIVE_INFINITY, 1.0));
    }

    @Test
    void invalidSetterArgsThrow() {
        ParametricEq eq = new ParametricEq(RATE);
        ParametricEq.Band band = eq.addBand(EqType.PEAK, 1000, 3.0, 1.0);
        assertThrows(IllegalArgumentException.class, () -> band.setFreq(0));
        assertThrows(IllegalArgumentException.class, () -> band.setFreq(RATE / 2.0));
        assertThrows(IllegalArgumentException.class, () -> band.setQ(0));
        assertThrows(IllegalArgumentException.class, () -> band.setGainDb(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> band.setGainDb(Double.POSITIVE_INFINITY));
    }
}
