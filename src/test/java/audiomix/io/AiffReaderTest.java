package audiomix.io;

import audiomix.core.AudioBuffer;
import audiomix.source.FileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AiffReaderTest {
    @TempDir Path tmp;

    private Path writeAiff(int sr, double freq, double level, int frames) throws Exception {
        double t = 0;
        double dt = 1.0 / sr;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short sample = (short) (level * Short.MAX_VALUE * Math.sin(2 * Math.PI * freq * t));
            pcm[i * 2] = (byte) (sample >> 8);
            pcm[i * 2 + 1] = (byte) sample;
            t += dt;
        }
        AudioFormat af = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sr, 16, 1, 2, sr, true);
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), af, frames);
        Path p = tmp.resolve("test.aiff");
        AudioSystem.write(ais, AudioFileFormat.Type.AIFF, p.toFile());
        return p;
    }

    @Test
    void readMetadata() throws Exception {
        Path p = writeAiff(44100, 440.0, 0.25, 11025);
        try (AiffReader r = new AiffReader(p)) {
            assertEquals(1, r.getChannels());
            assertEquals(44100, r.getSampleRate());
            assertEquals(11025, r.getFrameCount());
            assertEquals(WavFormat.PCM16, r.getFormat());
        }
    }

    @Test
    void readSamples() throws Exception {
        int frames = 88200;
        Path p = writeAiff(44100, 440.0, 0.25, frames);
        try (AiffReader r = new AiffReader(p)) {
            AudioBuffer buf = AudioBuffer.create(1, frames);
            int filled = r.read(buf);
            assertEquals(frames, filled);
            float peak = 0;
            for (int i = 0; i < frames; i++) {
                float v = Math.abs(buf.get(0, i));
                if (v > peak) peak = v;
            }
            assertEquals(0.25f, peak, 0.05f, "peak amplitude");
            int crossings = 0;
            for (int i = 1; i < frames; i++) {
                if ((buf.get(0, i - 1) < 0) != (buf.get(0, i) < 0)) crossings++;
            }
            double measured = crossings * (44100.0 / frames) / 2.0;
            assertEquals(440.0, measured, 0.5, "frequency via zero crossings");
            assertEquals(0, r.read(buf));
        }
    }

    @Test
    void fileSourceSameRate() throws Exception {
        int frames = 88200;
        Path p = writeAiff(44100, 440.0, 0.25, frames);
        try (FileSource src = new FileSource(p, 44100)) {
            assertEquals(1, src.getChannels());
            assertEquals(44100, src.getNativeSampleRate());
            AudioBuffer buf = AudioBuffer.create(1, 512);
            int total = 0;
            float peak = 0;
            int crossings = 0;
            float prev = 0;
            while (true) {
                int filled = src.read(buf);
                if (filled == 0) break;
                for (int i = 0; i < filled; i++) {
                    float v = buf.get(0, i);
                    float a = Math.abs(v);
                    if (a > peak) peak = a;
                    if (i > 0 || total > 0) {
                        if ((prev < 0) != (v < 0)) crossings++;
                    }
                    prev = v;
                }
                total += filled;
            }
            assertEquals(frames, total, 2, "frame count same rate");
            assertEquals(0.25f, peak, 0.05f, "peak same rate");
            double measured = crossings * (44100.0 / total) / 2.0;
            assertEquals(440.0, measured, 0.5, "freq same rate");
        }
    }

    @Test
    void fileSourceResample() throws Exception {
        int frames = 11025;
        Path p = writeAiff(44100, 440.0, 0.25, frames);
        try (FileSource src = new FileSource(p, 48000)) {
            AudioBuffer buf = AudioBuffer.create(1, 512);
            int total = 0;
            while (true) {
                int filled = src.read(buf);
                if (filled == 0) break;
                total += filled;
            }
            int expected = (int) Math.round((double) frames * 48000 / 44100);
            assertEquals(expected, total, 30, "resampled frame count");
        }
    }

    @Test
    void garbageFormFile() {
        byte[] bad = {
            'F','O','R','M', 0,0,0,20, 'A','I','F','F',
            'C','O','M','M', 0,0,0,10, // COMM size = 10 (needs 18)
            0,0
        };
        Path p = tmp.resolve("bad.aiff");
        try { Files.write(p, bad); } catch (Exception e) { throw new RuntimeException(e); }
        assertThrows(AudioIOException.class, () -> new AiffReader(p));
    }

    @Test
    void wavRegression() throws Exception {
        int frames = 1024, sr = 48000;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) (0.25 * Short.MAX_VALUE * Math.sin(2 * Math.PI * 440.0 * i / sr));
            pcm[i * 2] = (byte) s;
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        AudioFormat af = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sr, 16, 1, 2, sr, false);
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), af, frames);
        Path p = tmp.resolve("test.wav");
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, p.toFile());

        try (FileSource src = new FileSource(p, sr)) {
            assertEquals(1, src.getChannels());
            AudioBuffer buf = AudioBuffer.create(1, 512);
            int total = 0;
            while (true) {
                int filled = src.read(buf);
                if (filled == 0) break;
                total += filled;
            }
            assertEquals(frames, total, 2, "WAV regression frame count");
        }
    }
}
