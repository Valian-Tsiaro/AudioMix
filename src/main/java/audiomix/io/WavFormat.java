package audiomix.io;

/**
 * Supported WAV file formats. PCM16 and PCM24 use uncompressed integer
 * encoding (RIFF format tag 1). FLOAT32 uses 32-bit IEEE 754 (format tag 3).
 */
public enum WavFormat {
    /** 16-bit PCM, 2 bytes per sample, range ±32767. */
    PCM16(1, 16),
    /** 24-bit PCM, 3 bytes per sample, range ±8388607. */
    PCM24(1, 24),
    /** 32-bit IEEE float, 4 bytes per sample. */
    FLOAT32(3, 32);

    private final int tag;
    private final int bitsPerSample;

    WavFormat(int tag, int bitsPerSample) {
        this.tag = tag;
        this.bitsPerSample = bitsPerSample;
    }

    /** RIFF format tag (1 for PCM, 3 for IEEE float). */
    public int formatTag() {
        return tag;
    }

    /** Bits per sample (16, 24, or 32). */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /** Bytes per sample (2, 3, or 4). */
    public int bytesPerSample() {
        return bitsPerSample / 8;
    }

    /**
     * Resolves a format from RIFF tag and bits-per-sample.
     *
     * @param tag  RIFF format tag
     * @param bits bits per sample
     * @return matching format
     * @throws AudioIOException if tag/bits combination is unsupported
     */
    static WavFormat fromTag(int tag, int bits) {
        for (WavFormat f : values()) {
            if (f.tag == tag && f.bitsPerSample == bits) {
                return f;
            }
        }
        throw new AudioIOException("unsupported format: tag=" + tag + " bits=" + bits);
    }
}
