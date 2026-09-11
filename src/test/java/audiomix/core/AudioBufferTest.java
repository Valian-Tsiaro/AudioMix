package audiomix.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioBufferTest {

    @Test
    void createReportsDimensions() {
        AudioBuffer buf = AudioBuffer.create(2, 512);
        assertEquals(2, buf.channels());
        assertEquals(512, buf.frames);
        assertEquals(2, buf.data.length);
        for (int ch = 0; ch < buf.channels(); ch++) {
            assertEquals(512, buf.data[ch].length);
        }
    }

    @Test
    void createWithOneChannel() {
        AudioBuffer buf = AudioBuffer.create(1, 256);
        assertEquals(1, buf.channels());
        assertEquals(256, buf.frames);
    }

    @Test
    void clearZerosAllSamples() {
        AudioBuffer buf = AudioBuffer.create(2, 128);
        buf.set(0, 5, 0.5f);
        buf.set(1, 10, -0.3f);
        buf.clear();
        for (int ch = 0; ch < buf.channels(); ch++) {
            for (int i = 0; i < buf.frames; i++) {
                assertEquals(0.0f, buf.get(ch, i));
            }
        }
    }

    @Test
    void setAndGet() {
        AudioBuffer buf = AudioBuffer.create(1, 64);
        buf.set(0, 32, 0.75f);
        assertEquals(0.75f, buf.get(0, 32));
    }

    @Test
    void copyFromCopiesValues() {
        AudioBuffer src = AudioBuffer.create(2, 64);
        src.set(0, 0, 1.0f);
        src.set(1, 63, -0.5f);
        AudioBuffer dst = AudioBuffer.create(2, 64);
        dst.copyFrom(src);
        assertEquals(1.0f, dst.get(0, 0));
        assertEquals(-0.5f, dst.get(1, 63));
    }

    @Test
    void copyFromSelfIsNoop() {
        AudioBuffer buf = AudioBuffer.create(2, 64);
        buf.set(0, 0, 1.0f);
        buf.set(1, 63, -0.5f);
        buf.copyFrom(buf);
        assertEquals(1.0f, buf.get(0, 0));
        assertEquals(-0.5f, buf.get(1, 63));
    }

    @Test
    void copyFromIsDeep() {
        AudioBuffer src = AudioBuffer.create(2, 64);
        src.set(0, 0, 1.0f);
        AudioBuffer dst = AudioBuffer.create(2, 64);
        dst.copyFrom(src);
        dst.set(0, 0, 0.0f);
        assertEquals(1.0f, src.get(0, 0), "copyFrom must not share row references");
    }

    @Test
    void copyFromMismatchedChannelsThrows() {
        AudioBuffer src = AudioBuffer.create(1, 64);
        AudioBuffer dst = AudioBuffer.create(2, 64);
        assertThrows(IllegalArgumentException.class, () -> dst.copyFrom(src));
    }

    @Test
    void copyFromMismatchedFramesThrows() {
        AudioBuffer src = AudioBuffer.create(2, 128);
        AudioBuffer dst = AudioBuffer.create(2, 64);
        assertThrows(IllegalArgumentException.class, () -> dst.copyFrom(src));
    }

    @Test
    void createZeroChannelsThrows() {
        assertThrows(IllegalArgumentException.class, () -> AudioBuffer.create(0, 512));
    }

    @Test
    void createNegativeChannelsThrows() {
        assertThrows(IllegalArgumentException.class, () -> AudioBuffer.create(-1, 512));
    }

    @Test
    void createZeroFramesThrows() {
        assertThrows(IllegalArgumentException.class, () -> AudioBuffer.create(2, 0));
    }

    @Test
    void createNegativeFramesThrows() {
        assertThrows(IllegalArgumentException.class, () -> AudioBuffer.create(2, -1));
    }
}
