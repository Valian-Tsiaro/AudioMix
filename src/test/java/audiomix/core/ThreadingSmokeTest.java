package audiomix.core;

import audiomix.source.SineSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class ThreadingSmokeTest {

    private static final int SR = 48000;
    private static final int BS = 512;
    private static final long FRAMES = SR * 2;
    private static final int CAP = 4096;
    private static final int HAMMER_OPS = 10_000;

    private static final Effect PASS = new Effect() {
        @Override public void process(AudioBuffer b) { }
        @Override public boolean isIdle() { return true; }
    };

    private record RunResult(List<Throwable> errors, float[][] output) { }

    private static RunResult run(boolean muteSoloHammer) throws Exception {
        Mixer mixer = new Mixer(SR, BS);
        AuxBus fx = mixer.addAuxBus("fx");

        double[][] setup = {
            { 220, -0.8 }, { 330, -0.3 }, { 440, 0.3 }, { 550, 0.8 }
        };
        Channel[] channels = new Channel[4];
        for (int i = 0; i < 4; i++) {
            Channel ch = mixer.addChannel("ch" + i);
            channels[i] = ch;
            ch.setSource(new SineSource(SR, setup[i][0], 0.15, 1, FRAMES));
            ch.setPan(setup[i][1]);
            ch.addSend(fx, 0.15);
        }
        mixer.getMaster().setGainDb(0);

        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            try {
                Random rng = new Random(1);
                for (int i = 0; i < HAMMER_OPS && !done.get(); i++) {
                    for (Channel ch : channels) {
                        if (rng.nextBoolean()) ch.setGainDb(-24 * rng.nextDouble());
                        else ch.setGain(rng.nextDouble());
                    }
                    LockSupport.parkNanos(10_000L);
                }
            } catch (Throwable e) { errors.add(e); }
        }, "T1-gain");

        Thread t2 = new Thread(() -> {
            try {
                while (!done.get()) {
                    for (double x = -1.0; x <= 1.0 + 1e-9; x += 0.05) {
                        for (Channel ch : channels) ch.setPan(x);
                        LockSupport.parkNanos(10_000L);
                    }
                    for (double x = 1.0; x >= -1.0 - 1e-9; x -= 0.05) {
                        for (Channel ch : channels) ch.setPan(x);
                        LockSupport.parkNanos(10_000L);
                    }
                }
            } catch (Throwable e) { errors.add(e); }
        }, "T2-pan");

        Thread t3 = new Thread(() -> {
            try {
                Random rng = new Random(3);
                for (int i = 0; i < HAMMER_OPS && !done.get(); i++) {
                    Channel ch = channels[rng.nextInt(4)];
                    if (muteSoloHammer) {
                        ch.setMuted(rng.nextBoolean());
                        ch.setSolo(rng.nextBoolean());
                    }
                    ch.setSendLevel(fx, rng.nextDouble() * 0.3);
                    LockSupport.parkNanos(10_000L);
                }
            } catch (Throwable e) { errors.add(e); }
        }, "T3-mutesend");

        Thread t4 = new Thread(() -> {
            try {
                Random rng = new Random(4);
                for (int i = 0; i < HAMMER_OPS && !done.get(); i++) {
                    Channel ch = channels[rng.nextInt(4)];
                    ch.addEffect(PASS);
                    ch.removeEffect(PASS);
                    if (i % 50 == 0) {
                        mixer.addChannel("hammer-" + i);
                        mixer.addAuxBus("hfx-" + i);
                    }
                    LockSupport.parkNanos(10_000L);
                }
            } catch (Throwable e) { errors.add(e); }
        }, "T4-effect");

        t1.start(); t2.start(); t3.start(); t4.start();

        AudioBuffer dest = AudioBuffer.create(2, BS);
        List<float[]> leftBlocks = new ArrayList<>();
        List<float[]> rightBlocks = new ArrayList<>();
        long blocks = 0;

        while (!mixer.allIdle() && blocks < CAP) {
            mixer.processBlock(dest);
            float[] L = new float[BS];
            float[] R = new float[BS];
            System.arraycopy(dest.data[0], 0, L, 0, BS);
            System.arraycopy(dest.data[1], 0, R, 0, BS);
            leftBlocks.add(L);
            rightBlocks.add(R);
            blocks++;
        }
        float[][] out = new float[2][];
        out[0] = flatten(leftBlocks);
        out[1] = flatten(rightBlocks);

        done.set(true);
        for (Thread t : new Thread[]{ t1, t2, t3, t4 }) {
            t.join(10_000);
            assertFalse(t.isAlive(), t.getName() + " still alive after join");
        }

        assertTrue(mixer.allIdle(), "render did not exhaust — possible livelock");
        return new RunResult(errors, out);
    }

    private static float[] flatten(List<float[]> blocks) {
        int total = blocks.size() * BS;
        float[] out = new float[total];
        int pos = 0;
        for (float[] b : blocks) {
            System.arraycopy(b, 0, out, pos, BS);
            pos += BS;
        }
        return out;
    }

    private static void assertFinite(float[][] samples) {
        for (int ch = 0; ch < samples.length; ch++) {
            for (int i = 0; i < samples[ch].length; i++) {
                assertTrue(Float.isFinite(samples[ch][i]),
                    "non-finite ch=" + ch + " idx=" + i + ": " + samples[ch][i]);
            }
        }
    }

    private static double maxDelta(float[][] samples) {
        double max = 0;
        for (int ch = 0; ch < samples.length; ch++) {
            for (int i = 1; i < samples[ch].length; i++) {
                double d = Math.abs(samples[ch][i] - samples[ch][i - 1]);
                if (d > max) max = d;
            }
        }
        return max;
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void clickBound() throws Exception {
        RunResult r = run(false);
        assertTrue(r.errors().isEmpty(), "hammer exceptions: " + r.errors());
        assertFinite(r.output());
        double md = maxDelta(r.output());
        assertTrue(md <= 0.05, "max per-sample delta=" + md);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void withMuteSolo() throws Exception {
        RunResult r = run(true);
        assertTrue(r.errors().isEmpty(), "hammer exceptions: " + r.errors());
        assertFinite(r.output());
    }
}
