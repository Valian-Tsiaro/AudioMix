package audiomix.core;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * A single channel strip in the console. Pulls from a {@link Source},
 * applies an ordered insert {@link Effect} chain, and mixes into a
 * stereo destination buffer with smoothed gain and mute/solo gating.
 * <p>
 * Dimensions (channel count, block size) are fixed at construction.
 * Parameter setters ({@link #setGain}, {@link #setMuted}, etc.)
 * are callable from any thread; they store volatile targets consumed
 * at block boundaries.
 */
public final class Channel {

    private static final Logger LOG = Logger.getLogger(Channel.class.getName());
    private static final double GAIN_RAMP_MS = 10.0;

    private final String name;
    private final int channelCount;
    private final int blockSize;
    private final AudioBuffer staging;
    private final ParamSmoother leftSmoother;
    private final ParamSmoother rightSmoother;
    private final double[] leftGainPerSample;
    private final double[] rightGainPerSample;
    private volatile double gainTarget = 1.0;
    private volatile Double panPosition;
    private final CopyOnWriteArrayList<Effect> effects = new CopyOnWriteArrayList<>();

    private volatile Source source;
    private volatile boolean muted;
    private volatile boolean solo;
    private volatile boolean sourceExhausted;
    private volatile boolean active;
    private long framesServed;

    /**
     * Creates a new channel strip.
     *
     * @param name         strip name (non-null)
     * @param channels     1 (mono) or 2 (stereo)
     * @param sampleRate   project sample rate in Hz (&gt; 0)
     * @param blockSize    frames per block (&gt; 0)
     * @throws IllegalArgumentException if channels, sampleRate, or blockSize is out of range
     */
    public Channel(String name, int channels, int sampleRate, int blockSize) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (channels != 1 && channels != 2) throw new IllegalArgumentException("channels=" + channels);
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize=" + blockSize);
        this.name = name;
        this.channelCount = channels;
        this.blockSize = blockSize;
        this.staging = AudioBuffer.create(channels, blockSize);
        this.leftSmoother = new ParamSmoother(sampleRate, GAIN_RAMP_MS);
        this.leftSmoother.setValue(1.0);
        this.rightSmoother = new ParamSmoother(sampleRate, GAIN_RAMP_MS);
        this.rightSmoother.setValue(1.0);
        this.leftGainPerSample = new double[blockSize];
        this.rightGainPerSample = new double[blockSize];
    }

    /** Returns the strip name. */
    public String getName() { return name; }

    /** Returns the channel count (1 or 2). */
    public int getChannelCount() { return channelCount; }

    /**
     * Replaces the audio source. Callable from any thread.
     * Setting a non-null source marks the channel active immediately
     * (before the next {@link #process()} call), so the mixer recognises
     * it has work to do.
     *
     * @param s new source (may be null to clear)
     */
    public void setSource(Source s) {
        this.source = s;
        this.sourceExhausted = false;
        this.active = s != null;
    }

    /** Returns the current source, or {@code null}. */
    public Source getSource() { return source; }

    /**
     * Sets the output gain (linear). Callable from any thread.
     *
     * @param g gain ≥ 0
     * @throws IllegalArgumentException if {@code g &lt; 0}
     */
    public void setGain(double g) {
        if (g < 0) throw new IllegalArgumentException("gain=" + g);
        gainTarget = g;
        retarget();
    }

    /**
     * Sets the output gain in dB. Callable from any thread.
     *
     * @param db gain in dB
     */
    public void setGainDb(double db) {
        setGain(GainMath.dbToLinear(db));
    }

    /**
     * Sets the stereo pan position on a mono strip. Callable from any thread.
     * The value is clamped to [-1, 1] and smoothed over ~10 ms.
     *
     * @param x pan in [-1, 1] (left to right)
     * @throws IllegalStateException if this is a stereo strip
     */
    public void setPan(double x) {
        if (channelCount != 1) throw new IllegalStateException("setPan on stereo strip");
        panPosition = GainMath.clamp(x, -1.0, 1.0);
        retarget();
    }

    /**
     * Sets the L/R balance on a stereo strip. Callable from any thread.
     * The value is clamped to [-1, 1] and smoothed over ~10 ms.
     * Positive attenuates left; negative attenuates right.
     *
     * @param x balance in [-1, 1]
     * @throws IllegalStateException if this is a mono strip
     */
    public void setBalance(double x) {
        if (channelCount != 2) throw new IllegalStateException("setBalance on mono strip");
        panPosition = GainMath.clamp(x, -1.0, 1.0);
        retarget();
    }

    private void retarget() {
        double g = gainTarget;
        if (channelCount == 1) {
            if (panPosition != null) {
                double[] p = GainMath.equalPowerPan(panPosition);
                leftSmoother.setTarget(p[0] * g);
                rightSmoother.setTarget(p[1] * g);
            } else {
                leftSmoother.setTarget(g);
                rightSmoother.setTarget(g);
            }
        } else {
            double x = panPosition != null ? panPosition : 0.0;
            double lg = x > 0 ? 1 - x : 1.0;
            double rg = x < 0 ? 1 + x : 1.0;
            leftSmoother.setTarget(lg * g);
            rightSmoother.setTarget(rg * g);
        }
    }

    /**
     * Mutes the channel output. Callable from any thread.
     * Mute does not affect {@link #isActive()}.
     *
     * @param b true to mute
     */
    public void setMuted(boolean b) { this.muted = b; }

    /** Returns true if the channel is muted. */
    public boolean isMuted() { return muted; }

    /**
     * Marks this channel as solo. Callable from any thread.
     * When any channel in the mix is soloed, only soloed channels are audible.
     *
     * @param b true to solo
     */
    public void setSolo(boolean b) { this.solo = b; }

    /** Returns true if this channel is soloed. */
    public boolean isSolo() { return solo; }

    /**
     * Appends an effect to the insert chain.
     *
     * @param e effect to add
     * @return true (per {@link java.util.List#add})
     */
    public boolean addEffect(Effect e) { return effects.add(e); }

    /**
     * Removes an effect from the insert chain.
     *
     * @param e effect to remove
     * @return true if the effect was present
     */
    public boolean removeEffect(Effect e) { return effects.remove(e); }

    /**
     * Unmodifiable live view of the insert chain. Safe to iterate
     * while effects are added or removed (CopyOnWriteArrayList).
     *
     * @return current effect list
     */
    public List<Effect> getEffects() { return Collections.unmodifiableList(effects); }

    /**
     * Process one block: clear staging, pull source into staging,
     * run the insert chain over staging. Returns whether the channel
     * is still active (source not exhausted or chain not idle).
     * <p>
     * A channel with no source is always inactive.
     * An exhausted source still runs the chain so effect tails drain.
     * A source or effect that throws degrades this block's contribution
     * to silence and logs a warning; the exception never propagates.
     *
     * @return true while the channel contributes to the mix
     */
    public boolean process() {
        staging.clear();
        Source src = source;
        if (src != null) {
            // ponytail: direct read into staging; source fills all channels of
            // whatever buffer it's given. Stereo→mono averaging deferred
            // (sources expose getChannels() but Channel is mono/stereo only).
            try {
                int filled = src.read(staging);
                if (filled == 0) {
                    sourceExhausted = true;
                } else {
                    framesServed += filled;
                }
            } catch (Exception ex) {
                staging.clear();
                LOG.warning("source read failed: " + ex.getMessage());
            }
        }
        for (Effect e : effects) {
            try {
                e.process(staging);
            } catch (Exception ex) {
                staging.clear();
                LOG.warning("effect failed: " + ex.getMessage());
            }
        }
        for (int i = 0; i < blockSize; i++) {
            leftGainPerSample[i] = leftSmoother.nextValue();
            rightGainPerSample[i] = rightSmoother.nextValue();
        }
        active = src != null && (!sourceExhausted || !isChainIdle());
        return active;
    }

    /**
     * Add this channel's contribution into the stereo destination.
     * The contribution is gated by the mute/solo logic:
     * <pre>audible = isSolo() || (!anySoloActive &amp;&amp; !isMuted())</pre>
     * When not audible, contributes silence.
     * <p>
     * Per-sample left/right gains are pre-computed during {@link #process()}
     * incorporating pan/balance and fader gain, ramped click-free over ~10 ms.
     * Mono staging is split across L/R by the equal-power pan law;
     * stereo staging uses the balance multiplier per channel.
     *
     * @param stereoDest     stereo destination buffer (2 channels, frames == blockSize)
     * @param anySoloActive  true if any channel in the mix is soloed
     * @throws IllegalArgumentException if {@code stereoDest} is not stereo or
     *         has a different block size
     */
    public void mixInto(AudioBuffer stereoDest, boolean anySoloActive) {
        if (stereoDest.channels() != 2) {
            throw new IllegalArgumentException("dest not stereo: " + stereoDest.channels());
        }
        if (stereoDest.frames != blockSize) {
            throw new IllegalArgumentException("block size mismatch: " + stereoDest.frames + " != " + blockSize);
        }

        boolean audible = solo || (!anySoloActive && !muted);

        if (!audible) {
            return;
        }

        for (int i = 0; i < blockSize; i++) {
            if (channelCount == 2) {
                stereoDest.data[0][i] += leftGainPerSample[i] * staging.data[0][i];
                stereoDest.data[1][i] += rightGainPerSample[i] * staging.data[1][i];
            } else {
                stereoDest.data[0][i] += leftGainPerSample[i] * staging.data[0][i];
                stereoDest.data[1][i] += rightGainPerSample[i] * staging.data[0][i];
            }
        }
    }

    /**
     * True while the channel has a non-exhausted source or its
     * effect chain is still ringing. A channel with no source is
     * always inactive.
     *
     * @return true if the channel contributes to the mix
     */
    public boolean isActive() { return active; }

    void zeroStaging() { staging.clear(); }

    /**
     * True when every effect in the chain reports idle.
     * Returns true if the chain is empty.
     *
     * @return true if the chain has no remaining tail
     */
    public boolean isChainIdle() {
        return effects.isEmpty() || effects.stream().allMatch(Effect::isIdle);
    }

    /**
     * Total frames pulled from this channel's source so far.
     * Monotonic; 0 before the first {@link #process()} with a live source.
     *
     * @return cumulative frames read (may exceed actual content length if
     *         the source over-reads; for finite sources this is exact)
     */
    long framesServed() { return framesServed; }
}
