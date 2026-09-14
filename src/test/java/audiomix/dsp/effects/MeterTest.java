package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MeterTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double FREQ = 6000.0; // peak hits 1.0 exactly at frame 2

    private static AudioBuffer sineBlock(double level) {
        AudioBuffer b = AudioBuffer.create(1, BS);
        SineSource s = new SineSource(SR, FREQ, level, 1, BS);
        s.read(b);
        return b;
    }

    private static AudioBuffer stereoBuffer(float leftLevel, float rightLevel) {
        AudioBuffer b = AudioBuffer.create(2, BS);
        for (int i = 0; i < BS; i++) {
            b.data[0][i] = leftLevel;
            b.data[1][i] = rightLevel;
        }
        return b;
    }

    // ── full-scale sine ──────────────────────────────────────────────

    @Test
    void fullScalePeakAndRms() {
        Meter m = new Meter(1);
        for (int i = 0; i < 3; i++) m.process(sineBlock(1.0));

        assertEquals(0.0, m.getPeakDbfs(0), 0.1);
        assertEquals(-3.01, m.getRmsDbfs(0), 0.1);
        assertTrue(m.clipped(0));
    }

    // ── level 0.5 ───────────────────────────────────────────────────

    @Test
    void halfScalePeak() {
        Meter m = new Meter(1);
        m.process(sineBlock(0.5));

        assertEquals(-6.02, m.getPeakDbfs(0), 0.1);
        assertFalse(m.clipped(0));
    }

    // ── resetPeak then silence ───────────────────────────────────────

    @Test
    void resetPeakThenSilence() {
        Meter m = new Meter(1);
        m.process(sineBlock(1.0));
        assertTrue(m.getPeakDbfs(0) > Double.NEGATIVE_INFINITY);

        m.resetPeak();
        AudioBuffer silence = AudioBuffer.create(1, BS);
        m.process(silence);

        assertEquals(Double.NEGATIVE_INFINITY, m.getPeakDbfs(0));
        assertEquals(Double.NEGATIVE_INFINITY, m.getRmsDbfs(0));
    }

    // ── stereo independence ──────────────────────────────────────────

    @Test
    void stereoChannelsIndependent() {
        Meter m = new Meter(2);
        m.process(stereoBuffer(1.0f, 0.25f));

        assertEquals(0.0, m.getPeakDbfs(0), 0.01);
        assertEquals(-12.04, m.getPeakDbfs(1), 0.1);
        assertTrue(m.clipped(0));
        assertFalse(m.clipped(1));
    }

    // ── poll race smoke ──────────────────────────────────────────────

    @Test
    void pollRaceNoException() throws Exception {
        Meter m = new Meter(1);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> fail = new AtomicReference<>();
        Thread poller = new Thread(() -> {
            while (!stop.get()) {
                double v = m.getPeakDbfs(0);
                if (Double.isNaN(v)) {
                    fail.compareAndSet(null, "NaN from getPeakDbfs");
                    break;
                }
                double rms = m.getRmsDbfs(0);
                if (Double.isNaN(rms)) {
                    fail.compareAndSet(null, "NaN from getRmsDbfs");
                    break;
                }
            }
        });
        poller.start();
        for (int i = 0; i < 500; i++) m.process(sineBlock(0.5));
        stop.set(true);
        poller.join();
        assertNull(fail.get());
    }

    // ── isIdle ───────────────────────────────────────────────────────

    @Test
    void isIdleAlwaysTrue() {
        assertTrue(new Meter(1).isIdle());
        Meter m = new Meter(1);
        m.process(sineBlock(1.0));
        assertTrue(m.isIdle());
    }

    // ── resetPeak does not clear clip ────────────────────────────────

    @Test
    void resetPeakPreservesClip() {
        Meter m = new Meter(1);
        m.process(sineBlock(1.0));
        assertTrue(m.clipped(0));

        m.resetPeak();
        assertEquals(Double.NEGATIVE_INFINITY, m.getPeakDbfs(0));
        assertTrue(m.clipped(0)); // clip flag survives resetPeak
    }

    // ── clearClip clears the flag ────────────────────────────────────

    @Test
    void clearClipClearsFlag() {
        Meter m = new Meter(1);
        m.process(sineBlock(1.0));
        assertTrue(m.clipped(0));

        m.clearClip();
        assertFalse(m.clipped(0));
        // peak still persists across clearClip
        assertEquals(0.0, m.getPeakDbfs(0), 0.1);
    }
}
