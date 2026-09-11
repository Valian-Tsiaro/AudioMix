package audiomix.core;

/**
 * Non-interleaved audio source that fills an {@link AudioBuffer} on demand.
 * <p>
 * Each call to {@link #read} fills frames from the current position.
 * Returns the number of frames actually filled; this may be less than
 * {@code b.frames} near end-of-stream. Returns {@code 0} once exhausted,
 * and remains at {@code 0} on every subsequent call.
 * <p>
 * Safe to call {@link #close()} at any time. After close, behaviour is
 * implementation-defined.
 */
public interface Source {

    /**
     * Fill as many frames as available into {@code b}.
     *
     * @param b buffer to fill (must have ≥ 1 channel and ≥ 1 frame)
     * @return frames actually filled; 0 = exhausted forever
     */
    int read(AudioBuffer b);

    /** Release any underlying resources. */
    void close();
}
