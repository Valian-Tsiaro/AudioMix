package audiomix.core;

import audiomix.io.AudioSink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Real-time pull engine: drives one {@link Mixer} into an {@link AudioSink}
 * on a dedicated thread. The sink's blocking {@link AudioSink#write} is the
 * clock. Per-block containment: any failure degrades the block to silence,
 * logs a warning, and counts it; after 1000 consecutive failed blocks the
 * engine stops (misconfigured graph).
 * <p>
 * The engine auto-stops as soon as {@link Mixer#allIdle()} holds after a
 * block — finite playback ends by itself. Restarting playback after the
 * sources exhaust therefore requires starting a new engine.
 * <p>
 * {@code allIdle()} is checked after rendering a block but <em>before</em>
 * writing it to the sink. When auto-stop fires the final rendered block
 * (containing only silence) is not written. This bounds the frame-count
 * overshoot to less than one block vs an offline render that trims trailing
 * silence. Effect implementations must satisfy: once {@link Effect#isIdle()}
 * returns {@code true}, subsequent output is silent.
 * <p>
 * {@link #stop()} is idempotent, interrupts the engine thread so a blocking
 * sink is released, and joins within 1 s.
 */
public final class EngineThread {

    private static final Logger LOG = Logger.getLogger(EngineThread.class.getName());
    private static final int MAX_CONSECUTIVE_ERRORS = 1000;
    private static final int STEREO = 2;

    private final Mixer mixer;
    private final Bus bus;
    private final AudioSink sink;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong errorsLogged = new AtomicLong();

    /**
     * @param mixer mixer to pull (non-null)
     * @param bus   bus to render into the sink; the mixer's master for now
     * @param sink  blocking output destination (non-null)
     * @throws IllegalArgumentException if any argument is null
     */
    public EngineThread(Mixer mixer, Bus bus, AudioSink sink) {
        if (mixer == null) throw new IllegalArgumentException("mixer is null");
        if (bus == null) throw new IllegalArgumentException("bus is null");
        if (sink == null) throw new IllegalArgumentException("sink is null");
        this.mixer = mixer;
        this.bus = bus;
        this.sink = sink;
        this.thread = new Thread(this::run, "audiomix-engine");
    }

    /**
     * Starts the engine thread. Single-shot: a second call throws.
     * A new engine is required to play again after auto-stop.
     *
     * @throws IllegalStateException if already started
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("engine already started");
        }
        running.set(true);
        thread.start();
    }

    /**
     * Requests stop and joins the engine thread within 1 s. Idempotent.
     * Interrupts the engine thread so a blocking {@link AudioSink#write}
     * can unblock promptly.
     */
    public void stop() {
        running.set(false);
        if (Thread.currentThread() == thread) return;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True while the engine thread is rendering. */
    public boolean isRunning() { return running.get(); }

    /** Total frames pushed to the sink (full blocks). Monotonic. Counts silence blocks written during error containment even if the sink also rejects the retry. Also counts blocks whose write was skipped by an interrupt. */
    public long framesWritten() { return framesWritten.get(); }

    /** Number of per-block failures contained by the engine. Monotonic. Counts each failed render or primary write; retry-failure is not double-counted. */
    public long errorsLogged() { return errorsLogged.get(); }

    private void run() {
        int blockSize = mixer.getBlockSize();
        AudioBuffer buf = AudioBuffer.create(STEREO, blockSize);
        long consecutive = 0;
        try {
            sink.open(STEREO, mixer.getSampleRate(), blockSize);
        } catch (Throwable t) {
            LOG.warning("engine sink open failed: " + t.getMessage());
            return;
        }
        try {
            while (running.get()) {
                boolean ok = true;
                try {
                    mixer.renderBusBlock(bus, buf);
                    if (mixer.allIdle()) break;
                    sink.write(buf.data, buf.frames);
                } catch (Throwable t) {
                    ok = false;
                    buf.clear();
                    errorsLogged.incrementAndGet();
                    LOG.warning("engine block failed: " + t.getMessage());
                    if (++consecutive >= MAX_CONSECUTIVE_ERRORS) break;
                    try {
                        sink.write(buf.data, buf.frames);
                    } catch (Throwable ignored) {
                    }
                }
                if (ok) consecutive = 0;
                framesWritten.addAndGet(blockSize);
            }
        } finally {
            running.set(false);
            try { sink.close(); } catch (Throwable t) {
                LOG.warning("engine sink close failed: " + t.getMessage());
            }
        }
    }
}