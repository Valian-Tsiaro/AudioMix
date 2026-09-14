package audiomix.core;

/**
 * Auxiliary bus for FX routing. Receives post-fader (or pre-fader) sends
 * from channels, applies its own effect chain and gain, then sums into
 * the master destination.
 */
public final class AuxBus extends Bus {

    private final String name;
    private final AudioBuffer sum;

    AuxBus(String name, int sampleRate, int blockSize) {
        super(sampleRate, blockSize);
        this.name = name;
        this.sum = AudioBuffer.create(2, blockSize);
    }

    /** Returns the bus name. */
    public String getName() { return name; }

    /** Per-block 2-channel sum buffer. Package-visible for Mixer. */
    AudioBuffer sum() { return sum; }

    /** Zeros the sum buffer at the start of each block. */
    void clearSum() { sum.clear(); }
}
