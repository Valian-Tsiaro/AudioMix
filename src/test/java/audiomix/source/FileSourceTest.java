package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.io.AudioIOException;
import audiomix.io.WavFormat;
import audiomix.io.WavWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class FileSourceTest {
    @TempDir Path tmp;

    private Path writeSine(int ch, int sr, double freq, double level, int frames, WavFormat fmt) {
        AudioBuffer b = AudioBuffer.create(ch, frames);
        for (int i = 0; i < frames; i++) {
            double t = (double) i / sr;
            float v = (float) (level * Math.sin(2 * Math.PI * freq * t));
            for (int c = 0; c < ch; c++) b.set(c, i, v);
        }
        Path p = tmp.resolve("sine_" + sr + "Hz_" + frames + "f_" + ch + "ch.wav");
        try (var w = new WavWriter(p, ch, sr, fmt)) { w.write(b); }
        return p;
    }

    @Test
    void rejectMalformedFile() {
        Path p = tmp.resolve("bad.wav");
        try { java.nio.file.Files.write(p, "RIFF....WAVE".getBytes()); } catch (Exception e) { throw new RuntimeException(e); }
        assertThrows(AudioIOException.class, () -> new FileSource(p, 48000));
    }

    @Test
    void rejectNonexistentFile() {
        assertThrows(AudioIOException.class, () -> new FileSource(tmp.resolve("nope.wav"), 48000));
    }

    @Test
    void sameRatePassthrough() {
        int sr = 48000, frames = 1024, ch = 1;
        Path p = writeSine(ch, sr, 440.0, 0.25, frames, WavFormat.PCM16);
        try (FileSource src = new FileSource(p, sr)) {
            assertEquals(ch, src.getChannels());
            assertEquals(sr, src.getNativeSampleRate());
            AudioBuffer buf = AudioBuffer.create(ch, frames);
            int filled = src.read(buf);
            assertEquals(frames, filled);
            for (int i = 0; i < frames; i++) {
                double t = (double) i / sr;
                float expected = (float) (0.25 * Math.sin(2 * Math.PI * 440.0 * t));
                assertEquals(expected, buf.get(0, i), 1f / 32767f, "sample " + i);
            }
            assertEquals(0, src.read(buf));
            assertEquals(0, src.read(buf));
        }
    }

    @Test
    void stereoFileAccessor() {
        int sr = 48000, frames = 512;
        Path p = writeSine(2, sr, 440.0, 0.25, frames, WavFormat.PCM16);
        try (FileSource src = new FileSource(p, sr)) {
            assertEquals(2, src.getChannels());
            assertEquals(sr, src.getNativeSampleRate());
        }
    }

    @Test
    void resampleUpFrameCount() {
        int frames = 4410;
        Path p = writeSine(1, 44100, 440.0, 0.25, frames, WavFormat.PCM16);
        try (FileSource src = new FileSource(p, 48000)) {
            AudioBuffer buf = AudioBuffer.create(1, 512);
            int total = 0;
            while (true) {
                int filled = src.read(buf);
                if (filled == 0) break;
                total += filled;
            }
            int expected = (int) Math.round(4410.0 * 48000 / 44100);
            assertEquals(expected, total, 20, "resampled frame count");
        }
    }

    @Test
    void closeThenReadReturnsZero() {
        int sr = 48000, frames = 512;
        Path p = writeSine(1, sr, 440.0, 0.25, frames, WavFormat.PCM16);
        FileSource src = new FileSource(p, sr);
        AudioBuffer buf = AudioBuffer.create(1, 512);
        while (src.read(buf) > 0) {}
        src.close();
        assertEquals(0, src.read(buf));
    }

    @Test
    void truncatedFileExhaustsCleanly() {
        int sr = 48000, frames = 1024, ch = 1;
        Path p = writeSine(ch, sr, 440.0, 0.25, frames, WavFormat.PCM16);

        // Patch the data chunk size to claim 10x more data than exists.
        // Bytes 40-43 of the WAV header hold the data chunk size in LE.
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(40);
            long fakeSize = (long) frames * ch * 2 * 10;
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt((int) fakeSize).array());
        } catch (Exception e) { throw new RuntimeException(e); }

        FileSource src = new FileSource(p, sr);
        AudioBuffer buf = AudioBuffer.create(ch, 512);
        int totalRead = 0;
        while (true) {
            int filled = src.read(buf);
            if (filled == 0) break;
            totalRead += filled;
        }
        src.close();
        assertEquals(frames, totalRead,
                "truncated file: all real content served before exhaustion");
    }

    @Test
    void truncatedFileResampledExhaustsCleanly() {
        int sr = 48000, frames = 1024, ch = 1;
        Path p = writeSine(ch, sr, 440.0, 0.25, frames, WavFormat.PCM16);

        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(40);
            long fakeSize = (long) frames * ch * 2 * 10;
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt((int) fakeSize).array());
        } catch (Exception e) { throw new RuntimeException(e); }

        // Open at a mismatched rate → exercises readResampled catch path
        FileSource src = new FileSource(p, 44100);
        AudioBuffer buf = AudioBuffer.create(ch, 512);
        int totalRead = 0;
        while (true) {
            int filled = src.read(buf);
            if (filled == 0) break;
            totalRead += filled;
        }
        src.close();
        // Resampled output should be close to original, not wildly inflated
        int expectedResampled = (int) Math.round((double) frames * 44100 / sr);
        assertEquals(expectedResampled, totalRead, 30,
                "truncated file resampled: output close to expected length");
    }
}
