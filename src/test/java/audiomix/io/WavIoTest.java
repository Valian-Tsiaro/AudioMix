package audiomix.io;

import audiomix.core.AudioBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WavIoTest {
    @TempDir Path tmp;

    private AudioBuffer sine(int ch, int sr, double freq, double amp) {
        int frames = (int) (sr * 0.3);
        AudioBuffer b = AudioBuffer.create(ch, frames);
        for (int i = 0; i < frames; i++) {
            double t = (double) i / sr;
            float v = (float) (amp * Math.sin(2 * Math.PI * freq * t));
            for (int c = 0; c < ch; c++) b.set(c, i, v);
        }
        return b;
    }

    @Test
    void roundtripPcm16Mono() {
        AudioBuffer src = sine(1, 48000, 440, 0.25);
        Path p = tmp.resolve("pcm16m.wav");
        try (var w = new WavWriter(p, 1, 48000, WavFormat.PCM16)) { w.write(src); }
        try (var r = new WavReader(p)) {
            assertEquals(1, r.getChannels());
            assertEquals(48000, r.getSampleRate());
            assertEquals(src.frames, (int) r.getFrameCount());
            assertEquals(WavFormat.PCM16, r.getFormat());
            AudioBuffer dst = AudioBuffer.create(1, src.frames);
            int filled = r.read(dst);
            assertEquals(src.frames, filled);
            for (int i = 0; i < src.frames; i++) {
                float diff = Math.abs(src.get(0, i) - dst.get(0, i));
                assertTrue(diff <= 1f / 32767f, "PCM16 error at " + i + ": " + diff);
            }
        }
    }

    @Test
    void roundtripPcm16Stereo() {
        AudioBuffer src = sine(2, 48000, 440, 0.25);
        Path p = tmp.resolve("pcm16s.wav");
        try (var w = new WavWriter(p, 2, 48000, WavFormat.PCM16)) { w.write(src); }
        try (var r = new WavReader(p)) {
            assertEquals(2, r.getChannels());
            assertEquals(48000, r.getSampleRate());
            assertEquals(src.frames, (int) r.getFrameCount());
            assertEquals(WavFormat.PCM16, r.getFormat());
            AudioBuffer dst = AudioBuffer.create(2, src.frames);
            r.read(dst);
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < src.frames; i++) {
                    float diff = Math.abs(src.get(ch, i) - dst.get(ch, i));
                    assertTrue(diff <= 1f / 32767f, "ch" + ch + " PCM16 error at " + i);
                }
            }
        }
    }

    @Test
    void roundtripPcm24Mono() {
        AudioBuffer src = sine(1, 48000, 440, 0.25);
        Path p = tmp.resolve("pcm24m.wav");
        try (var w = new WavWriter(p, 1, 48000, WavFormat.PCM24)) { w.write(src); }
        try (var r = new WavReader(p)) {
            assertEquals(1, r.getChannels());
            assertEquals(WavFormat.PCM24, r.getFormat());
            AudioBuffer dst = AudioBuffer.create(1, src.frames);
            r.read(dst);
            for (int i = 0; i < src.frames; i++) {
                float diff = Math.abs(src.get(0, i) - dst.get(0, i));
                assertTrue(diff <= 2f / 8388607f, "PCM24 error at " + i);
            }
        }
    }

    @Test
    void roundtripPcm24Stereo() {
        AudioBuffer src = sine(2, 48000, 440, 0.25);
        Path p = tmp.resolve("pcm24s.wav");
        try (var w = new WavWriter(p, 2, 48000, WavFormat.PCM24)) { w.write(src); }
        try (var r = new WavReader(p)) {
            assertEquals(2, r.getChannels());
            assertEquals(WavFormat.PCM24, r.getFormat());
            AudioBuffer dst = AudioBuffer.create(2, src.frames);
            r.read(dst);
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < src.frames; i++) {
                    float diff = Math.abs(src.get(ch, i) - dst.get(ch, i));
                    assertTrue(diff <= 2f / 8388607f, "ch" + ch + " PCM24 error at " + i);
                }
            }
        }
    }

    @Test
    void roundtripFloat32Mono() {
        AudioBuffer src = sine(1, 48000, 440, 0.25);
        Path p = tmp.resolve("f32m.wav");
        try (var w = new WavWriter(p, 1, 48000, WavFormat.FLOAT32)) { w.write(src); }
        try (var r = new WavReader(p)) {
            assertEquals(1, r.getChannels());
            assertEquals(WavFormat.FLOAT32, r.getFormat());
            AudioBuffer dst = AudioBuffer.create(1, src.frames);
            r.read(dst);
            for (int i = 0; i < src.frames; i++) {
                assertEquals(src.get(0, i), dst.get(0, i), 0f, "FLOAT32 error at " + i);
            }
        }
    }

    @Test
    void roundtripFloat32Stereo() {
        AudioBuffer src = sine(2, 48000, 440, 0.25);
        Path p = tmp.resolve("f32s.wav");
        try (var w = new WavWriter(p, 2, 48000, WavFormat.FLOAT32)) { w.write(src); }
        try (var r = new WavReader(p)) {
            assertEquals(2, r.getChannels());
            assertEquals(WavFormat.FLOAT32, r.getFormat());
            AudioBuffer dst = AudioBuffer.create(2, src.frames);
            r.read(dst);
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < src.frames; i++) {
                    assertEquals(src.get(ch, i), dst.get(ch, i), 0f, "ch" + ch + " FLOAT32 error at " + i);
                }
            }
        }
    }

    @Test
    void pcm24KnownValue() {
        AudioBuffer src = AudioBuffer.create(1, 48000);
        for (int i = 0; i < 48000; i++) src.set(0, i, 0.5f);
        Path p = tmp.resolve("pcm24_known.wav");
        try (var w = new WavWriter(p, 1, 48000, WavFormat.PCM24)) { w.write(src); }
        try (var r = new WavReader(p)) {
            AudioBuffer dst = AudioBuffer.create(1, 48000);
            r.read(dst);
            for (int i = 0; i < 48000; i++) {
                assertEquals(0.5f, dst.get(0, i), 2f / 8388607f, "PCM24 known value at " + i);
            }
        }
    }

    @Test
    void truncatedFile() {
        Path p = tmp.resolve("bad.wav");
        try {
            var fos = new java.io.FileOutputStream(p.toFile());
            fos.write(new byte[10]);
            fos.close();
        } catch (Exception e) { throw new RuntimeException(e); }
        assertThrows(AudioIOException.class, () -> new WavReader(p));
    }

    @Test
    void float32PreservesAboveOne() {
        AudioBuffer src = AudioBuffer.create(1, 1);
        src.set(0, 0, 1.25f);
        Path p = tmp.resolve("f32_over.wav");
        try (var w = new WavWriter(p, 1, 48000, WavFormat.FLOAT32)) { w.write(src); }
        try (var r = new WavReader(p)) {
            AudioBuffer dst = AudioBuffer.create(1, 1);
            r.read(dst);
            assertEquals(1.25f, dst.get(0, 0), 0f);
        }
    }

    @Test
    void pcm16Clamps() {
        AudioBuffer src = AudioBuffer.create(1, 1);
        src.set(0, 0, 1.5f);
        Path p = tmp.resolve("pcm16_clamp.wav");
        try (var w = new WavWriter(p, 1, 48000, WavFormat.PCM16)) { w.write(src); }
        try (var r = new WavReader(p)) {
            AudioBuffer dst = AudioBuffer.create(1, 1);
            r.read(dst);
            assertEquals(1f, dst.get(0, 0), 1f / 32767f);
        }
    }
}
