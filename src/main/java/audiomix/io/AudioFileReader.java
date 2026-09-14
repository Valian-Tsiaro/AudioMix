package audiomix.io;

import audiomix.core.AudioBuffer;

/**
 * Streaming PCM file reader. Concrete implementations handle
 * container-specific parsing while presenting a uniform read API.
 *
 * <p>Not thread-safe; call {@link #read} from a single thread only.</p>
 */
public interface AudioFileReader extends AutoCloseable {

    /** Returns the number of audio channels (1–8). */
    int getChannels();

    /** Returns the sample rate in Hz. */
    int getSampleRate();

    /** Returns the total number of audio frames. */
    long getFrameCount();

    /** Returns the sample format. */
    WavFormat getFormat();

    /**
     * Reads audio frames into the buffer. Returns frames filled; 0 at
     * EOF (stays 0 forever). Partial fills near EOF.
     *
     * @param b destination buffer; channel count must match the file
     * @return frames actually read (0 at EOF)
     * @throws IllegalArgumentException if buffer channel count differs
     * @throws AudioIOException         if read fails
     */
    int read(AudioBuffer b);

    /** Closes the underlying input stream. */
    void close();
}
