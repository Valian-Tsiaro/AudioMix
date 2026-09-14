package audiomix.core;

import audiomix.io.AudioIOException;
import audiomix.io.WavFormat;
import audiomix.io.WavWriter;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * The pull engine: owns an ordered list of {@link Channel}s and a
 * {@link MasterBus}. One call to {@link #processBlock} pulls all
 * channels, sums into a stereo destination, then runs the master bus.
 */
public final class Mixer {

    private static final Logger LOG = Logger.getLogger(Mixer.class.getName());

    private final int sampleRate;
    private final int blockSize;
    private final CopyOnWriteArrayList<Channel> channels = new CopyOnWriteArrayList<>();
    private final MasterBus master;

    /**
     * Creates a mixer with the default block size (512 frames).
     *
     * @param sampleRate project sample rate in Hz (&gt; 0)
     * @throws IllegalArgumentException if sampleRate ≤ 0
     */
    public Mixer(int sampleRate) {
        this(sampleRate, 512);
    }

    /**
     * Creates a mixer with an explicit block size.
     *
     * @param sampleRate project sample rate in Hz (&gt; 0)
     * @param blockSize  frames per block (&gt; 0)
     * @throws IllegalArgumentException if sampleRate or blockSize ≤ 0
     */
    public Mixer(int sampleRate, int blockSize) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize=" + blockSize);
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.master = new MasterBus(sampleRate, blockSize);
    }

    /**
     * Adds a mono channel strip.
     *
     * @param name strip name (non-null)
     * @return the new channel
     */
    public Channel addChannel(String name) {
        return addChannel(name, false);
    }

    /**
     * Adds a channel strip.
     *
     * @param name   strip name (non-null)
     * @param stereo true for stereo, false for mono
     * @return the new channel
     */
    public Channel addChannel(String name, boolean stereo) {
        Channel ch = new Channel(name, stereo ? 2 : 1, sampleRate, blockSize);
        channels.add(ch);
        return ch;
    }

    /**
     * Returns the first channel with the given name, or {@code null}.
     *
     * @param name channel name
     * @return matching channel, or null
     */
    public Channel getChannel(String name) {
        for (Channel ch : channels) {
            if (ch.getName().equals(name)) return ch;
        }
        return null;
    }

    /**
     * Unmodifiable live view of the channel list.
     *
     * @return current channels
     */
    public List<Channel> getChannels() { return Collections.unmodifiableList(channels); }

    public MasterBus getMaster() { return master; }

    public int getSampleRate() { return sampleRate; }

    public int getBlockSize() { return blockSize; }

    /**
     * True when any channel in the mix is soloed. Scanned once per block.
     *
     * @return true if at least one channel is soloed
     */
    public boolean anySolo() {
        return channels.stream().anyMatch(Channel::isSolo);
    }

    /**
     * Pull one block from all channels into {@code stereoDest},
     * then run the master bus. Per-channel or per-effect failures
     * degrade to silence for that contribution; exceptions never escape.
     *
     * @param stereoDest stereo destination (2 channels × blockSize frames)
     * @throws IllegalArgumentException if dimensions are wrong
     */
    public void processBlock(AudioBuffer stereoDest) {
        if (stereoDest.channels() != 2) {
            throw new IllegalArgumentException("dest not stereo: " + stereoDest.channels());
        }
        if (stereoDest.frames != blockSize) {
            throw new IllegalArgumentException("block size mismatch: " + stereoDest.frames + " != " + blockSize);
        }

        stereoDest.clear();
        boolean solo = anySolo();

        for (Channel ch : channels) {
            try {
                if (ch.process()) ch.mixInto(stereoDest, solo);
            } catch (Throwable t) {
                ch.zeroStaging();
                LOG.warning("channel failed: " + t.getMessage());
            }
        }

        try {
            master.applyChainAndGain(stereoDest);
        } catch (Throwable t) {
            LOG.warning("master bus failed: " + t.getMessage());
            stereoDest.clear();
        }
    }

    /**
     * True when every channel has finished (source exhausted and
     * chain idle).
     *
     * @return true if no channel contributes
     */
    public boolean isExhausted() {
        return channels.stream().noneMatch(Channel::isActive);
    }

    /**
     * True when isExhausted and the master bus chain is also idle.
     *
     * @return true if the entire mixer is silent
     */
    public boolean allIdle() {
        return isExhausted() && master.isChainIdle();
    }

    /**
     * Offline render to a stereo FLOAT32 WAV file.
     *
     * @param path output file path
     * @throws AudioIOException on write error
     */
    public void renderToFile(String path) {
        renderToFile(path, WavFormat.FLOAT32);
    }

    /**
     * Offline render to a stereo WAV file in the given format.
     * Termination is guaranteed by {@link #allIdle()} semantics — every
     * channel's source must exhaust and every effect tail must decay.
     * The output frame count is exact: trailing zero-padded blocks are
     * discarded, and the final block is trimmed to the last frame that
     * could contain content.
     * <p>
     * The caller retains ownership of all sources and effects; this
     * method does not close them.
     *
     * @param path output file path
     * @param fmt  sample format (PCM16, PCM24, or FLOAT32)
     * @throws AudioIOException on write error
     */
    public void renderToFile(String path, WavFormat fmt) {
        WavWriter writer = new WavWriter(Path.of(path), 2, sampleRate, fmt);
        AudioBuffer buf = AudioBuffer.create(2, blockSize);
        AudioBuffer pending = AudioBuffer.create(2, blockSize);
        boolean pendingSet = false;
        long written = 0;
        try {
            while (true) {
                processBlock(buf);
                if (pendingSet) {
                    if (!allIdle()) {
                        writer.write(pending);
                        written += blockSize;
                    } else {
                        long served = 0;
                        for (Channel ch : channels) {
                            served = Math.max(served, ch.framesServed());
                        }
                        int lastNzP = lastNonzero(pending);
                        int lastNzC = lastNonzero(buf);
                        long total = Math.max(served, written + lastNzP + 1);
                        if (lastNzC >= 0) {
                            total = Math.max(total, written + blockSize + lastNzC + 1);
                        }
                        long takeP = Math.min(Math.max(total - written, 0), blockSize);
                        if (takeP > 0) {
                            AudioBuffer trim = AudioBuffer.create(2, (int) takeP);
                            for (int ch = 0; ch < 2; ch++) {
                                System.arraycopy(pending.data[ch], 0, trim.data[ch], 0, (int) takeP);
                            }
                            writer.write(trim);
                        }
                        long takeC = Math.min(Math.max(total - written - takeP, 0), blockSize);
                        if (takeC > 0) {
                            AudioBuffer trim = AudioBuffer.create(2, (int) takeC);
                            for (int ch = 0; ch < 2; ch++) {
                                System.arraycopy(buf.data[ch], 0, trim.data[ch], 0, (int) takeC);
                            }
                            writer.write(trim);
                        }
                        break;
                    }
                }
                // swap pending/buf
                AudioBuffer tmp = pending;
                pending = buf;
                buf = tmp;
                pendingSet = true;
            }
        } finally {
            writer.close();
        }
    }

    /** Returns the index of the last frame containing a nonzero sample, or -1 if all zero. */
    private static int lastNonzero(AudioBuffer b) {
        for (int i = b.frames - 1; i >= 0; i--) {
            for (int ch = 0; ch < b.channels(); ch++) {
                if (b.data[ch][i] != 0.0f) return i;
            }
        }
        return -1;
    }
}
