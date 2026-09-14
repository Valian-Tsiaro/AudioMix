package audiomix.core;

import audiomix.source.SilenceSource;
import audiomix.source.SineSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MuteSoloTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final double LEVEL = 0.25;
    private static final int WARMUP = 3;

    private static SineSource sine() {
        return new SineSource(SR, 440, LEVEL, 1, -1);
    }

    private static SilenceSource silence() {
        return new SilenceSource(1, -1);
    }

    private static boolean allZero(AudioBuffer buf) {
        for (float[] ch : buf.data) {
            for (float v : ch) {
                if (v != 0.0f) return false;
            }
        }
        return true;
    }

    private static boolean matches(AudioBuffer ref, AudioBuffer dest, double tol) {
        for (int ch = 0; ch < ref.channels(); ch++) {
            for (int i = 0; i < ref.frames; i++) {
                if (Math.abs(dest.data[ch][i] - ref.data[ch][i]) > tol) return false;
            }
        }
        return true;
    }

    private static AudioBuffer captureRef() {
        Mixer m = new Mixer(SR, BS);
        m.addChannel("T").setSource(sine());
        m.addChannel("P").setSource(silence());
        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < WARMUP; i++) m.processBlock(dest);
        AudioBuffer ref = AudioBuffer.create(2, BS);
        for (int ch = 0; ch < 2; ch++)
            System.arraycopy(dest.data[ch], 0, ref.data[ch], 0, BS);
        return ref;
    }

    private static AudioBuffer renderRow(boolean tMuted, boolean tSolo, boolean pSolo) {
        Mixer m = new Mixer(SR, BS);
        Channel t = m.addChannel("T");
        Channel p = m.addChannel("P");
        t.setSource(sine());
        p.setSource(silence());
        t.setMuted(tMuted);
        t.setSolo(tSolo);
        p.setSolo(pSolo);
        AudioBuffer dest = AudioBuffer.create(2, BS);
        for (int i = 0; i < WARMUP; i++) m.processBlock(dest);
        return dest;
    }

    @Test
    void truthTable() {
        AudioBuffer ref = captureRef();
        assertFalse(allZero(ref), "reference must be non-silent");

        Object[][] rows = {
            { false, false, false, true  },
            { true,  false, false, false },
            { false, false, true,  false },
            { true,  false, true,  false },
            { false, true,  false, true  },
            { true,  true,  false, true  },
            { false, true,  true,  true  },
            { true,  true,  true,  true  },
        };

        for (Object[] row : rows) {
            boolean tMuted = (boolean) row[0];
            boolean tSolo  = (boolean) row[1];
            boolean pSolo  = (boolean) row[2];
            boolean expect = (boolean) row[3];

            AudioBuffer out = renderRow(tMuted, tSolo, pSolo);

            if (expect) {
                assertTrue(matches(ref, out, 1e-6),
                    "audible: muted=" + tMuted + " solo=" + tSolo + " pSolo=" + pSolo);
            } else {
                assertTrue(allZero(out),
                    "silent: muted=" + tMuted + " solo=" + tSolo + " pSolo=" + pSolo);
            }
        }

        // un-soloing P restores T — build ref and test at same sine phase
        {
            // reference: 2×WARMUP blocks, all flags off
            Mixer rm = new Mixer(SR, BS);
            rm.addChannel("T").setSource(sine());
            rm.addChannel("P").setSource(silence());
            AudioBuffer refDest = AudioBuffer.create(2, BS);
            for (int i = 0; i < 2 * WARMUP; i++) rm.processBlock(refDest);
            AudioBuffer ref2 = AudioBuffer.create(2, BS);
            for (int ch = 0; ch < 2; ch++)
                System.arraycopy(refDest.data[ch], 0, ref2.data[ch], 0, BS);

            // test: solo P for WARMUP, then un-solo for WARMUP
            Mixer m = new Mixer(SR, BS);
            Channel t = m.addChannel("T");
            Channel p = m.addChannel("P");
            t.setSource(sine());
            p.setSource(silence());
            p.setSolo(true);
            AudioBuffer dest = AudioBuffer.create(2, BS);
            for (int i = 0; i < WARMUP; i++) m.processBlock(dest);
            assertTrue(allZero(dest), "P soloed → T silent");

            p.setSolo(false);
            for (int i = 0; i < WARMUP; i++) m.processBlock(dest);
            assertTrue(matches(ref2, dest, 1e-6), "P un-soloed → T restored");
        }
    }
}
