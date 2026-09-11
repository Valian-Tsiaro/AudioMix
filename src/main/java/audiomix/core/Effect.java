package audiomix.core;

/**
 * In-place audio effect applied in an ordered insert chain.
 * Processing is called in block order; implementations must not
 * allocate on the audio path.
 */
public interface Effect {

    /**
     * Process one block of audio in place.
     *
     * @param b audio buffer (same channel count as the owning strip)
     */
    void process(AudioBuffer b);

    /**
     * True when this effect has no remaining tail (e.g. reverb decay,
     * delay feedback). The default returns {@code true}.
     *
     * @return false while a tail still rings
     */
    default boolean isIdle() { return true; }
}
