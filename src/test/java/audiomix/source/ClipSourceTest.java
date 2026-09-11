package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.core.Channel;
import audiomix.core.Mixer;
import audiomix.core.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ClipSourceTest {

    private static final int RATE = 48000;
    private static final int BLOCK = 512;
    private static final double AMP = 0.25;
    private static final double FREQ = 440.0;

    // ── helpers ──────────────────────────────────────────────────

    private static float[] drain(Source src) {
        ArrayList<Float> list = new ArrayList<>();
        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        int n;
        while ((n = src.read(buf)) > 0) {
            for (int i = 0; i < n; i++) list.add(buf.get(0, i));
        }
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    private static float referenceSample(int targetFrame, double freq, double level, int totalFrames) {
        SineSource ref = new SineSource(RATE, freq, level, 1, (long) totalFrames);
        AudioBuffer refBuf = AudioBuffer.create(1, BLOCK);
        int pos = 0;
        while (pos <= targetFrame) {
            int filled = ref.read(refBuf);
            if (filled == 0) break;
            if (pos + filled > targetFrame) {
                return refBuf.get(0, targetFrame - pos);
            }
            pos += filled;
        }
        throw new RuntimeException("reference frame " + targetFrame + " not reached");
    }

    // ── LEAD-IN ──────────────────────────────────────────────────

    @Test
    void leadIn() {
        double offset = 0.5;
        double outSec = 1.0;
        int innerLen = (int) Math.round(outSec * RATE);
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, innerLen);
        ClipSource clip = new ClipSource(inner, RATE, offset, 0, outSec);

        float[] frames = drain(clip);

        int leadInEnd = (int) Math.round(offset * RATE);
        int totalExpected = leadInEnd + (int) Math.round((outSec) * RATE);

        assertEquals(totalExpected, frames.length);

        for (int i = 0; i < leadInEnd; i++) {
            assertEquals(0.0f, frames[i], 1e-7f, "lead-in zero at frame " + i);
        }

        assertEquals(0.0f, frames[leadInEnd], 1e-7f, "first sine sample (sin(0) = 0)");

        int quarterCycle = (int) Math.ceil(RATE / (4.0 * FREQ));
        float maxAmp = 0f;
        for (int i = leadInEnd; i < Math.min(leadInEnd + quarterCycle, frames.length); i++) {
            maxAmp = Math.max(maxAmp, Math.abs(frames[i]));
        }
        assertTrue(maxAmp >= AMP - 1e-3f,
                "amplitude should reach near " + AMP + " within quarter cycle, got " + maxAmp);
    }

    // ── RANGE ────────────────────────────────────────────────────

    @Test
    void range() {
        double inSec = 0.25, outSec = 0.75;
        int innerLen = RATE; // 1 s
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, innerLen);
        ClipSource clip = new ClipSource(inner, RATE, 0, inSec, outSec);

        float[] frames = drain(clip);

        int served = (int) Math.round((outSec - inSec) * RATE);
        assertEquals(served, frames.length);

        int refFrame = (int) Math.round(inSec * RATE);
        int refTotal = (int) Math.round(outSec * RATE);
        float ref = referenceSample(refFrame, FREQ, AMP, refTotal);
        assertEquals(ref, frames[0], 1e-7f, "first served sample matches reference");

        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        assertEquals(0, clip.read(buf));
    }

    // ── COMBINED ─────────────────────────────────────────────────

    @Test
    void combined() {
        double offset = 0.4, inSec = 0.1, outSec = 0.5;
        int serveFrames = (int) Math.round((outSec - inSec) * RATE);
        int leadInFrames = (int) Math.round(offset * RATE);
        int innerLen = (int) Math.round(outSec * RATE);
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, innerLen);
        ClipSource clip = new ClipSource(inner, RATE, offset, inSec, outSec);

        float[] frames = drain(clip);

        assertEquals(leadInFrames + serveFrames, frames.length);

        for (int i = 0; i < leadInFrames; i++) {
            assertEquals(0.0f, frames[i], 1e-7f, "lead-in zero at frame " + i);
        }

        int inFrames = (int) Math.round(inSec * RATE);
        float ref = referenceSample(inFrames, FREQ, AMP, innerLen);
        assertEquals(ref, frames[leadInFrames], 1e-7f, "first served sample");
    }

    // ── ZERO-LENGTH ──────────────────────────────────────────────

    @Test
    void zeroLengthClipServesFullLeadIn() {
        double offset = 0.5;
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, 1000);
        ClipSource clip = new ClipSource(inner, RATE, offset, 0, 0);

        float[] frames = drain(clip);

        int leadInEnd = (int) Math.round(offset * RATE);
        assertEquals(leadInEnd, frames.length);
        for (int i = 0; i < leadInEnd; i++) {
            assertEquals(0.0f, frames[i], 1e-7f, "lead-in zero at frame " + i);
        }
        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        assertEquals(0, clip.read(buf));
    }

    // ── SKIP PAST EOF ────────────────────────────────────────────

    @Test
    void skipPastEof() {
        int innerFrames = (int) (0.1 * RATE); // 4800
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, innerFrames);
        ClipSource clip = new ClipSource(inner, RATE, 0, 1.0, 2.0);

        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        assertEquals(0, clip.read(buf));
        assertEquals(0, clip.read(buf));
    }

    // ── PARTIAL READ ─────────────────────────────────────────────

    @Test
    void partialRead() {
        int frames = 100;
        PartialSource inner = new PartialSource(frames);
        ClipSource clip = new ClipSource(inner, RATE, 0, 0, (double) frames / RATE);

        float[] out = drain(clip);
        assertEquals(frames, out.length);
    }

    // ── POST-EXHAUSTION ──────────────────────────────────────────

    @Test
    void postExhaustion() {
        PartialSource inner = new PartialSource(100);
        ClipSource clip = new ClipSource(inner, RATE, 0, 0, 100.0 / RATE);

        AudioBuffer buf = AudioBuffer.create(1, BLOCK);
        while (clip.read(buf) > 0) {}
        assertEquals(0, clip.read(buf));
        assertEquals(0, clip.read(buf));
    }

    // ── CLOSE ────────────────────────────────────────────────────

    @Test
    void closeDelegates() {
        FakeSource inner = new FakeSource(1000);
        ClipSource clip = new ClipSource(inner, RATE, 0, 0, 1.0);
        assertFalse(inner.isClosed());
        clip.close();
        assertTrue(inner.isClosed());
    }

    // ── ARGS ─────────────────────────────────────────────────────

    @Test
    void invalidArgs() {
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, RATE);
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, -1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(null, RATE, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, Double.POSITIVE_INFINITY, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ClipSource(inner, RATE, 0, 0, Double.POSITIVE_INFINITY));
    }

    // ── INTEGRATION ──────────────────────────────────────────────

    @Test
    void mixerIntegration() {
        double offset = 0.1, inSec = 0, outSec = 0.5;
        int serveFrames = (int) Math.round((outSec - inSec) * RATE);
        int leadInFrames = (int) Math.round(offset * RATE);
        int totalFrames = leadInFrames + serveFrames;

        int innerLen = (int) Math.round(outSec * RATE);
        SineSource inner = new SineSource(RATE, FREQ, AMP, 1, innerLen);
        ClipSource clip = new ClipSource(inner, RATE, offset, inSec, outSec);

        Mixer mixer = new Mixer(RATE);
        Channel ch = mixer.addChannel("clip");
        ch.setSource(clip);

        AudioBuffer stereo = AudioBuffer.create(2, BLOCK);
        ArrayList<Float> allFrames = new ArrayList<>();
        int totalBlocks = 0;
        int maxBlocks = 200;
        boolean seenContent = false;

        for (int block = 0; block < maxBlocks; block++) {
            mixer.processBlock(stereo);
            boolean blockHasContent = false;
            for (int f = 0; f < BLOCK; f++) {
                float s = stereo.get(0, f);
                if (s != 0.0f) blockHasContent = true;
            }
            totalBlocks++;
            if (seenContent && !blockHasContent) break;
            seenContent = seenContent || blockHasContent;
            for (int f = 0; f < BLOCK; f++) {
                allFrames.add(stereo.get(0, f));
            }
            if (mixer.allIdle()) break;
        }

        int totalAccumulated = totalBlocks * BLOCK;
        assertTrue(Math.abs(totalAccumulated - totalFrames) <= 2 * BLOCK,
                "total " + totalAccumulated + " should be within 2 blocks of " + totalFrames);

        for (int i = 0; i < Math.min(leadInFrames, allFrames.size()); i++) {
            assertEquals(0.0f, allFrames.get(i), 1e-7f, "lead-in zero at frame " + i);
        }

        for (int i = 0; i < allFrames.size(); i++) {
            assertFalse(Float.isNaN(allFrames.get(i)), "NaN at frame " + i);
        }
    }

    // ── fakes ────────────────────────────────────────────────────

    private static class FakeSource implements Source {
        private int remaining;
        private boolean closed;

        FakeSource(int frames) { this.remaining = frames; }

        @Override
        public int read(AudioBuffer b) {
            int n = Math.min(b.frames, remaining);
            for (int i = 0; i < n; i++)
                for (int ch = 0; ch < b.channels(); ch++)
                    b.data[ch][i] = 0.5f;
            remaining -= n;
            return n;
        }

        @Override
        public void close() { closed = true; }

        boolean isClosed() { return closed; }
    }

    private static class PartialSource implements Source {
        private int remaining;

        PartialSource(int frames) { this.remaining = frames; }

        @Override
        public int read(AudioBuffer b) {
            int n = Math.min(b.frames, remaining);
            for (int i = 0; i < n; i++)
                for (int ch = 0; ch < b.channels(); ch++)
                    b.data[ch][i] = 0.5f;
            remaining -= n;
            return n;
        }

        @Override
        public void close() {}
    }
}
