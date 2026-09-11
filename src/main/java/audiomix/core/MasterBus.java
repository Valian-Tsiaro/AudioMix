package audiomix.core;

/**
 * The final stereo summing point in a {@link Mixer}.
 * Extends {@link Bus} with no additional behaviour — present as a
 * type distinction for future wiring (aux buses, sends, realtime output).
 */
public final class MasterBus extends Bus {

    /**
     * @param sampleRate project sample rate in Hz (&gt; 0)
     * @param blockSize  frames per block (&gt; 0)
     * @throws IllegalArgumentException if sampleRate or blockSize ≤ 0
     */
    public MasterBus(int sampleRate, int blockSize) {
        super(sampleRate, blockSize);
    }
}
