package audiomix.core;

import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChannelTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double LEVEL = 0.25;
    private static final double FREQ = 160.0; // phase lands on peak at sample 75 per block

    // ── helpers ──────────────────────────────────────────────────────

    private static SineSource sine(long frames) {
        return new SineSource(SR, FREQ, LEVEL, 1, frames);
    }

    private static SineSource sineStereo(long frames) {
        return new SineSource(SR, FREQ, LEVEL, 2, frames);
    }

    private static void processN(Channel ch, int n) {
        for (int i = 0; i < n; i++) ch.process();
    }

    private static float blockPeak(AudioBuffer buf) {
        float max = 0;
        for (int ch = 0; ch < buf.channels(); ch++) {
            for (int i = 0; i < buf.frames; i++) {
                float v = Math.abs(buf.data[ch][i]);
                if (v > max) max = v;
            }
        }
        return max;
    }

    // ── level ────────────────────────────────────────────────────────

    @Test
    void monoGainHalfPeak() {
        Channel ch = new Channel("g", 1, SR, BS);
        ch.setGain(0.5);
        ch.setSource(sine(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);
        assertEquals(LEVEL * 0.5, blockPeak(dest), 1e-6);
    }

    // ── gain dB ──────────────────────────────────────────────────────

    @Test
    void gainDbRatio() {
        Channel ch = new Channel("db", 1, SR, BS);
        ch.setSource(sine(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);

        ch.setGain(1.0);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);
        float ref = blockPeak(dest);

        dest.clear();
        ch.setGainDb(-6.0206);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);
        float db = blockPeak(dest);

        assertEquals(0.5, db / ref, 1e-3);
    }

    // ── mute ─────────────────────────────────────────────────────────

    @Test
    void mutedContributesSilence() {
        Channel ch = new Channel("m", 1, SR, BS);
        ch.setGain(1.0);
        ch.setSource(sine(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);
        processN(ch, 3);

        ch.setMuted(true);
        ch.process();
        ch.mixInto(dest, false);

        for (int chIdx = 0; chIdx < 2; chIdx++) {
            for (int i = 0; i < BS; i++) {
                assertEquals(0.0f, dest.data[chIdx][i]);
            }
        }
        assertTrue(ch.isActive());
    }

    // ── insert order ─────────────────────────────────────────────────

    @Test
    void effectOrderMatchesAddOrder() {
        Channel ch = new Channel("ord", 1, SR, BS);
        ch.setSource(sine(-1));
        List<String> log = new ArrayList<>();

        ch.addEffect(b -> log.add("A"));
        ch.addEffect(b -> log.add("B"));
        ch.process();

        assertEquals(List.of("A", "B"), log);
    }

    @Test
    void removeEffectDuringProcessingTakesEffectNextBlock() {
        Channel ch = new Channel("rem", 1, SR, BS);
        ch.setSource(sine(-1));
        List<String> log = new ArrayList<>();

        Effect b = buf -> log.add("B");
        Effect a = buf -> {
            log.add("A");
            ch.removeEffect(b);
        };

        ch.addEffect(a);
        ch.addEffect(b);

        ch.process();
        assertEquals(List.of("A", "B"), log);

        log.clear();
        ch.process();
        assertEquals(List.of("A"), log);
    }

    // ── channel adaptation ───────────────────────────────────────────

    @Test
    void monoSineIntoStereoStripBothChannelsEqual() {
        Channel ch = new Channel("ad", 2, SR, BS);
        ch.setGain(1.0);
        ch.setSource(sine(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);

        assertEquals(LEVEL, blockPeak(dest), 1e-6);
        for (int i = 0; i < BS; i++) {
            assertEquals(dest.data[0][i], dest.data[1][i], 1e-6);
        }
    }

    @Test
    void stereoSineIntoMonoStripEqualsAverage() {
        Channel ch = new Channel("ad", 1, SR, BS);
        ch.setGain(1.0);
        ch.setSource(sineStereo(-1));
        AudioBuffer dest = AudioBuffer.create(2, BS);
        processN(ch, 3);
        ch.process();
        ch.mixInto(dest, false);

        assertEquals(LEVEL, blockPeak(dest), 1e-6);
        for (int i = 0; i < BS; i++) {
            assertEquals(dest.data[0][i], dest.data[1][i], 1e-6);
        }
    }

    // ── tail drain ───────────────────────────────────────────────────

    @Test
    void tailDrainsAfterSourceExhausted() {
        Channel ch = new Channel("t", 1, SR, BS);
        ch.setGain(1.0);
        ch.setSource(sine(4800));

        // Effect is idle once it has seen 5 consecutive all-zero-input calls.
        Effect tail = new Effect() {
            int zeroCalls;
            @Override
            public void process(AudioBuffer b) {
                boolean silence = true;
                for (int i = 0; i < b.frames && silence; i++) {
                    if (b.data[0][i] != 0.0f) silence = false;
                }
                if (silence) zeroCalls++;
            }
            @Override
            public boolean isIdle() { return zeroCalls >= 5; }
        };
        ch.addEffect(tail);

        // Process until inactive.
        int blocks = 0;
        while (ch.process()) blocks++;
        assertTrue(blocks >= 10, "active at least through source: " + blocks);
        assertTrue(blocks <= 15, "tail adds ~5 blocks: " + blocks);

        // Verify staging was zero during tail via mixInto.
        AudioBuffer dest = AudioBuffer.create(2, BS);
        ch.process();                // tail block (staging zeros)
        ch.mixInto(dest, false);     // contribution = zeros * gain = zeros
        assertEquals(0.0f, blockPeak(dest), 1e-10);
    }

    // ── gain ramp (analytic expected-contribution test) ──────────────

    @Test
    void gainRampIsClickFree() {
        Channel ch = new Channel("r", 1, SR, BS);
        ch.setSource(sine(-1));

        ch.setGain(0.0);
        processN(ch, 3);                // settle at gain 0

        ch.setGain(1.0);                // starts 480-sample ramp

        ch.process();                   // block 4: source read + ramp
        AudioBuffer dest = AudioBuffer.create(2, BS);
        ch.mixInto(dest, false);

        double step = 1.0 / 480.0;
        long samplesBefore = 3L * BS;
        double startPhase = 2.0 * Math.PI * FREQ * samplesBefore / SR;
        double phasePerSample = 2.0 * Math.PI * FREQ / SR;
        float maxDelta = 0.0f;
        for (int i = 0; i < BS; i++) {
            double g = Math.min(1.0, (i + 1) * step);
            double expected = LEVEL * Math.sin(startPhase + phasePerSample * i) * g;
            float delta = (float) Math.abs(dest.data[0][i] - expected);
            if (delta > maxDelta) maxDelta = delta;
        }
        assertTrue(maxDelta <= 1e-6, "max per-sample error: " + maxDelta);
    }

    // ── validation ───────────────────────────────────────────────────

    @Test
    void constructorRejectsBadChannels() {
        assertThrows(IllegalArgumentException.class,
                () -> new Channel("x", 0, SR, BS));
        assertThrows(IllegalArgumentException.class,
                () -> new Channel("x", 3, SR, BS));
    }

    @Test
    void setGainRejectsNegative() {
        Channel ch = new Channel("x", 1, SR, BS);
        assertThrows(IllegalArgumentException.class, () -> ch.setGain(-1.0));
    }

    @Test
    void processNoSourceReturnsFalse() {
        Channel ch = new Channel("x", 1, SR, BS);
        assertFalse(ch.process());
        assertFalse(ch.isActive());
    }

    @Test
    void getEffectsIsUnmodifiable() {
        Channel ch = new Channel("x", 1, SR, BS);
        assertThrows(UnsupportedOperationException.class,
                () -> ch.getEffects().add(b -> {}));
    }

    @Test
    void mixIntoRejectsBadDest() {
        Channel ch = new Channel("x", 1, SR, BS);
        AudioBuffer mono = AudioBuffer.create(1, BS);
        AudioBuffer wrongSize = AudioBuffer.create(2, BS - 1);
        assertThrows(IllegalArgumentException.class, () -> ch.mixInto(mono, false));
        assertThrows(IllegalArgumentException.class, () -> ch.mixInto(wrongSize, false));
    }

    // ── error degradation ────────────────────────────────────────────

    @Test
    void sourceExceptionDegradesToSilence() {
        Channel ch = new Channel("x", 1, SR, BS);
        ch.setSource(new Source() {
            @Override public int read(AudioBuffer b) { throw new IllegalStateException("boom"); }
            @Override public void close() {}
        });
        assertDoesNotThrow(ch::process);
        assertTrue(ch.isActive());   // not exhausted — retries next block
        AudioBuffer dest = AudioBuffer.create(2, BS);
        ch.mixInto(dest, false);
        assertEquals(0.0f, blockPeak(dest), 1e-10);
    }

    @Test
    void effectExceptionDegradesToSilence() {
        Channel ch = new Channel("x", 1, SR, BS);
        ch.setSource(sine(-1));
        ch.addEffect(b -> { throw new IllegalStateException("boom"); });
        assertDoesNotThrow(ch::process);
        assertTrue(ch.isActive());
        AudioBuffer dest = AudioBuffer.create(2, BS);
        ch.mixInto(dest, false);
        assertEquals(0.0f, blockPeak(dest), 1e-10);
    }

    // ── solo gating ──────────────────────────────────────────────────

    @Test
    void soloGating() {
        Channel ch = new Channel("s", 1, SR, BS);
        ch.setSource(sine(-1));
        processN(ch, 3);
        ch.process();
        AudioBuffer dest = AudioBuffer.create(2, BS);

        // not soloed, others soloed → silence
        ch.mixInto(dest, false);
        assertTrue(blockPeak(dest) > 0);
        dest.clear();
        ch.mixInto(dest, true);
        assertEquals(0.0f, blockPeak(dest), 1e-10);

        // soloed → audible even when others soloed
        ch.setSolo(true);
        ch.process();
        dest.clear();
        ch.mixInto(dest, true);
        assertEquals(LEVEL, blockPeak(dest), 1e-6);

        // solo beats mute
        ch.setMuted(true);
        ch.process();
        dest.clear();
        ch.mixInto(dest, true);
        assertEquals(LEVEL, blockPeak(dest), 1e-6);
    }
}
