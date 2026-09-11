package audiomix.dsp;

/**
 * Stateful streaming linear-interpolation resampler, any ratio, per-channel.
 * <p>
 * Preserves the continuous frequency of the input signal and scales frame
 * count by {@code outRate / inRate}. A 440 Hz sine sampled at {@code inRate}
 * comes out a 440 Hz sine sampled at {@code outRate}; the discrete frequency
 * transforms as {@code f_out = f_in * inRate / outRate}.
 * <p>
 * Streaming: callers may push fixed input blocks via {@link #process} and pull
 * fixed output blocks; leftover input is buffered internally and the fractional
 * read position carries across calls. Output values depend only on the input
 * stream, not on how it is chunked. Once {@link #flush} returns 0 the
 * resampler is exhausted forever. Not thread-safe; call from one thread.
 */
public final class Resampler {

    private final int channels;
    private final double step;
    private final double outPerIn;

    private float[][] buf;
    private int valid;
    private long bufferStart;
    private long consumed;
    private double frac;
    private long totalRealFed;
    private boolean done;

    /**
     * Creates a resampler converting from {@code inRate} to {@code outRate}.
     * Equal rates yield an exact sample-by-sample passthrough.
     *
     * @param inRate input sample rate in Hz, positive
     * @param outRate output sample rate in Hz, positive
     * @param channels channel count, positive
     * @throws IllegalArgumentException if any rate or channel count is not positive
     */
    public Resampler(int inRate, int outRate, int channels) {
        if (inRate <= 0 || outRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("rates and channels must be positive");
        }
        this.channels = channels;
        this.step = (double) inRate / outRate;
        this.outPerIn = (double) outRate / inRate;
        this.buf = new float[channels][0];
    }

    /** @return output frames per input frame ({@code outRate / inRate}) */
    public double ratio() {
        return outPerIn;
    }

    /**
     * Consumes exactly {@code inFrames} input frames and writes as many
     * interpolated output frames as fit in {@code out} (up to {@code outFrames}
     * per channel). Input arrays are read from index 0. Returns 0 if no output
     * is ready yet, or if the resampler is exhausted after {@link #flush}.
     *
     * @param in input samples, {@code [channel][frame]}, at least {@code inFrames} frames
     * @param inFrames number of input frames to consume
     * @param out output samples, {@code [channel][frame]}, filled in place
     * @param outFrames maximum output frames to produce per channel
     * @return frames produced per channel, 0 if no output is ready yet or exhausted
     */
    public int process(float[][] in, int inFrames, float[][] out, int outFrames) {
        if (done) return 0;
        append(in, inFrames);
        totalRealFed += inFrames;
        return emit(out, outFrames, false);
    }

    /**
     * Emits the remaining output after end-of-input, interpolating the final
     * partial sample toward zero, up to {@code outFrames} per channel. May need
     * several calls if {@code out} is small; returns 0 forever once done.
     *
     * @param out output samples, {@code [channel][frame]}, filled in place
     * @param outFrames maximum output frames to produce per channel
     * @return frames produced per channel, 0 when exhausted
     */
    public int flush(float[][] out, int outFrames) {
        if (done) return 0;
        int produced = emit(out, outFrames, true);
        if (consumed + frac >= totalRealFed) done = true;
        return produced;
    }

    private void append(float[][] in, int inFrames) {
        int drop = (int) (consumed - bufferStart);
        if (drop > 0) {
            for (int c = 0; c < channels; c++) {
                System.arraycopy(buf[c], drop, buf[c], 0, valid - drop);
            }
            valid -= drop;
            bufferStart += drop;
        }
        if (valid + inFrames > buf[0].length) {
            int grown = Math.max(valid + inFrames, buf[0].length * 2);
            float[][] nb = new float[channels][grown];
            for (int c = 0; c < channels; c++) {
                System.arraycopy(buf[c], 0, nb[c], 0, valid);
            }
            buf = nb;
        }
        for (int c = 0; c < channels; c++) {
            System.arraycopy(in[c], 0, buf[c], valid, inFrames);
        }
        valid += inFrames;
    }

    private int emit(float[][] out, int outFrames, boolean drain) {
        int produced = 0;
        while (produced < outFrames) {
            int i0 = (int) (consumed - bufferStart);
            if (drain) {
                if (consumed + frac >= totalRealFed) break;
            } else if (i0 + 1 >= valid) {
                break;
            }
            double f = frac;
            for (int c = 0; c < channels; c++) {
                float s0 = buf[c][i0];
                float outsample;
                if (f == 0.0) {
                    outsample = s0;
                } else {
                    float s1;
                    if (i0 + 1 < valid) {
                        s1 = buf[c][i0 + 1];
                    } else {
                        s1 = 0f;
                    }
                    outsample = (float) (s0 + (s1 - s0) * f);
                }
                out[c][produced] = outsample;
            }
            frac += step;
            long carry = (long) frac;
            frac -= carry;
            consumed += carry;
            produced++;
        }
        return produced;
    }
}
