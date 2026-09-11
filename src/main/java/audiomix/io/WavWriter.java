package audiomix.io;

import audiomix.core.AudioBuffer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Streaming WAV writer. Writes standard little-endian RIFF/WAVE files
 * with a 44-byte header patched on {@link #close()}.
 *
 * <p>PCM16/PCM24 clamp input samples to ±1.0 before quantization.
 * FLOAT32 writes raw IEEE float values (no clamping).</p>
 *
 * <p>Not thread-safe; call {@link #write} from a single thread only.</p>
 */
public final class WavWriter implements AutoCloseable {
    private final int channels;
    private final int sampleRate;
    private final WavFormat fmt;
    private final OutputStream out;
    private final Path path;
    private long dataBytesWritten;

    /**
     * Opens a WAV file for streaming output.
     *
     * @param path       output file path
     * @param channels   number of channels (≥ 1)
     * @param sampleRate sample rate in Hz (> 0)
     * @param fmt        sample format
     * @throws IllegalArgumentException if channels &lt; 1 or sampleRate ≤ 0
     * @throws AudioIOException         if the file cannot be opened or the header write fails
     */
    public WavWriter(Path path, int channels, int sampleRate, WavFormat fmt) {
        if (channels < 1) throw new IllegalArgumentException("channels=" + channels);
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.fmt = fmt;
        this.path = path;
        this.dataBytesWritten = 0;
        try {
            out = new BufferedOutputStream(new FileOutputStream(path.toFile()));
        } catch (FileNotFoundException e) {
            throw new AudioIOException("cannot write: " + path, e);
        }
        try {
            writeHeader();
        } catch (IOException e) {
            try { out.close(); } catch (IOException ignored) {}
            throw new AudioIOException("write header failed", e);
        }
    }

    private void writeHeader() throws IOException {
        int blockAlign = channels * fmt.bytesPerSample();
        int byteRate = sampleRate * blockAlign;
        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes());
        hdr.putInt(0);
        hdr.put("WAVE".getBytes());
        hdr.put("fmt ".getBytes());
        hdr.putInt(16);
        hdr.putShort((short) fmt.formatTag());
        hdr.putShort((short) channels);
        hdr.putInt(sampleRate);
        hdr.putInt(byteRate);
        hdr.putShort((short) blockAlign);
        hdr.putShort((short) fmt.bitsPerSample());
        hdr.put("data".getBytes());
        hdr.putInt(0);
        out.write(hdr.array());
    }

    /**
     * Writes one block of interleaved audio. The buffer's channel count
     * must match the constructor's channel count.
     *
     * @param b audio block to write; must have same channel count as this writer
     * @throws IllegalArgumentException if buffer channel count differs
     * @throws AudioIOException         on I/O error
     */
    public void write(AudioBuffer b) {
        if (b.channels() != channels) throw new IllegalArgumentException("channel mismatch");
        int frames = b.frames;
        int bps = fmt.bytesPerSample();
        ByteBuffer buf = ByteBuffer.allocate(channels * frames * bps).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                float v = b.get(ch, i);
                switch (fmt) {
                    case PCM16 -> {
                        v = Math.max(-1f, Math.min(1f, v));
                        buf.putShort((short) Math.round(v * 32767));
                    }
                    case PCM24 -> {
                        v = Math.max(-1f, Math.min(1f, v));
                        int s = Math.round(v * 8388607);
                        buf.put((byte) (s & 0xFF));
                        buf.put((byte) ((s >> 8) & 0xFF));
                        buf.put((byte) ((s >> 16) & 0xFF));
                    }
                    case FLOAT32 -> buf.putFloat(v);
                }
            }
        }
        try {
            buf.flip();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            out.write(bytes);
            dataBytesWritten += bytes.length;
        } catch (IOException e) {
            throw new AudioIOException("write failed", e);
        }
    }

    /**
     * Closes the output stream and patches the RIFF and data chunk sizes
     * in the file header. Idempotent in effect (re-patching yields same result).
     *
     * @throws AudioIOException if the header patch fails
     */
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(4);
            raf.write(intLE((int) (36 + dataBytesWritten)));
            raf.seek(40);
            raf.write(intLE((int) dataBytesWritten));
        } catch (IOException e) {
            throw new AudioIOException("patch sizes failed", e);
        }
    }

    private static byte[] intLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
