package audiomix.core;

import audiomix.io.WavFormat;
import audiomix.io.WavReader;
import audiomix.source.FileSource;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SendsAuxTest {

    @TempDir Path tmp;

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double TAU = 2.0 * Math.PI;

    private static SineSource sine(int freq, double level, long frames) {
        return new SineSource(SR, freq, level, 1, frames);
    }

    /** Analytic send: unity fader, send 0.4, aux gain 0 dB. */
    @Test
    void analyticSend() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        Channel ch = m.addChannel("a");
        ch.setSource(sine(440, 0.25, -1));
        ch.addSend(aux, 0.4);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 2L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.35 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    /** Aux gain -6.0206 dB halves the send term. */
    @Test
    void auxGainDb() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        Channel ch = m.addChannel("a");
        ch.setSource(sine(440, 0.25, -1));
        ch.addSend(aux, 0.4);
        aux.setGainDb(-6.0206);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 2L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.30 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    /** Aux effect that doubles all samples. */
    @Test
    void auxEffect() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        aux.addEffect(buf -> {
            for (int ch = 0; ch < buf.channels(); ch++)
                for (int i = 0; i < buf.frames; i++)
                    buf.data[ch][i] *= 2;
        });
        Channel ch = m.addChannel("a");
        ch.setSource(sine(440, 0.25, -1));
        ch.addSend(aux, 0.4);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 2L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.45 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    /** Pre-fader send bypasses fader; mute still kills it. */
    @Test
    void preFader() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        Channel ch = m.addChannel("a");
        ch.setSource(sine(440, 0.25, -1));
        ch.addSend(aux, 0.4);
        ch.setSendPreFader(aux, true);
        ch.setGainDb(-60);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 2L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.10 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 0.10 * 0.01,
                    "pre-fader send within 1%: frame=" + i);
        }
    }

    /** Muted channel: both main and send are exactly zero. */
    @Test
    void muteKillsSend() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        Channel ch = m.addChannel("a");
        ch.setSource(sine(440, 0.25, -1));
        ch.addSend(aux, 0.4);
        ch.setMuted(true);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        for (int i = 0; i < BS; i++) {
            assertEquals(0.0f, dest.data[0][i], 0.0f, "frame=" + i);
        }
        for (float[] row : aux.sum().data) {
            for (float v : row) assertEquals(0.0f, v);
        }
    }

    /** Two channels both sending 0.4: aux term = 0.4*(a+b). */
    @Test
    void twoChannelsSend() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        Channel ca = m.addChannel("a");
        ca.setSource(sine(440, 0.25, -1));
        ca.addSend(aux, 0.4);
        Channel cb = m.addChannel("b");
        cb.setSource(sine(440, 0.25, -1));
        cb.addSend(aux, 0.4);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 2L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.70 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    /** Aux tail: effect keeps aux non-idle; allIdle false until aux drains. */
    @Test
    void auxTail() {
        int frames = 4096;
        int tailBlocks = 5;
        Path wav = tmp.resolve("src.wav");
        writeSineWav(wav, SR, 440.0, 0.25, frames);

        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        aux.addEffect(new RenderToFileTest.TestTailEffect(tailBlocks));
        FileSource src = new FileSource(wav, SR);
        Channel ch = m.addChannel("src");
        ch.setSource(src);
        ch.addSend(aux, 0.4);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        int sourceBlocks = (frames + BS - 1) / BS;
        int totalBlocks = 0;
        boolean wasNotIdleAfterSource = false;
        try {
            while (!m.allIdle()) {
                m.processBlock(dest);
                totalBlocks++;
                if (totalBlocks > sourceBlocks && !wasNotIdleAfterSource) {
                    assertFalse(m.allIdle(),
                            "allIdle false while aux tail drains (block " + totalBlocks + ")");
                    wasNotIdleAfterSource = true;
                }
            }
        } finally {
            src.close();
        }
        assertTrue(wasNotIdleAfterSource, "aux tail actually extended past source");
        assertTrue(totalBlocks > sourceBlocks,
                "more blocks than source: " + totalBlocks + " > " + sourceBlocks);
    }

    /** Live send level change: no exception, new level reached within 2 blocks. */
    @Test
    void liveSendLevel() {
        Mixer m = new Mixer(SR, BS);
        AuxBus aux = m.addAuxBus("fx");
        Channel ch = m.addChannel("a");
        ch.setSource(sine(440, 0.25, -1));
        ch.addSend(aux, 0.0);

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 2; i++) m.processBlock(dest);
        ch.setSendLevel(aux, 0.4);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 4L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.35 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 1e-3,
                    "send level ramped: frame=" + i);
        }
    }

    /** Regression: no sends → output identical to Step 12 behavior. */
    @Test
    void regressionNoSends() {
        Mixer m = new Mixer(SR, BS);
        m.addAuxBus("empty");
        m.addChannel("a").setSource(sine(440, 0.25, -1));

        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < 3; i++) m.processBlock(dest);

        long n0 = 2L * BS;
        for (int i = 0; i < BS; i++) {
            double t = (n0 + i) / (double) SR;
            float expected = (float) (0.25 * Math.sin(TAU * 440 * t));
            assertEquals(expected, dest.data[0][i], 1e-5, "frame=" + i);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static void writeSineWav(Path p, int sr, double freq, double level, int frames) {
        AudioBuffer b = AudioBuffer.create(1, frames);
        for (int i = 0; i < frames; i++) {
            b.set(0, i, (float) (level * Math.sin(TAU * freq * i / sr)));
        }
        try (var w = new audiomix.io.WavWriter(p, 1, sr, WavFormat.FLOAT32)) {
            w.write(b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
