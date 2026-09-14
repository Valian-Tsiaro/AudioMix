package audiomix.core;

import audiomix.io.WavFormat;
import audiomix.io.WavReader;
import audiomix.io.WavWriter;
import audiomix.source.FileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RenderToFileTest {
    @TempDir Path tmp;

    private final List<FileSource> toClose = new ArrayList<>();

    private FileSource open(Path path, int projectRate) {
        FileSource fs = new FileSource(path, projectRate);
        toClose.add(fs);
        return fs;
    }

    private void closeAll() {
        for (FileSource fs : toClose) fs.close();
        toClose.clear();
    }

    private Path writeSineWav(String name, int sr, double freq, double level,
                             double durSec, WavFormat fmt, int channels) {
        int frames = (int) (sr * durSec);
        AudioBuffer b = AudioBuffer.create(channels, frames);
        for (int i = 0; i < frames; i++) {
            double t = (double) i / sr;
            float v = (float) (level * Math.sin(2 * Math.PI * freq * t));
            for (int c = 0; c < channels; c++) b.set(c, i, v);
        }
        Path p = tmp.resolve(name);
        try (var w = new WavWriter(p, channels, sr, fmt)) { w.write(b); }
        return p;
    }

    @Test
    void goldenLinearity() {
        int sr = 48000, frames = (int) (sr * 0.3); // 14400
        Path aWav = writeSineWav("a.wav", sr, 440.0, 0.25, 0.3, WavFormat.PCM16, 1);
        Path bWav = writeSineWav("b.wav", sr, 880.0, 0.25, 0.3, WavFormat.PCM16, 1);
        Path out = tmp.resolve("out.wav");

        try {
            Mixer mixer = new Mixer(sr);
            Channel ca = mixer.addChannel("a");
            Channel cb = mixer.addChannel("b");
            ca.setSource(open(aWav, sr));
            cb.setSource(open(bWav, sr));

            mixer.renderToFile(out.toString(), WavFormat.PCM16);
        } finally { closeAll(); }

        try (var r = new WavReader(out)) {
            assertEquals(14400, (int) r.getFrameCount(),
                    "golden linearity: frame count must equal source length");

            AudioBuffer outBuf = AudioBuffer.create(2, 14400);
            r.read(outBuf);

            AudioBuffer aBuf = AudioBuffer.create(1, 14400);
            AudioBuffer bBuf = AudioBuffer.create(1, 14400);
            try (var ra = new WavReader(aWav)) { ra.read(aBuf); }
            try (var rb = new WavReader(bWav)) { rb.read(bBuf); }

            for (int i = 0; i < 14400; i++) {
                int aInt = Math.round(aBuf.get(0, i) * 32767);
                int bInt = Math.round(bBuf.get(0, i) * 32767);
                int expected = aInt + bInt;
                int actual = Math.round(outBuf.get(0, i) * 32767);
                int diff = Math.abs(actual - expected);
                assertTrue(diff <= 2,
                        "frame " + i + ": expected " + expected + " got " + actual
                                + " (diff=" + diff + "). 2 LSB bound: both inputs are quantized"
                                + " PCM16 files and the output is requantized, so 1 LSB would"
                                + " be dishonest — quantization error compounds through the mix"
                                + " and the final round-trip.");
            }
        }
    }

    @Test
    void resampleUp44k1To48k() {
        double dur = 0.3;
        int inSr = 44100;
        Path wav = writeSineWav("in44k.wav", inSr, 440.0, 0.25, dur, WavFormat.PCM16, 1);
        Path out = tmp.resolve("out48k.wav");

        try {
            Mixer mixer = new Mixer(48000);
            Channel ch = mixer.addChannel("src");
            ch.setSource(open(wav, 48000));
            mixer.renderToFile(out.toString(), WavFormat.PCM16);
        } finally { closeAll(); }

        try (var r = new WavReader(out)) {
            int outFrames = (int) r.getFrameCount();
            double outDurSec = (double) outFrames / 48000;
            assertEquals(dur, outDurSec, 0.003, "resample up: output duration");

            AudioBuffer buf = AudioBuffer.create(2, outFrames);
            r.read(buf);
            float peak = 0;
            boolean anyNaN = false;
            for (int i = 0; i < outFrames; i++) {
                float s = buf.get(0, i);
                if (Float.isNaN(s)) anyNaN = true;
                peak = Math.max(peak, Math.abs(s));
            }
            assertFalse(anyNaN, "resample up: no NaN");
            assertEquals(0.25f, peak, 0.25f * 0.10f, "resample up: peak");
        }
    }

    @Test
    void resampleDown48kTo44k1() {
        double dur = 0.3;
        int inSr = 48000;
        Path wav = writeSineWav("in48k.wav", inSr, 440.0, 0.25, dur, WavFormat.PCM16, 1);
        Path out = tmp.resolve("out44k.wav");

        try {
            Mixer mixer = new Mixer(44100);
            Channel ch = mixer.addChannel("src");
            ch.setSource(open(wav, 44100));
            mixer.renderToFile(out.toString(), WavFormat.PCM16);
        } finally { closeAll(); }

        try (var r = new WavReader(out)) {
            int outFrames = (int) r.getFrameCount();
            double outDurSec = (double) outFrames / 44100;
            assertEquals(dur, outDurSec, 0.003, "resample down: output duration");

            AudioBuffer buf = AudioBuffer.create(2, outFrames);
            r.read(buf);
            float peak = 0;
            boolean anyNaN = false;
            for (int i = 0; i < outFrames; i++) {
                float s = buf.get(0, i);
                if (Float.isNaN(s)) anyNaN = true;
                peak = Math.max(peak, Math.abs(s));
            }
            assertFalse(anyNaN, "resample down: no NaN");
        }
    }

    @Test
    void monoFileStereoChannelLrEqual() {
        int sr = 48000, frames = 2048;
        Path wav = writeSineWav("mono.wav", sr, 440.0, 0.5, (double) frames / sr, WavFormat.PCM16, 1);
        Path out = tmp.resolve("stereo_out.wav");

        try {
            Mixer mixer = new Mixer(sr);
            Channel ch = mixer.addChannel("mono", true);
            ch.setSource(open(wav, sr));
            mixer.renderToFile(out.toString(), WavFormat.FLOAT32);
        } finally { closeAll(); }

        try (var r = new WavReader(out)) {
            assertEquals(2, r.getChannels());
            AudioBuffer buf = AudioBuffer.create(2, (int) r.getFrameCount());
            r.read(buf);
            for (int i = 0; i < buf.frames; i++) {
                assertEquals(buf.get(0, i), buf.get(1, i), 1e-6f,
                        "mono file into stereo channel: L == R at frame " + i);
            }
        }
    }

    @Test
    void tailEffectLongerOutput() {
        int sr = 48000, frames = 4096;
        int tailBlocks = 5;
        Path wav = writeSineWav("src.wav", sr, 440.0, 0.25, (double) frames / sr, WavFormat.PCM16, 1);
        Path out = tmp.resolve("tail_out.wav");

        try {
            Mixer mixer = new Mixer(sr);
            Channel ch = mixer.addChannel("src");
            ch.setSource(open(wav, sr));
            ch.addEffect(new TestTailEffect(tailBlocks));
            mixer.renderToFile(out.toString(), WavFormat.FLOAT32);
        } finally { closeAll(); }

        try (var r = new WavReader(out)) {
            int outFrames = (int) r.getFrameCount();
            assertTrue(outFrames > frames,
                    "tail effect: output (" + outFrames + ") must exceed source (" + frames + ")");
        }
    }

    /** Simple pass-through effect that stays non-idle for K zero-input blocks. */
    static class TestTailEffect implements Effect {
        private int remaining;

        TestTailEffect(int extraBlocks) { remaining = extraBlocks; }

        @Override
        public void process(AudioBuffer b) {
            boolean allZero = true;
            outer:
            for (int ch = 0; ch < b.channels(); ch++) {
                for (int i = 0; i < b.frames; i++) {
                    if (b.data[ch][i] != 0.0f) { allZero = false; break outer; }
                }
            }
            if (allZero) remaining--;
        }

        @Override
        public boolean isIdle() { return remaining <= 0; }
    }
}
