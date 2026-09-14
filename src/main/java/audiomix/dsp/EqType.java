package audiomix.dsp;

/**
 * Filter response shapes available for a {@link Biquad} section.
 */
public enum EqType {
    /** Peaking bell: unity outside the band, gain at the center. */
    PEAK,
    /** Low shelf: fixed gain below the corner, unity above. */
    LOW_SHELF,
    /** High shelf: fixed gain above the corner, unity below. */
    HIGH_SHELF,
    /** Second-order highpass: -3 dB at the cutoff, -40 dB/decade skirt. */
    HIGHPASS,
    /** Second-order lowpass: -3 dB at the cutoff, -40 dB/decade skirt. */
    LOWPASS,
    /** Notch: deep rejection at the center frequency, unity elsewhere. */
    NOTCH
}
