package audiomix.io;

/**
 * Blocking audio output destination for the real-time engine. This is the
 * engine's clock: {@link #write} blocks until the device accepts a block,
 * pacing the render loop. All methods are called from the engine thread.
 */
public interface AudioSink {

    /**
     * Prepares the output for a stream.
     *
     * @param channels   channel count (2 for the built-in engine)
     * @param sampleRate frames per second (&gt; 0)
     * @param blockSize  frames per write call (&gt; 0)
     * @throws IllegalArgumentException if any argument is out of range
     */
    void open(int channels, int sampleRate, int blockSize);

    /**
     * Blocks until {@code frames} frames are accepted. Called once per block;
     * the blocking time is the real-time clock. Must return promptly when the
     * calling thread is interrupted: restore the interrupt flag and return, or
     * throw.
     *
     * @param data   non-interleaved samples, nominal range ±1.0
     * @param frames frames to write (≤ {@code data[0].length})
     */
    void write(float[][] data, int frames);

    /** Releases the output device; called once, after the last write. */
    void close();
}