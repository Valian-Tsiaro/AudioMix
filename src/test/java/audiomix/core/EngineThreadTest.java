package audiomix.core;

import audiomix.dsp.effects.Delay;
import audiomix.io.AudioSink;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class EngineThreadTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final int FINITE_FRAMES = (int) (SR * 0.3); // 14400

    // ── TestSink ─────────────────────────────────────────────────────

    static class TestSink implements AudioSink {
        volatile boolean opened;
        volatile boolean closed;
        volatile int openedChannels;
        volatile int openedSampleRate;
        volatile int openedBlockSize;
        volatile long totalFrames;

        final List<float[][]> recorded = new ArrayList<>();
        final CopyOnWriteArrayList<Throwable> sinkErrors = new CopyOnWriteArrayList<>();

        volatile long sleepMsPerWrite;
        volatile int throwOnFirstN;        // first N writes throw RuntimeException
        private int writeCount;

        @Override
        public void open(int channels, int sampleRate, int blockSize) {
            openedChannels = channels;
            openedSampleRate = sampleRate;
            openedBlockSize = blockSize;
            opened = true;
        }

        @Override
        public void write(float[][] data, int frames) throws InterruptedException {
            if (sleepMsPerWrite > 0) {
                TimeUnit.MILLISECONDS.sleep(sleepMsPerWrite);
            }
            if (writeCount < throwOnFirstN) {
                writeCount++;
                throw new RuntimeException("test sink write failure #" + writeCount);
            }
            totalFrames += frames;
            // copy block (engine reuses buffer)
            float[][] copy = new float[data.length][];
            for (int ch = 0; ch < data.length; ch++) {
                copy[ch] = new float[frames];
                System.arraycopy(data[ch], 0, copy[ch], 0, frames);
            }
            recorded.add(copy);
        }

        @Override
        public void close() { closed = true; }

        void resetWriteCount() { writeCount = 0; }

        float peak() {
            float peak = 0;
            for (float[][] block : recorded) {
                for (float[] ch : block) {
                    for (float s : ch) peak = Math.max(peak, Math.abs(s));
                }
            }
            return peak;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static void awaitStopped(EngineThread e, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (e.isRunning() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static SineSource sine(int freq, double level, long frames) {
        return new SineSource(SR, freq, level, 1, frames);
    }

    // ── tests ────────────────────────────────────────────────────────

    @Test
    @Timeout(15)
    void fileMixAutoStop() throws Exception {
        Mixer mixer = new Mixer(SR, BS);
        mixer.addChannel("a").setSource(sine(440, 0.25, FINITE_FRAMES));
        mixer.addChannel("b").setSource(sine(880, 0.25, FINITE_FRAMES));

        TestSink sink = new TestSink();
        EngineThread engine = new EngineThread(mixer, mixer.getMaster(), sink);

        engine.start();
        awaitStopped(engine, 10_000);
        assertFalse(engine.isRunning(), "engine should auto-stop when allIdle");

        assertEquals(SR, sink.openedSampleRate);
        assertEquals(BS, sink.openedBlockSize);
        assertEquals(2, sink.openedChannels);
        assertTrue(sink.closed, "sink should be closed after engine stops");

        long frames = engine.framesWritten();
        assertTrue(frames >= FINITE_FRAMES && frames <= FINITE_FRAMES + 2 * BS,
                "framesWritten=" + frames + " expected ~" + FINITE_FRAMES
                        + " (engine writes full blocks + drain block, offline trims)");
    }

    @Test
    @Timeout(15)
    void throwingSourceAndSink() throws Exception {
        // throwing source: succeeds 3 blocks, throws 8 blocks, then exhausts
        final int[] calls = {0};
        final int throwStart = 4, throwEnd = 12;
        Source throwingSource = new Source() {
            @Override
            public int read(AudioBuffer b) {
                calls[0]++;
                if (calls[0] >= throwStart && calls[0] <= throwEnd) {
                    throw new RuntimeException("simulated source failure");
                }
                if (calls[0] > throwEnd) return 0;
                // first 3 blocks: fill with sine data
                return new SineSource(SR, 440, 0.25, 1, FINITE_FRAMES).read(b);
            }
            @Override public void close() {}
        };

        Mixer mixer = new Mixer(SR, BS);
        mixer.addChannel("bad").setSource(throwingSource);
        mixer.addChannel("good").setSource(sine(880, 0.25, FINITE_FRAMES));

        TestSink sink = new TestSink();
        sink.throwOnFirstN = 2; // sink also throws on first 2 writes

        EngineThread engine = new EngineThread(mixer, mixer.getMaster(), sink);
        engine.start();
        awaitStopped(engine, 10_000);

        assertTrue(engine.errorsLogged() > 0, "errorsLogged=" + engine.errorsLogged());
        assertTrue(engine.framesWritten() > 0, "should keep counting frames");
        assertFalse(engine.isRunning());
        assertTrue(sink.closed);
    }

    @Test
    @Timeout(15)
    void stopWhileRunning() throws Exception {
        Mixer mixer = new Mixer(SR, BS);
        mixer.addChannel("inf").setSource(sine(440, 0.25, -1)); // infinite

        TestSink sink = new TestSink();
        sink.sleepMsPerWrite = 10; // ensure thread is blocked in sink.write

        EngineThread engine = new EngineThread(mixer, mixer.getMaster(), sink);
        engine.start();
        TimeUnit.MILLISECONDS.sleep(100);

        long t0 = System.currentTimeMillis();
        engine.stop();
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(engine.isRunning());
        assertTrue(elapsed <= 1000, "stop() took " + elapsed + " ms, must join within 1 s");

        // idempotent double-stop
        engine.stop();
        assertFalse(engine.isRunning());
    }

    @Test
    @Timeout(30)
    void delayTail() throws Exception {
        int sourceFrames = 2048;
        int tailMs = 200;
        int tailFrames = (int) ((long) tailMs * SR / 1000); // 9600

        Mixer mixer = new Mixer(SR, BS);
        Channel ch = mixer.addChannel("src");
        ch.setSource(sine(440, 0.25, sourceFrames));
        ch.addEffect(new Delay(SR, 1000).delayMs(tailMs).feedback(0).wet(1.0).dry(0.0));

        TestSink sink = new TestSink();
        EngineThread engine = new EngineThread(mixer, mixer.getMaster(), sink);
        engine.start();
        awaitStopped(engine, 15_000);

        long frames = engine.framesWritten();
        assertTrue(frames > sourceFrames,
                "engine must play tail: framesWritten=" + frames + " > " + sourceFrames);
    }

    @Test
    @Timeout(10)
    void concurrentGainHammer() throws Exception {
        Mixer mixer = new Mixer(SR, BS);
        Channel ch1 = mixer.addChannel("a");
        Channel ch2 = mixer.addChannel("b");
        ch1.setSource(sine(440, 0.25, -1));
        ch2.setSource(sine(880, 0.25, -1));

        TestSink sink = new TestSink();
        EngineThread engine = new EngineThread(mixer, mixer.getMaster(), sink);
        engine.start();

        AtomicBoolean hammerDone = new AtomicBoolean(false);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        Thread hammer = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 200;
                int i = 0;
                while (!hammerDone.get() && System.currentTimeMillis() < deadline) {
                    ch1.setGainDb(-24.0 + (i % 48));
                    ch2.setGainDb(-24.0 + ((i + 12) % 48));
                    i++;
                    TimeUnit.MILLISECONDS.sleep(1);
                }
            } catch (Throwable t) { errors.add(t); }
        }, "gain-hammer");
        hammer.start();
        hammer.join(500);
        hammerDone.set(true);

        engine.stop();
        assertTrue(errors.isEmpty(), "hammer exceptions: " + errors);
    }
}
