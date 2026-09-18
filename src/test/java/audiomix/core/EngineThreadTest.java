package audiomix.core;

import audiomix.dsp.effects.Delay;
import audiomix.io.AudioSink;
import audiomix.io.WavReader;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class EngineThreadTest {

    private static final int SR = 48000;
    private static final int BS = 512;

    // ── helpers ──────────────────────────────────────────────────────

    private static Mixer twoSines() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("a").setSource(new SineSource(SR, 440, 0.25, 1, (long) (SR * 0.3)));
        m.addChannel("b").setSource(new SineSource(SR, 660, 0.25, 1, (long) (SR * 0.3)));
        return m;
    }

    private static boolean awaitStopped(EngineThread engine, long timeoutMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (engine.isRunning()) {
            if (System.nanoTime() >= end) return false;
            try { Thread.sleep(1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    private static boolean awaitRunning(EngineThread engine, long timeoutMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!engine.isRunning()) {
            if (System.nanoTime() >= end) return false;
            try { Thread.sleep(1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    /** Non-blocking sink; counts and optionally records blocks, optionally sleeps, optionally fails. */
    static final class TestSink implements AudioSink {
        int channels;
        int sampleRate;
        int blockSize;
        long writtenFrames;
        int writes;
        volatile boolean closed;
        final boolean record;
        final List<float[][]> blocks;
        final long sleepMs;
        volatile int failAfterWrite = -1;

        TestSink(long sleepMs, boolean record) {
            this.sleepMs = sleepMs;
            this.record = record;
            this.blocks = record ? new ArrayList<>() : null;
        }

        @Override
        public void open(int channels, int sampleRate, int blockSize) {
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.blockSize = blockSize;
        }

        @Override
        public void write(float[][] data, int frames) {
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            int n = ++writes;
            writtenFrames += frames;
            if (failAfterWrite >= 0 && n > failAfterWrite) throw new RuntimeException("sink boom");
            if (record) {
                float[][] copy = new float[data.length][];
                for (int c = 0; c < data.length; c++) copy[c] = data[c].clone();
                blocks.add(copy);
            }
        }

        @Override
        public void close() { closed = true; }
    }

    /** Reads whole blocks of a constant, throws once on the Nth read, then exhausts. */
    static final class ThrowingSource implements Source {
        private final int throwOnRead;
        private final float fill;
        private int reads;
        private boolean thrown;

        ThrowingSource(int throwOnRead, float fill) {
            this.throwOnRead = throwOnRead;
            this.fill = fill;
        }

        @Override
        public int read(AudioBuffer b) {
            if (thrown) return 0;
            reads++;
            if (reads == throwOnRead) {
                thrown = true;
                throw new RuntimeException("source boom");
            }
            for (float[] ch : b.data) Arrays.fill(ch, fill);
            return b.frames;
        }

        @Override
        public void close() { }
    }

    // ── realtime vs offline ──────────────────────────────────────────

    @Test
    void realtimeMatchesOfflineFrameCount(@TempDir Path tmp) {
        Path out = tmp.resolve("offline.wav");
        twoSines().renderToFile(out.toString());
        long offline;
        try (var r = new WavReader(out)) {
            offline = r.getFrameCount();
        }

        Mixer m = twoSines();
        TestSink sink = new TestSink(0, false);
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        assertTrue(awaitStopped(engine, 10_000), "engine auto-stops on allIdle");
        assertFalse(engine.isRunning());
        long diff = Math.abs(engine.framesWritten() - offline);
        assertTrue(diff <= BS, "engine frames vs offline render: diff=" + diff + " expected ≤ " + BS);
        assertEquals(0, engine.errorsLogged());
        assertEquals(2, sink.channels);
        assertTrue(sink.closed, "sink closed on auto-stop");
    }

    // ── containment: throwing source ─────────────────────────────────

    @Test
    void throwingSourceSurvivesAndAutoStops() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("bad").setSource(new ThrowingSource(4, 0.125f));
        m.addChannel("good").setSource(new SineSource(SR, 440, 0.25, 1, (long) (SR * 0.3)));
        TestSink sink = new TestSink(0, true);
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        assertTrue(awaitStopped(engine, 10_000), "auto-stop once the good channel drains");
        assertFalse(engine.isRunning());
        assertTrue(engine.framesWritten() > 0, "frames keep counting through the failure");
        assertEquals(sink.blocks.size(), engine.framesWritten() / BS);
        for (float[][] block : sink.blocks) {
            for (float[] ch : block) {
                for (float s : ch) assertTrue(Float.isFinite(s), "block contains only finite samples");
            }
        }
    }

    // ── containment: failing sink ────────────────────────────────────

    @Test
    void failingSinkCountsErrorsAndKeepsRunning() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("a").setSource(new SineSource(SR, 440, 0.25, 1, (long) (SR * 0.3)));
        TestSink sink = new TestSink(0, false);
        sink.failAfterWrite = 2;
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        assertTrue(awaitStopped(engine, 10_000), "auto-stop still works when the sink fails");
        assertFalse(engine.isRunning());
        assertTrue(engine.errorsLogged() > 0, "engine-boundary failures are counted");
        assertTrue(engine.framesWritten() > 0, "frames keep counting through failures");
    }

    // ── stop semantics ───────────────────────────────────────────────

    @Test
    void stopJoinsWithinSecondAndIsIdempotent() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("inf").setSource(new SineSource(SR, 440, 0.25, 1, -1));
        TestSink sink = new TestSink(1, false);
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        assertTrue(awaitRunning(engine, 2000));

        long t0 = System.nanoTime();
        engine.stop();
        long joinMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(joinMs < 1000, "stop joins within 1 s, took " + joinMs + " ms");
        assertFalse(engine.isRunning());
        assertTrue(sink.closed);

        engine.stop();
        assertFalse(engine.isRunning());
        assertThrows(IllegalStateException.class, engine::start);
    }

    // ── tail extends playback ────────────────────────────────────────

    @Test
    void delayTailPlaysBeyondSourceLength() {
        long srcFrames = (long) (SR * 0.3);
        Mixer m = new Mixer(SR, BS);
        Channel ch = m.addChannel("d");
        ch.setSource(new SineSource(SR, 440, 0.25, 1, srcFrames));
        ch.addEffect(new Delay(SR, 1000).delayMs(250).feedback(0.4).wet(1).dry(0));
        TestSink sink = new TestSink(0, false);
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        assertTrue(awaitStopped(engine, 30_000), "engine stops once the tail decays");
        assertFalse(engine.isRunning());
        assertTrue(engine.framesWritten() > srcFrames, "engine plays the delay tail");
    }

    // ── parameter hammering while running ────────────────────────────

    @Test
    void concurrentGainHammerRunsClean() throws Exception {
        Mixer m = new Mixer(SR, BS);
        Channel ch = m.addChannel("g");
        ch.setSource(new SineSource(SR, 330, 0.25, 1, -1));
        TestSink sink = new TestSink(1, false);
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        assertTrue(awaitRunning(engine, 2000));

        AtomicReference<Throwable> hammerError = new AtomicReference<>();
        Thread hammer = new Thread(() -> {
            try {
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                while (System.nanoTime() < end && engine.isRunning()) {
                    ch.setGainDb(-24 * Math.random());
                }
            } catch (Throwable t) { hammerError.set(t); }
        }, "gain-hammer");
        hammer.start();
        hammer.join(10_000);
        assertFalse(hammer.isAlive(), "hammer thread joined");
        assertNull(hammerError.get(), "no exception in hammer thread");

        engine.stop();
        assertFalse(engine.isRunning());
        assertEquals(0, engine.errorsLogged());
        assertTrue(engine.framesWritten() > 0);
    }

    // ── interrupt unblocks a stuck sink ─────────────────────────────

    /** Blocks until interrupted — simulates a real sink stuck on I/O. */
    static final class BlockingSink implements AudioSink {
        volatile boolean writeEntered;
        volatile boolean closed;

        @Override
        public void open(int channels, int sampleRate, int blockSize) { }

        @Override
        public void write(float[][] data, int frames) {
            writeEntered = true;
            try { Thread.sleep(Long.MAX_VALUE); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override
        public void close() { closed = true; }
    }

    @Test
    void interruptUnblocksStop() throws Exception {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("x").setSource(new SineSource(SR, 440, 0.25, 1, -1));
        BlockingSink sink = new BlockingSink();
        EngineThread engine = new EngineThread(m, m.getMaster(), sink);
        engine.start();
        // spin until the engine thread enters sink.write
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!sink.writeEntered && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(sink.writeEntered, "engine reached sink.write");

        long t0 = System.nanoTime();
        engine.stop();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(ms < 2000, "stop returned in " + ms + " ms");
        assertFalse(engine.isRunning());
        assertTrue(sink.closed, "sink closed after interrupt");
    }
}