package audiomix.io;

/**
 * Blocking audio output sink — the engine's clock.
 * {@link #write} blocks for the duration of the hardware buffer cycle;
 * a real implementation must honour {@link Thread#interrupt()} so that
 * {@link audiomix.core.EngineThread#stop()} can unblock it promptly.
 */
public interface AudioSink {

    /**
     * Open the sink for playback.
     *
     * @param channels   number of channels (2 for stereo)
     * @param sampleRate sample rate in Hz (&gt; 0)
     * @param blockSize  frames per block (&gt; 0)
     * @throws IllegalStateException if already open
     */
    void open(int channels, int sampleRate, int blockSize);

    /**
     * Write one block of interleaved data. Blocks until the hardware
     * buffer cycle completes.
     *
     * @param data   non-interleaved sample data ({@code data.length == channels})
     * @param frames number of valid frames ({@code &le; blockSize})
     * @throws InterruptedException if the thread is interrupted while blocking
     */
    void write(float[][] data, int frames) throws InterruptedException;

    /** Close the sink and release any underlying resources. */
    void close();
}
