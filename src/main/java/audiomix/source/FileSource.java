package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.core.Source;
import audiomix.dsp.Resampler;
import audiomix.io.AudioIOException;
import audiomix.io.WavReader;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * File-backed {@link Source} that reads a WAV and resamples to the
 * project sample rate on the fly. At EOF the source returns 0 forever.
 *
 * <p>Channel mapping: a mono file is duplicated to every channel of the
 * destination buffer. A multi-channel file is mapped index-by-index;
 * extra file channels are dropped when the buffer has fewer channels.</p>
 *
 * <p>Not thread-safe; call from one thread only.</p>
 */
public final class FileSource implements Source, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FileSource.class.getName());

    private final WavReader reader;
    private final Resampler resampler;
    private final int fileChannels;

    private AudioBuffer inBuf;
    private AudioBuffer outBuf;
    private boolean readerEof;
    private boolean exhausted;
    private boolean closed;

    /**
     * Opens a WAV file for playback through the mixer.
     *
     * @param path              input file path
     * @param projectSampleRate project sample rate in Hz (&gt; 0)
     * @throws IllegalArgumentException if projectSampleRate ≤ 0
     * @throws AudioIOException         if the file cannot be opened or parsed
     */
    public FileSource(Path path, int projectSampleRate) {
        if (projectSampleRate <= 0) throw new IllegalArgumentException("projectSampleRate=" + projectSampleRate);
        this.reader = new WavReader(path);
        this.fileChannels = reader.getChannels();
        int nativeRate = reader.getSampleRate();
        this.resampler = (nativeRate == projectSampleRate) ? null : new Resampler(nativeRate, projectSampleRate, fileChannels);
    }

    /**
     * Returns the channel count of the underlying WAV file.
     *
     * @return file channel count (1–8)
     */
    public int getChannels() { return fileChannels; }

    /**
     * Returns the native sample rate of the WAV file.
     *
     * @return sample rate in Hz
     */
    public int getNativeSampleRate() { return reader.getSampleRate(); }

    @Override
    public int read(AudioBuffer b) {
        if (exhausted) return 0;
        if (resampler == null) return readDirect(b);
        return readResampled(b);
    }

    private int readDirect(AudioBuffer b) {
        try {
            if (fileChannels == b.channels()) {
                int n = reader.read(b);
                if (n == 0) exhausted = true;
                return n;
            }
            ensureInBuf(b.frames);
            int n = reader.read(inBuf);
            if (n == 0) { exhausted = true; return 0; }
            mapChannels(inBuf, n, b, 0);
            return n;
        } catch (AudioIOException e) {
            LOG.warning("file read failed, treating as EOF: " + e.getMessage());
            exhausted = true;
            return 0;
        }
    }

    private int readResampled(AudioBuffer b) {
        ensureInBuf(b.frames);
        ensureOutBuf(b.frames);
        int filled = 0;
        while (filled < b.frames && !exhausted) {
            int produced;
            if (readerEof) {
                produced = resampler.flush(outBuf.data, b.frames - filled);
                if (produced == 0) { exhausted = true; break; }
            } else {
                try {
                    int n = reader.read(inBuf);
                    if (n == 0) { readerEof = true; continue; }
                    produced = resampler.process(inBuf.data, n, outBuf.data, b.frames - filled);
                    if (produced == 0) continue;
                } catch (AudioIOException e) {
                    LOG.warning("file read failed, treating as EOF: " + e.getMessage());
                    readerEof = true;
                    continue;
                }
            }
            mapChannels(outBuf, produced, b, filled);
            filled += produced;
        }
        return filled;
    }

    /**
     * Copy frames from src to dst at the given offset, mapping channel
     * index-by-index (mono→duplicate, multi→direct, extras dropped).
     */
    private static void mapChannels(AudioBuffer src, int frames, AudioBuffer dst, int dstOffset) {
        int srcCh = src.channels();
        int dstCh = dst.channels();
        if (srcCh == 1) {
            for (int ch = 0; ch < dstCh; ch++) {
                System.arraycopy(src.data[0], 0, dst.data[ch], dstOffset, frames);
            }
        } else {
            int copyCh = Math.min(srcCh, dstCh);
            for (int ch = 0; ch < copyCh; ch++) {
                System.arraycopy(src.data[ch], 0, dst.data[ch], dstOffset, frames);
            }
        }
    }

    private void ensureInBuf(int frames) {
        if (inBuf == null || inBuf.frames < frames) {
            inBuf = AudioBuffer.create(fileChannels, frames);
        }
    }

    private void ensureOutBuf(int frames) {
        if (outBuf == null || outBuf.frames < frames) {
            outBuf = AudioBuffer.create(fileChannels, frames);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}
