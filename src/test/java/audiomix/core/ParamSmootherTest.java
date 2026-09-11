package audiomix.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ParamSmootherTest {

    @Test
    void freshDefaultsSettled() {
        ParamSmoother s = new ParamSmoother(48000, 10);
        assertEquals(0.0, s.currentValue());
        assertTrue(s.isSettled());
        s.setValue(1.0);
        assertTrue(s.isSettled());
        assertEquals(1.0, s.currentValue());
    }

    @Test
    void rampCompletesExactly() {
        ParamSmoother s = new ParamSmoother(48000, 10); // 480 samples
        s.setValue(0);
        s.setTarget(1);
        for (int i = 0; i < 480; i++) {
            double val = s.nextValue();
            assertTrue(val < 1.0 + 1e-12,
                    "overshot at sample " + (i + 1) + ": " + val);
        }
        assertEquals(1.0, s.currentValue(), 1e-12);
        assertTrue(s.isSettled());
    }

    @Test
    void rampMonotonicNonDecreasingWithConstantStep() {
        ParamSmoother s = new ParamSmoother(48000, 10);
        s.setValue(0);
        s.setTarget(1);
        double expected = 1.0 / 480;
        double prev = s.currentValue();
        for (int i = 0; i < 480; i++) {
            double val = s.nextValue();
            assertTrue(val >= prev - 1e-12, "not monotonic at sample " + (i + 1));
            assertEquals(expected, val - prev, 1e-12, "step mismatch at sample " + (i + 1));
            prev = val;
        }
        assertEquals(1.0, s.currentValue(), 1e-12);
    }

    @Test
    void reTargetMidRampLandsExactly() {
        ParamSmoother s = new ParamSmoother(48000, 10);
        s.setValue(0);
        s.setTarget(1);
        for (int i = 0; i < 100; i++) s.nextValue();
        s.setTarget(0.5);
        for (int i = 0; i < 480; i++) {
            double val = s.nextValue();
            assertTrue(val <= 0.5 + 1e-12, "overshot at re-target sample " + i);
        }
        assertEquals(0.5, s.currentValue(), 1e-12);
    }

    @Test
    void isSettledDuringAndAfterRamp() {
        ParamSmoother s = new ParamSmoother(48000, 10);
        s.setValue(0);
        s.setTarget(1);
        s.nextValue(); // sample 1 of 480
        assertFalse(s.isSettled());
        for (int i = 1; i < 480; i++) s.nextValue();
        assertTrue(s.isSettled());
    }

    @Test
    void concurrencySmoke() throws Exception {
        int rate = 48000;
        ParamSmoother s = new ParamSmoother(rate, 10);
        s.setValue(0);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Double> lastTarget = new AtomicReference<>(0.0);
        AtomicReference<Throwable> hammerError = new AtomicReference<>(null);

        Thread hammer = new Thread(() -> {
            while (!stop.get()) {
                double v = Math.random();
                lastTarget.set(v);
                s.setTarget(v);
            }
        });
        hammer.setUncaughtExceptionHandler((t, e) -> hammerError.set(e));
        hammer.start();

        // 200 ms at 48kHz = 9600 samples
        int totalSamples = rate * 200 / 1000;
        boolean finite = true;
        for (int i = 0; i < totalSamples; i++) {
            double val = s.nextValue();
            if (!Double.isFinite(val)) finite = false;
        }

        stop.set(true);
        hammer.join();
        assertNull(hammerError.get(), "hammer thread threw");
        assertTrue(finite, "non-finite value during hammering");
        s.setTarget(lastTarget.get());

        // converge to last target with full ramp window
        for (int i = 0; i < 480; i++) s.nextValue();
        assertEquals(lastTarget.get(), s.currentValue(), 1e-9);
    }
}
