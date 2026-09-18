package audiomix.core;

import audiomix.dsp.EqType;
import audiomix.dsp.effects.Compressor;
import audiomix.dsp.effects.Freeverb;
import audiomix.dsp.effects.Limiter;
import audiomix.dsp.effects.Meter;
import audiomix.dsp.effects.ParametricEq;
import audiomix.io.WavFormat;
import audiomix.io.WavReader;
import audiomix.io.WavWriter;
import audiomix.source.FileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class AcceptanceExampleTest {

    @TempDir Path tmp;

    @Test
    void fullGraphEndToEnd() {
        int sr = 48000, frames = sr;
        AudioBuffer sineBuf = AudioBuffer.create(1, frames);
        for (int i = 0; i < frames; i++) {
            double t = (double) i / sr;
            sineBuf.set(0, i, (float) (0.25 * Math.sin(2 * Math.PI * 440 * t)));
        }
        Path sinePath = tmp.resolve("sine.wav");
        try (var w = new WavWriter(sinePath, 1, sr, WavFormat.PCM16)) { w.write(sineBuf); }

        Mixer m = new Mixer(sr);
        Channel voc = m.addChannel("vocals");
        Path outPath = tmp.resolve("out.wav");
        Meter masterMeter = new Meter(2);
        try (FileSource fs = new FileSource(sinePath, sr)) {
            voc.setSource(fs);
            voc.addEffect(new ParametricEq(sr).band(EqType.PEAK, 1000, 3.0, 1.0));
            voc.addEffect(new Compressor(sr).threshold(-18).ratio(4));
            voc.setGainDb(-3);
            m.addAuxBus("reverb");
            voc.addSend(m.getAux("reverb"), 0.4);
            m.getAux("reverb").addEffect(new Freeverb(sr));
            m.getMaster().addEffect(new Limiter(sr));
            m.getMaster().addEffect(masterMeter);
            m.renderToFile(outPath.toString(), WavFormat.PCM16);
        }

        try (WavReader reader = new WavReader(outPath)) {
            int totalFrames = (int) reader.getFrameCount();
            int outCh = reader.getChannels();
            assertTrue(totalFrames >= frames, "output shorter than source: " + totalFrames);
            AudioBuffer out = AudioBuffer.create(outCh, totalFrames);
            int filled = reader.read(out);
            assertTrue(filled >= frames, "read " + filled + " frames");

            double peak = 0;
            double sumSq = 0;
            for (int c = 0; c < outCh; c++) {
                for (int f = 0; f < filled; f++) {
                    double s = out.get(c, f);
                    assertFalse(Double.isNaN(s), "NaN at ch" + c + " frame " + f);
                    peak = Math.max(peak, Math.abs(s));
                    sumSq += s * s;
                }
            }
            double peakDb = 20 * Math.log10(Math.max(peak, 1e-20));
            double rmsDb = 10 * Math.log10(Math.max(sumSq / (outCh * filled), 1e-20));

            assertTrue(peakDb <= -0.5, "peak " + peakDb + " dBFS exceeds -0.5 dBFS");
            assertTrue(peakDb >= -30, "peak " + peakDb + " dBFS too low");
            assertTrue(rmsDb >= -40, "RMS " + rmsDb + " dBFS too low");
        }

        double meterPeak = masterMeter.getPeakDbfs(0);
        assertTrue(meterPeak >= -30 && meterPeak <= 0.5,
                "meter snapshot " + meterPeak + " dBFS out of range");
    }
}