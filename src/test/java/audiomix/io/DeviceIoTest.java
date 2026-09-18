package audiomix.io;

import audiomix.core.AudioBuffer;
import audiomix.source.LineInSource;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceIoTest {

    abstract static class FakeSourceLine implements SourceDataLine {
        final List<byte[]> written = new ArrayList<>();
        AudioFormat format;
        boolean open;
        boolean floatOk = true;

        public void open(AudioFormat fmt, int buf) {
            if (fmt.getEncoding() == AudioFormat.Encoding.PCM_FLOAT && !floatOk) {
                throw new IllegalArgumentException("no float support");
            }
            format = fmt;
            open = true;
        }

        public void open(AudioFormat fmt) { open(fmt, 0); }

        public AudioFormat getFormat() { return format; }

        public boolean isOpen() { return open; }

        public boolean isRunning() { return false; }

        public void start() { }

        public void stop() { }

        public void drain() { }

        public void flush() { }

        public int getBufferSize() { return 4096; }

        public int available() { return Integer.MAX_VALUE; }

        public int getFramePosition() { return 0; }

        public long getLongFramePosition() { return 0; }

        public long getMicrosecondPosition() { return 0; }

        public float getLevel() { return -1.0f; }

        public boolean isActive() { return false; }

        public void addLineListener(LineListener l) { }

        public void removeLineListener(LineListener l) { }

        public Control[] getControls() { return new Control[0]; }

        public boolean isControlSupported(Control.Type t) { return false; }

        public Control getControl(Control.Type t) { throw new IllegalArgumentException(); }

        public Line.Info getLineInfo() { return new Line.Info(getClass()); }

        public void open() { open = true; }

        public void close() { open = false; }
    }

    static final class FloatAcceptingLine extends FakeSourceLine {
        @Override
        public int write(byte[] b, int off, int len) {
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            written.add(copy);
            return len;
        }
    }

    static final class FloatRefusingLine extends FakeSourceLine {
        FloatRefusingLine() { floatOk = false; }

        @Override
        public int write(byte[] b, int off, int len) {
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            written.add(copy);
            return len;
        }
    }

    static final class FakeTargetLine implements TargetDataLine {
        final ByteBuffer feed;
        AudioFormat format;
        boolean open;
        boolean closed;

        FakeTargetLine(AudioFormat fmt, byte[] bytes) {
            this.format = fmt;
            this.feed = ByteBuffer.wrap(bytes);
        }

        public void open(AudioFormat fmt, int buf) {
            format = fmt;
            open = true;
        }

        public void open(AudioFormat fmt) { open(fmt, 0); }

        public void open() { open = true; }

        public int read(byte[] b, int off, int len) {
            int n = Math.min(len, feed.remaining());
            feed.get(b, off, n);
            return n;
        }

        public void drain() { }

        public void flush() { }

        public void start() { }

        public void stop() { closed = true; }

        public boolean isRunning() { return !closed; }

        public void close() { closed = true; }

        public boolean isOpen() { return open && !closed; }

        public AudioFormat getFormat() { return format; }

        public int getBufferSize() { return 4096; }

        public int available() { return feed.remaining(); }

        public int getFramePosition() { return 0; }

        public long getLongFramePosition() { return 0; }

        public long getMicrosecondPosition() { return 0; }

        public boolean isActive() { return false; }

        public float getLevel() { return -1.0f; }

        public void addLineListener(LineListener l) { }

        public void removeLineListener(LineListener l) { }

        public Control[] getControls() { return new Control[0]; }

        public boolean isControlSupported(Control.Type t) { return false; }

        public Control getControl(Control.Type t) { throw new IllegalArgumentException(); }

        public Line.Info getLineInfo() { return new Line.Info(getClass()); }
    }

    private static float decodeFloat(byte[] data, int frame, int ch) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat((frame * 2 + ch) * 4);
    }

    private static short decode16(byte[] data, int frame, int ch) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort((frame * 2 + ch) * 2);
    }

    @Test
    void deviceManagerSmoke() {
        DeviceManager dm = new DeviceManager();
        List<DeviceInfo> outs = dm.listOutputs();
        assertNotNull(outs);
        assertNotNull(dm.listInputs());
        for (DeviceInfo d : outs) {
            assertNotNull(d.getName());
            assertNotNull(d.getDescription());
        }
        if (outs.isEmpty()) {
            assertNull(dm.defaultOutput());
        }
    }

    @Test
    void sinkWritesFloatBytes() {
        FloatAcceptingLine fake = new FloatAcceptingLine();
        SourceDataLineSink sink = new SourceDataLineSink(null, (m, f) -> fake);
        sink.open(2, 44100, 4);
        sink.write(new float[][]{{1.0f, 0.5f}, {-1.0f, 0.25f}}, 2);
        sink.close();
        assertEquals(1, fake.written.size());
        assertEquals(AudioFormat.Encoding.PCM_FLOAT, fake.format.getEncoding());
        assertEquals(1.0f, decodeFloat(fake.written.get(0), 0, 0), 1e-6f);
        assertEquals(0.5f, decodeFloat(fake.written.get(0), 1, 0), 1e-6f);
        assertEquals(-1.0f, decodeFloat(fake.written.get(0), 0, 1), 1e-6f);
        assertEquals(0.25f, decodeFloat(fake.written.get(0), 1, 1), 1e-6f);
    }

    @Test
    void sinkFallsBackToPcm16() {
        FloatRefusingLine fake = new FloatRefusingLine();
        SourceDataLineSink sink = new SourceDataLineSink(null, (m, f) -> fake);
        sink.open(2, 44100, 4);
        sink.write(new float[][]{{0.5f, 0.0f}, {0.0f, 0.5f}}, 2);
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, fake.format.getEncoding());
        assertEquals(16, fake.format.getSampleSizeInBits());
        assertEquals(16383.5, decode16(fake.written.get(0), 0, 0), 1.0);
        assertEquals(16383.5, decode16(fake.written.get(0), 1, 1), 1.0);
    }

    @Test
    void lineInFeedsPcm16Sine() {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);
        int frames = 8;
        byte[] bytes = new byte[frames * 4];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            bb.putShort((short) Math.round(Math.sin(i * 0.5) * 0.5 * 32767));
            bb.putShort((short) Math.round(Math.cos(i * 0.5) * 0.25 * 32767));
        }
        FakeTargetLine fake = new FakeTargetLine(fmt, bytes);
        LineInSource src = new LineInSource(null, 44100, 2,
                (d, rate, ch, pf) -> {
                    if (pf) throw new AudioIOException("refuse float");
                    fake.open(fmt);
                    return fake;
                });
        assertEquals(16, src.getNegotiatedFormat());

        AudioBuffer b = AudioBuffer.create(2, 4);
        assertEquals(4, src.read(b));
        assertEquals(Math.round(Math.sin(0 * 0.5) * 0.5 * 32767) / 32767f, b.get(0, 0), 1e-6f);
        assertEquals(Math.round(Math.cos(0 * 0.5) * 0.25 * 32767) / 32767f, b.get(1, 0), 1e-6f);
        assertEquals(Math.round(Math.sin(3 * 0.5) * 0.5 * 32767) / 32767f, b.get(0, 3), 1e-6f);

        AudioBuffer b2 = AudioBuffer.create(2, 4);
        assertEquals(4, src.read(b2));
        assertEquals(Math.round(Math.sin(4 * 0.5) * 0.5 * 32767) / 32767f, b2.get(0, 0), 1e-6f);

        src.close();
        assertTrue(fake.closed);
    }

    @Test
    void lineInRefusesExactRate() {
        assertThrows(AudioIOException.class, () -> new LineInSource(null, 44100, 2,
                (d, rate, ch, pf) -> {
                    throw new AudioIOException("OS refuses rate: " + rate);
                }));
    }

    @Test
    void bindDefaultWithoutDeviceThrows() {
        DeviceManager dm = new DeviceManager();
        if (dm.defaultOutput() != null) {
            return; // hardware present, headless assertion not applicable
        }
        audiomix.core.Mixer m = new audiomix.core.Mixer(44100);
        assertThrows(AudioIOException.class, m.getMaster()::bindToDefaultSpeakers);
    }

    @Test
    void bindToDeviceRegistersBinding() {
        audiomix.core.Mixer m = new audiomix.core.Mixer(44100);
        audiomix.core.AuxBus aux = m.addAuxBus("fx");
        DeviceInfo d = new DeviceInfo("Fake", "test", false);
        aux.bindToDevice(d);
        Map<audiomix.core.Bus, List<DeviceInfo>> bindings = m.getBindings();
        assertEquals(1, bindings.get(aux).size());
        assertSame(d, bindings.get(aux).get(0));
        assertTrue(bindings.get(m.getMaster()).isEmpty());
    }
}
