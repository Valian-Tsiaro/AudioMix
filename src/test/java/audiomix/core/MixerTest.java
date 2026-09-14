package audiomix.core;

import audiomix.dsp.effects.Meter;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MixerTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double TAU = 2.0 * Math.PI;

    // ── helpers ──────────────────────────────────────────────────────

    private static SineSource sine(int freq, double level, long frames) {
        return new SineSource(SR, freq, level, 1, frames);
    }

    private static double analyticSum(double t, int f1, int f2, double lvl) {
        return lvl * Math.sin(TAU * f1 * t) + lvl * Math.sin(TAU * f2 * t);
    }

    // ── linearity ────────────────────────────────────────────────────

    @Test
    void linearity() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("A").setSource(sine(440, 0.25, -1));
        m.addChannel("B").setSource(sine(880, 0.25, -1));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        int blocks = 0;
        for (int i = 0; i < 4; i++) {
            m.processBlock(dest);
            blocks++;
        }

        long n0 = (long) (blocks - 1) * BS;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < BS; i++) {
                double t = (n0 + i) / (double) SR;
                float expected = (float) analyticSum(t, 440, 880, 0.25);
                assertEquals(expected, dest.data[ch][i], 1e-5,
                        "ch=" + ch + " frame=" + i);
            }
        }
    }

    // ── master gain dB ──────────────────────────────────────────────

    @Test
    void masterGainDb() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("s").setSource(sine(440, 0.25, -1));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        float[] ref = new float[BS];
        for (int i = 0; i < 3; i++) m.processBlock(dest);
        System.arraycopy(dest.data[0], 0, ref, 0, BS);
        float refPeak = peak(ref);

        m.getMaster().setGainDb(-6.0206);
        for (int i = 0; i < 3; i++) m.processBlock(dest);
        float dbPeak = peak(dest.data[0]);

        assertEquals(0.5, dbPeak / refPeak, 1e-3);
    }

    private static float peak(float[] data) {
        float max = 0;
        for (float v : data) {
            float a = Math.abs(v);
            if (a > max) max = a;
        }
        return max;
    }

    // ── master chain order ──────────────────────────────────────────

    @Test
    void masterChainOrder() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("x").setSource(sine(440, 0.25, -1));
        List<String> log = new ArrayList<>();

        m.getMaster().addEffect(buf -> log.add("A"));
        m.getMaster().addEffect(buf -> log.add("B"));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        m.processBlock(dest);
        assertEquals(List.of("A", "B"), log);
    }

    // ── finite sources ──────────────────────────────────────────────

    @Test
    void finiteSources() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("a").setSource(sine(440, 0.25, 4800));
        m.addChannel("b").setSource(sine(880, 0.25, 4800));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        int blocks = 0;
        while (!m.isExhausted()) {
            m.processBlock(dest);
            blocks++;
        }
        assertEquals(11, blocks, "4800 frames = 10 data blocks + 1 drain block");

        m.processBlock(dest);
        assertFalse(blockHasNonZero(dest));
        assertTrue(m.allIdle());
    }

    private static boolean blockHasNonZero(AudioBuffer buf) {
        for (float[] ch : buf.data) {
            for (float v : ch) {
                if (v != 0.0f) return true;
            }
        }
        return false;
    }

    // ── tail extends allIdle past exhaustion ────────────────────────

    @Test
    void tailExtendsAllIdle() {
        Mixer m = new Mixer(SR, BS);
        Channel ch = m.addChannel("t");
        ch.setSource(sine(440, 0.25, 4800));

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

        AudioBuffer dest = AudioBuffer.create(2, BS);
        int blocks = 0;
        while (!m.allIdle()) {
            m.processBlock(dest);
            blocks++;
        }
        assertTrue(blocks > 11, "tail extends past source exhaustion: " + blocks);
        assertTrue(blocks <= 16, "tail adds ~5 blocks: " + blocks);
        assertTrue(m.allIdle());
    }

    // ── containment: throwing source ─────────────────────────────────

    @Test
    void throwingSourceDoesNotAffectOtherChannel() {
        Mixer m = new Mixer(SR, BS);

        Channel bad = m.addChannel("bad");
        bad.setGain(1.0);
        final int[] calls = {0};
        bad.setSource(new Source() {
            @Override
            public int read(AudioBuffer b) {
                calls[0]++;
                if (calls[0] == 1) {
                    new SineSource(SR, 440, 0.25, 1, -1).read(b);
                    return b.frames;
                }
                throw new RuntimeException("boom");
            }
            @Override public void close() {}
        });

        Channel good = m.addChannel("good");
        good.setSource(sine(880, 0.25, -1));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        int numBlocks = 10;
        assertDoesNotThrow(() -> {
            for (int i = 0; i < numBlocks; i++) m.processBlock(dest);
        });

        long n0 = (numBlocks - 1L) * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.25 * Math.sin(TAU * 880 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    // ── containment: master throws ──────────────────────────────────

    @Test
    void masterThrowingEffectYieldsZeros() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("s").setSource(sine(440, 0.25, -1));
        m.getMaster().addEffect(buf -> { throw new RuntimeException("boom"); });

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        assertDoesNotThrow(() -> m.processBlock(dest));
        assertFalse(blockHasNonZero(dest));
    }

    // ── containment: Error-throwing channel (Mixer-level catch) ──────

    @Test
    void channelErrorDoesNotEscapeProcessBlock() {
        Mixer m = new Mixer(SR, BS);

        Channel bad = m.addChannel("bad");
        bad.setSource(sine(440, 0.25, -1));
        bad.addEffect(b -> { throw new OutOfMemoryError("test error"); });

        Channel good = m.addChannel("good");
        good.setSource(sine(880, 0.25, -1));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) m.processBlock(dest);
        });

        long n0 = 9L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.25 * Math.sin(TAU * 880 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    // ── containment: Error-throwing master (Mixer-level catch) ───────

    @Test
    void masterErrorYieldsZeros() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("s").setSource(sine(440, 0.25, -1));
        m.getMaster().addEffect(buf -> { throw new OutOfMemoryError("test error"); });

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        assertDoesNotThrow(() -> m.processBlock(dest));
        assertFalse(blockHasNonZero(dest));
    }

    // ── applyChainAndGain validation ─────────────────────────────────

    @Test
    void applyChainAndGainRejectsWrongFrameCount() {
        Bus bus = new Bus(SR, BS);
        AudioBuffer wrong = AudioBuffer.create(2, BS + 1);
        assertThrows(IllegalArgumentException.class, () -> bus.applyChainAndGain(wrong));
    }

    // ── validation ──────────────────────────────────────────────────

    @Test
    void mixerRejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new Mixer(0));
        assertThrows(IllegalArgumentException.class, () -> new Mixer(SR, 0));
    }

    @Test
    void busRejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new Bus(0, BS));
        assertThrows(IllegalArgumentException.class, () -> new Bus(SR, 0));
        Bus bus = new Bus(SR, BS);
        assertThrows(IllegalArgumentException.class, () -> bus.setGain(-1.0));
    }

    @Test
    void processBlockRejectsBadDest() {
        Mixer m = new Mixer(SR, BS);
        AudioBuffer mono = AudioBuffer.create(1, BS);
        AudioBuffer wrongSize = AudioBuffer.create(2, BS - 1);
        assertThrows(IllegalArgumentException.class, () -> m.processBlock(mono));
        assertThrows(IllegalArgumentException.class, () -> m.processBlock(wrongSize));
    }

    @Test
    void getChannelsIsUnmodifiable() {
        Mixer m = new Mixer(SR, BS);
        assertThrows(UnsupportedOperationException.class,
                () -> m.getChannels().add(new Channel("x", 1, SR, BS)));
    }

    @Test
    void getChannelReturnsNullForUnknownName() {
        Mixer m = new Mixer(SR, BS);
        assertNull(m.getChannel("nope"));
    }

    // ── meter transparency ──────────────────────────────────────────

    @Test
    void meterIsTransparent() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("A").setSource(sine(440, 0.25, -1));
        m.addChannel("B").setSource(sine(880, 0.25, -1));

        Meter meter = new Meter(1);
        m.getChannel("A").addEffect(meter);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        int blocks = 0;
        for (int i = 0; i < 4; i++) {
            m.processBlock(dest);
            blocks++;
        }

        long n0 = (long) (blocks - 1) * BS;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < BS; i++) {
                double t = (n0 + i) / (double) SR;
                float expected = (float) analyticSum(t, 440, 880, 0.25);
                assertEquals(expected, dest.data[ch][i], 1e-5,
                        "ch=" + ch + " frame=" + i);
            }
        }

        // meter was actually in the path
        assertTrue(meter.getPeakDbfs(0) > Double.NEGATIVE_INFINITY);
    }
}
