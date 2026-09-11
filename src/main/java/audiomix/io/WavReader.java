package audiomix.io;

import audiomix.core.AudioBuffer;
import java.io.*;
import java.nio.file.Path;

/**
 * Streaming WAV reader. Parses standard little-endian RIFF/WAVE files.
 * Unknown chunks are skipped; compressed or extended-format files
 * are rejected with {@link AudioIOException}.
 *
 * <p>Not thread-safe; call {@link #read} from a single thread only.</p>
 */
public final class WavReader implements AutoCloseable {
    private int channels;
    private int sampleRate;
    private WavFormat format;
    private long frameCount;
    private final InputStream in;
    private final int bytesPerSample;
    private final int blockAlign;
    private long framesRead;

    /**
     * Opens a WAV file for reading.
     *
     * @param path input file path
     * @throws AudioIOException if the file is malformed, truncated, or uses an unsupported format
     */
    public WavReader(Path path) {
        try {
            in = new BufferedInputStream(new FileInputStream(path.toFile()));
        } catch (FileNotFoundException e) {
            throw new AudioIOException("cannot read: " + path, e);
        }
        try {
            parseHeader();
            bytesPerSample = format.bytesPerSample();
            blockAlign = channels * bytesPerSample;
            framesRead = 0;
        } catch (AudioIOException e) {
            close();
            throw e;
        } catch (IOException e) {
            close();
            throw new AudioIOException("parse failed", e);
        }
    }

    private void parseHeader() throws IOException {
        byte[] riff = readFully(12);
        if (!matches(riff, 0, "RIFF") || !matches(riff, 8, "WAVE")) {
            throw new AudioIOException("not a RIFF/WAVE file");
        }
        int channels = 0, sampleRate = 0, tag = 0, bits = 0;
        long dataSize = -1;
        boolean foundFmt = false;
        while (true) {
            byte[] chunkHdr = readFully(8);
            int size = le32(chunkHdr, 4);
            if (matches(chunkHdr, 0, "fmt ")) {
                if (size < 16) throw new AudioIOException("fmt chunk too small");
                byte[] fmt = readFully(size);
                tag = le16(fmt, 0);
                channels = le16(fmt, 2);
                sampleRate = le32(fmt, 4);
                bits = le16(fmt, 14);
                if (channels < 1 || channels > 8) throw new AudioIOException("unsupported channels: " + channels);
                foundFmt = true;
            } else if (matches(chunkHdr, 0, "data")) {
                if (!foundFmt) throw new AudioIOException("data before fmt");
                dataSize = size & 0xFFFFFFFFL;
                break;
            } else {
                long toSkip = size;
                while (toSkip > 0) {
                    long skipped = in.skip(toSkip);
                    if (skipped <= 0) throw new AudioIOException("skip failed");
                    toSkip -= skipped;
                }
                if ((size & 1) == 1) in.read();
            }
        }
        if (!foundFmt) throw new AudioIOException("no fmt chunk");
        if (tag == 0xFFFE) throw new AudioIOException("unsupported format tag: " + tag);
        WavFormat fmt = WavFormat.fromTag(tag, bits);
        int blockAlign = channels * fmt.bytesPerSample();
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.format = fmt;
        this.frameCount = blockAlign > 0 ? dataSize / blockAlign : 0;
    }

    private byte[] readFully(int len) throws IOException {
        byte[] buf = new byte[len];
        readFully(buf);
        return buf;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new AudioIOException("unexpected EOF");
            off += r;
        }
    }

    private static boolean matches(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != s.charAt(i)) return false;
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) |
                ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** Returns the number of channels in the file (1–8). */
    public int getChannels() { return channels; }

    /** Returns the sample rate in Hz. */
    public int getSampleRate() { return sampleRate; }

    /** Returns the total number of audio frames in the data chunk. */
    public long getFrameCount() { return frameCount; }

    /** Returns the sample format of this file. */
    public WavFormat getFormat() { return format; }

    /**
     * Reads audio frames into the buffer. Returns frames filled; 0 if at
     * EOF (stays 0 forever). Partial fills are returned near EOF.
     *
     * @param b destination buffer; channel count must match the file
     * @return number of frames actually read (0 at EOF)
     * @throws IllegalArgumentException if buffer channel count differs
     * @throws AudioIOException         if the data chunk is shorter than its header claims
     */
    public int read(AudioBuffer b) {
        if (b.channels() != channels) throw new IllegalArgumentException("channel mismatch");
        if (framesRead >= frameCount) return 0;
        int toRead = (int) Math.min(b.frames, frameCount - framesRead);
        byte[] block = new byte[toRead * blockAlign];
        try {
            readFully(block);
        } catch (IOException e) {
            throw new AudioIOException("read failed", e);
        }
        int idx = 0;
        for (int i = 0; i < toRead; i++) {
            for (int ch = 0; ch < channels; ch++) {
                switch (format) {
                    case PCM16 -> {
                        int s = le16(block, idx);
                        if (s > 32767) s -= 65536;
                        b.set(ch, i, s / 32767f);
                        idx += 2;
                    }
                    case PCM24 -> {
                        int s = (block[idx] & 0xFF) | ((block[idx + 1] & 0xFF) << 8) | ((block[idx + 2] & 0xFF) << 16);
                        if ((s & 0x800000) != 0) s |= 0xFF000000;
                        b.set(ch, i, s / 8388607f);
                        idx += 3;
                    }
                    case FLOAT32 -> {
                        int bits = le32(block, idx);
                        b.set(ch, i, Float.intBitsToFloat(bits));
                        idx += 4;
                    }
                }
            }
        }
        framesRead += toRead;
        return toRead;
    }

    /** Closes the underlying input stream. */
    public void close() {
        try { in.close(); } catch (IOException ignored) {}
    }
}
