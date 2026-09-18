package audiomix.io;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Real audio output backed by a JavaSound SourceDataLine. Opens PCM_FLOAT
 * when the device supports it and falls back to PCM_SIGNED 16-bit
 * little-endian otherwise. All methods are called from the engine thread.
 */
public final class SourceDataLineSink implements AudioSink {

    interface SourceDataLineFactory {
        SourceDataLine open(Mixer mixer, AudioFormat fmt) throws LineUnavailableException;
    }

    private static final SourceDataLineFactory SYSTEM_FACTORY =
            (mixer, fmt) -> mixer == null
                    ? AudioSystem.getSourceDataLine(fmt)
                    : (SourceDataLine) mixer.getLine(new DataLine.Info(SourceDataLine.class, fmt));

    private final DeviceInfo device;
    private final SourceDataLineFactory factory;
    private SourceDataLine line;
    private boolean floatFormat;
    private int channels;

    /**
     * Creates a sink for the given device.
     *
     * @param device output device, or null for the default speakers
     */
    public SourceDataLineSink(DeviceInfo device) {
        this(device, SYSTEM_FACTORY);
    }

    SourceDataLineSink(DeviceInfo device, SourceDataLineFactory factory) {
        this.device = device;
        this.factory = factory;
    }

    @Override
    public void open(int channels, int sampleRate, int blockSize) {
        if (channels < 1 || sampleRate <= 0 || blockSize <= 0) {
            throw new IllegalArgumentException(
                    "channels=" + channels + " sampleRate=" + sampleRate + " blockSize=" + blockSize);
        }
        this.channels = channels;
        floatFormat = true;
        try {
            line = acquire(new AudioFormat(
                    AudioFormat.Encoding.PCM_FLOAT, sampleRate, 32, channels, channels * 4,
                    sampleRate, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN),
                    blockSize * 8);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            floatFormat = false;
            try {
                line = acquire(new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels, channels * 2,
                        sampleRate, false), blockSize * 8);
            } catch (LineUnavailableException | IllegalArgumentException e2) {
                throw new AudioIOException("no usable output format: " +
                        sampleRate + " Hz, " + channels + " ch", e2);
            }
        }
        line.start();
    }

    private SourceDataLine acquire(AudioFormat fmt, int bufferBytes)
            throws LineUnavailableException {
        SourceDataLine l = factory.open(
                device == null || device.getMixerInfo() == null
                        ? null : AudioSystem.getMixer(device.getMixerInfo()), fmt);
        l.open(fmt, bufferBytes);
        return l;
    }

    @Override
    public void write(float[][] data, int frames) {
        if (frames == 0) return;
        int bps = floatFormat ? 4 : 2;
        byte[] bytes = new byte[frames * channels * bps];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(
                floatFormat ? ByteOrder.nativeOrder() : ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                float s = data[ch][i];
                if (floatFormat) {
                    buf.putFloat(s);
                } else {
                    int v = Math.round(s * 32767f);
                    if (v > 32767) v = 32767;
                    if (v < -32767) v = -32767;
                    buf.putShort((short) v);
                }
            }
        }
        line.write(bytes, 0, bytes.length);
    }

    @Override
    public void close() {
        if (line != null) {
            line.drain();
            line.close();
            line = null;
        }
    }
}
