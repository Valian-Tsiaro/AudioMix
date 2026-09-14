package audiomix.io;

import audiomix.core.AudioBuffer;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Path;

/**
 * Streaming AIFF reader. Parses standard FORM/AIFF files with
 * uncompressed PCM16 or PCM24 data. AIFC files are
 * rejected. Unknown chunks are skipped.
 *
 * <p>Not thread-safe; call {@link #read} from a single thread only.</p>
 */
public final class AiffReader implements AudioFileReader {
    private int channels;
    private int sampleRate;
    private WavFormat format;
    private long frameCount;
    private final InputStream in;
    private final int bytesPerSample;
    private final int blockAlign;
    private long framesRead;

    /**
     * Opens an AIFF file for reading.
     *
     * @param path input file path
     * @throws AudioIOException if the file is malformed, truncated, or uses an unsupported format
     */
    public AiffReader(Path path) {
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
        byte[] hdr = readFully(12);
        if (!matches(hdr, 0, "FORM") || !matches(hdr, 8, "AIFF")) {
            throw new AudioIOException("not an AIFF file");
        }
        int channels = 0, sampleRate = 0, sampleSize = 0;
        long frameCount = -1;
        boolean foundComm = false;
        while (true) {
            byte[] chunkHdr = readFully(8);
            int size = be32(chunkHdr, 4);
            if (matches(chunkHdr, 0, "COMM")) {
                if (size < 18) throw new AudioIOException("COMM chunk too small");
                byte[] comm = readFully(18);
                channels = be16(comm, 0);
                frameCount = be32(comm, 2) & 0xFFFFFFFFL;
                sampleSize = be16(comm, 6);
                sampleRate = decodeExtended(comm, 8);
                if (channels < 1 || channels > 8) throw new AudioIOException("unsupported channels: " + channels);
                if (sampleRate <= 0) throw new AudioIOException("unsupported sample rate: " + sampleRate);
                foundComm = true;
            } else if (matches(chunkHdr, 0, "SSND")) {
                if (!foundComm) throw new AudioIOException("SSND before COMM");
                if (size < 8) throw new AudioIOException("SSND chunk too small");
                byte[] ssndHdr = readFully(8);
                long offset = be32(ssndHdr, 0) & 0xFFFFFFFFL;
                long blockSize = be32(ssndHdr, 4) & 0xFFFFFFFFL;
                if (offset != 0 || blockSize != 0)
                    throw new AudioIOException("non-standard SSND offset/blockSize");
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
        if (!foundComm) throw new AudioIOException("no COMM chunk");
        WavFormat fmt;
        if (sampleSize == 16) fmt = WavFormat.PCM16;
        else if (sampleSize == 24) fmt = WavFormat.PCM24;
        else throw new AudioIOException("unsupported sample size: " + sampleSize);
        int blockAlign = channels * fmt.bytesPerSample();
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.format = fmt;
        this.frameCount = frameCount;
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

    private static int be16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int decodeExtended(byte[] b, int off) {
        long sign = ((b[off] & 0x80) != 0) ? -1L : 1L;
        int exponent = ((b[off] & 0x7F) << 8) | (b[off + 1] & 0xFF);
        BigInteger mantissa = BigInteger.ZERO;
        for (int i = 0; i < 8; i++) {
            mantissa = mantissa.shiftLeft(8).or(BigInteger.valueOf(b[off + 2 + i] & 0xFF));
        }
        mantissa = mantissa.or(BigInteger.ONE.shiftLeft(63));
        if (exponent == 0) return 0;
        int shift = 63 - (exponent - 16383);
        if (shift < 0) throw new AudioIOException("unsupported sample rate");
        return (int) (sign * mantissa.shiftRight(shift).longValue());
    }

    /** Returns the number of channels in the file (1–8). */
    public int getChannels() { return channels; }

    /** Returns the sample rate in Hz. */
    public int getSampleRate() { return sampleRate; }

    /** Returns the total number of audio frames. */
    public long getFrameCount() { return frameCount; }

    /** Returns the sample format of this file. */
    public WavFormat getFormat() { return format; }

    /**
     * Reads audio frames into the buffer. Returns frames filled; 0 at
     * EOF (stays 0 forever). Partial fills near EOF.
     *
     * @param b destination buffer; channel count must match the file
     * @return frames actually read (0 at EOF)
     * @throws IllegalArgumentException if buffer channel count differs
     * @throws AudioIOException         if the data chunk is shorter than claimed
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
                        int s = be16(block, idx);
                        if (s > 32767) s -= 65536;
                        b.set(ch, i, s / 32767f);
                        idx += 2;
                    }
                    case PCM24 -> {
                        int s = ((block[idx] & 0xFF) << 16) |
                                ((block[idx + 1] & 0xFF) << 8) |
                                (block[idx + 2] & 0xFF);
                        if ((s & 0x800000) != 0) s |= 0xFF000000;
                        b.set(ch, i, s / 8388607f);
                        idx += 3;
                    }
                    default -> throw new AudioIOException("unsupported format: " + format);
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
