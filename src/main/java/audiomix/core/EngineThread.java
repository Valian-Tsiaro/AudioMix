package audiomix.core;

import audiomix.io.AudioSink;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Pull-engine thread: pulls blocks from a {@link Mixer} bus into an
 * {@link AudioSink} in a loop. The sink's blocking {@code write} call
 * acts as the clock.
 *
 * <p>Auto-stops when {@link Mixer#allIdle()} returns {@code true}
 * (all sources exhausted and effect tails drained); restarting
 * playback requires a new {@link #start()} call on this or a fresh
 * {@code EngineThread}.
 *
 * <p>Per-block containment: any {@link Throwable} that escapes the
 * mixer is caught — the block is zeroed, silence is written to the
 * sink, the warning is logged, and the error is counted. After
 * 1000 consecutive block errors the engine stops (misconfigured
 * graph).</p>
 *
 * <p>A real sink must honour {@link Thread#interrupt()} so that
 * {@link #stop()} can unblock it promptly.</p>
 *
 * <p>Thread-safety: {@link #start()}, {@link #stop()}, and
 * {@link #isRunning()} are safe to call from any thread.</p>
 */
public final class EngineThread {

    private static final Logger LOG = Logger.getLogger(EngineThread.class.getName());
    private static final int MAX_CONSECUTIVE_ERRORS = 1000;

    private final Mixer mixer;
    private final Bus bus;
    private final AudioSink sink;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong errorsLogged = new AtomicLong();

    /**
     * Creates a new engine.
     *
     * @param mixer source mixer
     * @param bus   bus to render (currently must be {@code mixer.getMaster()})
     * @param sink  blocking audio output sink
     * @throws IllegalArgumentException if bus is not the master bus
     * @throws NullPointerException     if any argument is null
     */
    public EngineThread(Mixer mixer, Bus bus, AudioSink sink) {
        if (mixer == null) throw new NullPointerException("mixer is null");
        if (bus == null) throw new NullPointerException("bus is null");
        if (sink == null) throw new NullPointerException("sink is null");
        if (bus != mixer.getMaster()) {
            throw new IllegalArgumentException("bus must be the mixer's master bus");
        }
        this.mixer = mixer;
        this.bus = bus;
        this.sink = sink;
    }

    /**
     * Start the engine. Idempotent after auto-stop or manual {@link #stop()};
     * throws {@link IllegalStateException} if called while already running.
     *
     * @throws IllegalStateException if the engine is already running
     */
    public void start() {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("engine already running");
        }
        framesWritten.set(0);
        errorsLogged.set(0);
        Thread t = new Thread(this::run, "audiomix-engine");
        thread = t;
        t.start();
    }

    /**
     * Stop the engine. Safe to call while already stopped (no-op) or
     * while running (sets flag, interrupts thread, joins within 1 s).
     */
    public void stop() {
        if (!running.get()) return;
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Returns {@code true} while the engine thread is alive. */
    public boolean isRunning() { return running.get(); }

    /** Total frames written to the sink since the last {@link #start()}. */
    public long framesWritten() { return framesWritten.get(); }

    /** Total per-block errors contained since the last {@link #start()}. */
    public long errorsLogged() { return errorsLogged.get(); }

    private void run() {
        int blockSize = mixer.getBlockSize();
        AudioBuffer buf = AudioBuffer.create(2, blockSize);
        int consecutiveErrors = 0;

        try {
            sink.open(2, mixer.getSampleRate(), blockSize);

            while (running.get()) {
                try {
                    mixer.renderBusBlock(bus, buf);
                    sink.write(buf.data, buf.frames);
                    framesWritten.addAndGet(buf.frames);
                    consecutiveErrors = 0;
                    if (mixer.allIdle()) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    errorsLogged.incrementAndGet();
                    consecutiveErrors++;
                    LOG.warning("engine block failed: " + t.getMessage());
                    buf.clear();
                    try {
                        sink.write(buf.data, buf.frames);
                        framesWritten.addAndGet(buf.frames);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable t2) {
                        // sink broken — count once, do not double-count the render error
                    }
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        LOG.warning("engine stopped after " + MAX_CONSECUTIVE_ERRORS
                                + " consecutive block errors");
                        break;
                    }
                }
            }
        } finally {
            running.set(false);
            try { sink.close(); } catch (Throwable t) {
                LOG.warning("engine sink close failed: " + t.getMessage());
            }
        }
    }
}
