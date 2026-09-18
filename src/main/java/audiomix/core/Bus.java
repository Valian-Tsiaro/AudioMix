package audiomix.core;

import audiomix.io.DeviceInfo;
import audiomix.io.DeviceManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Ordered insert {@link Effect} chain with smoothed gain.
 * Applies effects in place, then multiplies by a linear ramp that
 * settles to the target gain over ~10 ms. Parameter setters are
 * callable from any thread.
 */
public class Bus {

    private static final Logger LOG = Logger.getLogger(Bus.class.getName());
    private static final double GAIN_RAMP_MS = 10.0;

    protected final int blockSize;
    private final ParamSmoother gainSmoother;
    private final CopyOnWriteArrayList<Effect> effects = new CopyOnWriteArrayList<>();
    Mixer owner; // ponytail: set by Mixer; inert until Step 26
    private static final DeviceManager DEVICES = new DeviceManager();

    /**
     * @param sampleRate project sample rate in Hz (&gt; 0)
     * @param blockSize  frames per block (&gt; 0)
     * @throws IllegalArgumentException if sampleRate or blockSize ≤ 0
     */
    public Bus(int sampleRate, int blockSize) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate=" + sampleRate);
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize=" + blockSize);
        this.blockSize = blockSize;
        this.gainSmoother = new ParamSmoother(sampleRate, GAIN_RAMP_MS);
        this.gainSmoother.setValue(1.0);
    }

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
     * Unmodifiable live view of the insert chain.
     *
     * @return current effect list
     */
    public List<Effect> getEffects() { return Collections.unmodifiableList(effects); }

    /**
     * Sets the output gain (linear). Callable from any thread.
     *
     * @param g gain ≥ 0
     * @throws IllegalArgumentException if {@code g < 0}
     */
    public void setGain(double g) {
        if (g < 0) throw new IllegalArgumentException("gain=" + g);
        gainSmoother.setTarget(g);
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
     * Apply the insert chain then the smoothed gain to {@code b} in place.
     * Per-effect exceptions degrade to silence for the remainder of the
     * chain; the exception never propagates.
     *
     * @param b audio buffer ({@code b.frames} must equal the bus block size)
     * @throws IllegalArgumentException if {@code b.frames != blockSize}
     */
    public void applyChainAndGain(AudioBuffer b) {
        if (b.frames != blockSize) {
            throw new IllegalArgumentException("buffer frames " + b.frames + " != " + blockSize);
        }
        for (Effect e : effects) {
            try {
                e.process(b);
            } catch (Exception ex) {
                b.clear();
                LOG.warning("effect failed: " + ex.getMessage());
            }
        }
        for (int i = 0; i < b.frames; i++) {
            double g = gainSmoother.nextValue();
            for (int ch = 0; ch < b.channels(); ch++) {
                b.data[ch][i] = (float) (g * b.data[ch][i]);
            }
        }
    }

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
     * Binds this bus to an output device, appending to the owning
     * {@link Mixer}'s binding registry. Registered with volatile
     * semantics; taking effect at a block boundary. Callable from
     * any thread.
     *
     * @param device output device (non-null)
     * @throws IllegalArgumentException if device is null
     * @throws IllegalStateException    if not owned by a mixer
     */
    public void bindToDevice(DeviceInfo device) {
        if (device == null) throw new IllegalArgumentException("device is null");
        if (owner == null) throw new IllegalStateException("bus has no mixer");
        owner.registerBinding(this, device);
    }

    /**
     * Binds this bus to the system default speakers. Callable from any
     * thread.
     *
     * @throws IllegalStateException    if not owned by a mixer
     * @throws audiomix.io.AudioIOException if there is no default output device
     */
    public void bindToDefaultSpeakers() {
        DeviceInfo d = DEVICES.defaultOutput();
        if (d == null) {
            throw new audiomix.io.AudioIOException("no default output device available");
        }
        bindToDevice(d);
    }
}
