package audiomix.source;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import audiomix.core.AudioBuffer;
import audiomix.core.Source;
import audiomix.io.AudioIOException;
import audiomix.io.DeviceInfo;

/**
 * Live line-in source backing a JavaSound TargetDataLine. Opens at
 * exactly the project sample rate and channel count; if the OS refuses
 * that exact format the constructor throws {@link AudioIOException}.
 * PCM_FLOAT is preferred when available, PCM_SIGNED 16-bit otherwise.
 * {@link #read} never returns 0 — the capture stream is infinite.
 */
public final class LineInSource implements Source {

    private final TargetDataLine line;
    private final int channels;
    private final int bits;
    private final int frameBytes;
    private final byte[] bytes;

    /**
     * Opens the given capture device (or default microphone) at the exact
     * project format. Callable from any thread; performs device I/O.
     *
     * @param device     input device, or null for the system default input
     * @param sampleRate project rate in Hz (&gt; 0)
     * @param channels   channel count (≥ 1)
     * @throws IllegalArgumentException if arguments are out of range
     * @throws AudioIOException if no device accepts the exact format
     */
    public LineInSource(DeviceInfo device, int sampleRate, int channels) {
        this(device, sampleRate, channels, LineInSource::openDefault);
    }

    /**
     * Opens line-in with an injectable factory (test seam).
     *
     * @param device     input device, or null for the system default input
     * @param sampleRate project rate in Hz (&gt; 0)
     * @param channels   channel count (≥ 1)
     * @param factory    factory that opens the TargetDataLine
     * @throws IllegalArgumentException if arguments are out of range
     * @throws AudioIOException if no factory result matches the exact format
     */
    public LineInSource(DeviceInfo device, int sampleRate, int channels, TargetDataLineFactory factory) {
        if (sampleRate <= 0 || channels < 1) {
            throw new IllegalArgumentException("sampleRate=" + sampleRate + " channels=" + channels);
        }
        this.channels = channels;
        TargetDataLine open = null;
        int negotiatedBits = 0;
        AudioIOException last = null;
        for (boolean preferFloat : new boolean[]{true, false}) {
            try {
                TargetDataLine l = factory.open(device, sampleRate, channels, preferFloat);
                if (l == null || !l.getFormat().getEncoding().equals(
                        preferFloat ? AudioFormat.Encoding.PCM_FLOAT : AudioFormat.Encoding.PCM_SIGNED)
                        || l.getFormat().getSampleRate() != sampleRate
                        || l.getFormat().getChannels() != channels) {
                    last = new AudioIOException("line-in refused format: "
                            + sampleRate + " Hz, " + channels + " ch, float=" + preferFloat);
                    continue;
                }
                open = l;
                open.start();
                negotiatedBits = preferFloat ? 32 : 16;
                break;
            } catch (AudioIOException e) {
                last = e;
            }
        }
        if (open == null) {
            throw last;
        }
        this.line = open;
        this.bits = negotiatedBits;
        this.frameBytes = channels * bits / 8;
        this.bytes = new byte[4096 * frameBytes];
    }

    /**
     * Bits per sample the device accepted: 16 (PCM) or 32 (float).
     *
     * @return negotiated bits per sample
     */
    public int getNegotiatedFormat() {
        return bits;
    }

    /**
     * Fills {@code b} completely, blocking TargetDataLine.read → floats.
     * Never returns 0; live line-in is an infinite stream.
     *
     * @param b buffer to fill
     * @return frames actually filled ({@code b.frames}, capped by the internal
     *         chunk size; a subsequent call continues the stream)
     * @throws AudioIOException if the line failed
     */
    @Override
    public int read(AudioBuffer b) {
        int fill = Math.min(b.frames, bytes.length / frameBytes);
        int got = 0;
        while (got < frameBytes) {
            if (!line.isOpen()) {
                throw new AudioIOException("line-in closed");
            }
            got = line.read(bytes, 0, fill * frameBytes);
            if (got == -1) {
                throw new AudioIOException("line-in read failed: stream ended");
            }
        }
        int frames = got / frameBytes;
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            for (int ch = 0; ch < channels && ch < b.channels(); ch++) {
                b.data[ch][i] = bits == 32 ? buf.getFloat() : buf.getShort() / 32767f;
            }
            for (int ch = channels; ch < b.channels(); ch++) {
                b.data[ch][i] = 0.0f;
            }
            if (channels > b.channels()) {
                buf.position(buf.position() + (channels - b.channels()) * (bits / 8));
            }
        }
        return frames;
    }

    @Override
    public void close() {
        line.stop();
        line.close();
    }

    private static TargetDataLine openDefault(DeviceInfo d, int rate, int ch, boolean preferFloat) {
        AudioFormat fmt = new AudioFormat(
                preferFloat ? AudioFormat.Encoding.PCM_FLOAT : AudioFormat.Encoding.PCM_SIGNED,
                rate, preferFloat ? 32 : 16, ch, ch * (preferFloat ? 4 : 2), rate,
                preferFloat && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
        try {
            Mixer mixer = d == null || d.getMixerInfo() == null
                    ? null : AudioSystem.getMixer(d.getMixerInfo());
            TargetDataLine l = mixer == null
                    ? AudioSystem.getTargetDataLine(fmt)
                    : (TargetDataLine) mixer.getLine(new DataLine.Info(TargetDataLine.class, fmt));
            l.open(fmt);
            return l;
        } catch (Exception e) {
            throw new AudioIOException("line-in open failed: " + rate + " Hz, " + ch + " ch, float=" + preferFloat, e);
        }
    }
}
