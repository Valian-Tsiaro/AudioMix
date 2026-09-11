package audiomix.source;

import audiomix.core.AudioBuffer;
import audiomix.core.Source;

import java.util.Arrays;

/**
 * A {@link Source} decorator that models a DAW-style clip: a sub-range
 * of an inner source placed at a non-zero position on the timeline.
 *
 * <p>Each {@link #read} call produces a contiguous output segment composed,
 * in order, of:
 * <ol>
 *   <li>lead-in zeros (up to {@code b.frames}),</li>
 *   <li>any pending carry from a previous read (skip or serve overshoot),</li>
 *   <li>exactly one fresh {@code inner.read} shifted to the current fill point.</li>
 * </ol>
 *
 * <p>If a phase read overshoots its allotted room the excess frames are
 * stashed in a private carry buffer and served on a subsequent call — no
 * mid-clip content is lost. Carry from the skip phase and from serve reads
 * share the same buffer.
 *
 * <p>To preserve the timeline (carry content must appear after the full
 * lead-in), a call that drains carry performs no fresh read, and the carry
 * is not split across lead-in and serve within the same call.
 *
 * <p>Thread safety: stateful but single-threaded (audio thread only).
 * No internal volatile fields.
 */
public final class ClipSource implements Source, AutoCloseable {

    private final Source inner;
    private long leadInRemaining;
    private long skipRemaining;
    private long serveRemaining;
    private boolean exhausted;
    private boolean closed;

    private float[][] carryData;
    private int carryLen;

    /**
     * Create a clip over {@code inner}.
     *
     * @param inner source supplying the clip content (never read past {@code outSec})
     * @param projectRate project sample rate in Hz, shared with the owning Mixer
     * @param offsetSec lead-in silence before the clip starts, in seconds, ≥ 0
     * @param inSec skip the first {@code inSec * projectRate} frames of inner, in seconds, ≥ 0
     * @param outSec stop after {@code (outSec - inSec) * projectRate} frames served, in seconds, ≥ inSec;
     *               to play to EOF pass the inner's exact length
     * @throws IllegalArgumentException on null inner, non-positive projectRate,
     *         negative/NaN/Infinite time arguments, or outSec &lt; inSec
     */
    public ClipSource(Source inner, int projectRate,
                      double offsetSec, double inSec, double outSec) {
        if (inner == null) throw new IllegalArgumentException("inner is null");
        if (projectRate <= 0) throw new IllegalArgumentException("projectRate=" + projectRate);
        if (Double.isNaN(offsetSec) || Double.isInfinite(offsetSec) || offsetSec < 0)
            throw new IllegalArgumentException("offsetSec=" + offsetSec);
        if (Double.isNaN(inSec) || Double.isInfinite(inSec) || inSec < 0)
            throw new IllegalArgumentException("inSec=" + inSec);
        if (Double.isNaN(outSec) || Double.isInfinite(outSec) || outSec < 0)
            throw new IllegalArgumentException("outSec=" + outSec);
        if (outSec < inSec)
            throw new IllegalArgumentException("outSec < inSec: " + outSec + " < " + inSec);
        this.inner = inner;
        this.leadInRemaining = Math.round(offsetSec * projectRate);
        this.skipRemaining = Math.round(inSec * projectRate);
        this.serveRemaining = Math.round(outSec * projectRate) - Math.round(inSec * projectRate);
    }

    @Override
    public int read(AudioBuffer b) {
        if (exhausted) return 0;
        int filled = 0;

        while (skipRemaining >= b.frames) {
            int n = inner.read(b);
            if (n == 0) { exhausted = true; return 0; }
            skipRemaining -= n;
        }

        if (skipRemaining > 0) {
            int n = inner.read(b);
            if (n == 0) { exhausted = true; return 0; }
            int take = (int) Math.min((long) n, skipRemaining);
            skipRemaining -= take;
            if (take < n) {
                int c = n - take;
                ensureCarry(b.channels(), c);
                for (int ch = 0; ch < b.channels(); ch++) {
                    System.arraycopy(b.data[ch], take, carryData[ch], 0, c);
                }
                carryLen = c;
            }
        }

        if (leadInRemaining > 0) {
            int l = (int) Math.min(leadInRemaining, (long) (b.frames - filled));
            for (float[] row : b.data) Arrays.fill(row, 0, l, 0f);
            leadInRemaining -= l;
            filled = l;
        }

        if (carryLen > 0) {
            int drain = (int) Math.min((long) carryLen,
                    Math.min(serveRemaining, (long) (b.frames - filled)));
            if (drain > 0) {
                for (int ch = 0; ch < b.channels(); ch++) {
                    System.arraycopy(carryData[ch], 0, b.data[ch], filled, drain);
                    System.arraycopy(carryData[ch], drain, carryData[ch], 0, carryLen - drain);
                }
                carryLen -= drain;
                serveRemaining -= drain;
                filled += drain;
                if (serveRemaining == 0) exhausted = true;
            }
            return filled;
        }

        if (serveRemaining == 0) {
            exhausted = leadInRemaining == 0;
            return filled;
        }
        int n = inner.read(b);
        if (n == 0) { exhausted = true; return filled; }
        int keep = (int) Math.min((long) n,
                Math.min(serveRemaining, (long) (b.frames - filled)));
        if (keep > 0 && filled > 0) {
            for (int ch = 0; ch < b.channels(); ch++) {
                System.arraycopy(b.data[ch], 0, b.data[ch], filled, keep);
            }
        }
        int overshoot = n - keep;
        if (overshoot > 0 && serveRemaining > keep) {
            ensureCarry(b.channels(), carryLen + overshoot);
            for (int ch = 0; ch < b.channels(); ch++) {
                System.arraycopy(b.data[ch], keep, carryData[ch], carryLen, overshoot);
            }
            carryLen += overshoot;
        }
        for (float[] row : b.data) {
            Arrays.fill(row, 0, filled, 0f);
            Arrays.fill(row, filled + keep, b.frames, 0f);
        }
        serveRemaining -= keep;
        filled += keep;
        if (serveRemaining == 0) exhausted = true;
        return filled;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            inner.close();
        }
    }

    private void ensureCarry(int channels, int need) {
        if (carryData == null || carryData.length < channels
                || carryData[0].length < need) {
            int ch = Math.max(channels, carryData != null ? carryData.length : 0);
            int len = Math.max(need, carryData != null ? carryData[0].length : 0);
            carryData = new float[ch][len];
        }
    }
}
