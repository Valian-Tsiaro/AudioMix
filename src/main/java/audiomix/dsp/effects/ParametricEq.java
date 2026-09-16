package audiomix.dsp.effects;

import audiomix.core.AudioBuffer;
import audiomix.core.Effect;
import audiomix.dsp.Biquad;
import audiomix.dsp.EqType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * N-band parametric equalizer. Each band owns one {@link Biquad} per
 * processed channel, created lazily as needed. Band parameters are
 * mutable at any time; coefficients are recomputed at the start of
 * each {@link #process} block when a band is dirty.
 */
public final class ParametricEq implements Effect {

    private final int sampleRate;
    private final CopyOnWriteArrayList<Band> bands = new CopyOnWriteArrayList<>();

    /**
     * Creates an EQ at the given project sample rate.
     *
     * @param sampleRate project sample rate in Hz, must be positive
     * @throws IllegalArgumentException if sampleRate <= 0
     */
    public ParametricEq(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate=" + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    /**
     * Adds a new band.
     *
     * @param type    filter shape (must not be null)
     * @param freq    center frequency in Hz, exclusive of 0 and Nyquist
     * @param gainDb  boost or cut in dB
     * @param q       quality factor, must be positive
     * @return the new band
     * @throws IllegalArgumentException if freq or q out of range, or gainDb not finite
     */
    public Band addBand(EqType type, double freq, double gainDb, double q) {
        Band band = new Band(sampleRate, type, freq, gainDb, q);
        bands.add(band);
        return band;
    }

    /**
     * Fluent alias for {@link #addBand(EqType, double, double, double)}.
     *
     * @return {@code this}
     */
    public ParametricEq band(EqType type, double freq, double gainDb, double q) {
        addBand(type, freq, gainDb, q);
        return this;
    }

    /**
     * Removes a band.
     *
     * @return true if the band was present
     */
    public boolean removeBand(Band b) {
        return bands.remove(b);
    }

    /** Removes all bands. */
    public void clearBands() {
        bands.clear();
    }

    /**
     * Returns an unmodifiable snapshot of the current bands.
     */
    public List<Band> getBands() {
        return List.copyOf(bands);
    }

    @Override
    public void process(AudioBuffer b) {
        int ch = b.channels();

        for (Band band : bands) {
            if (band.needsReconfigure()) {
                do {
                    band.clearDirty();
                    for (Biquad bq : band.biquads) {
                        bq.configure(band.type, band.freq, band.gainDb, band.q, sampleRate);
                        bq.reset(); // ponytail: resets state on reconfigure — trades minor click for determinism
                    }
                } while (band.needsReconfigure());
            }

            // ponytail: lazy grow on audio path — spec-mandated for mono→stereo
            while (band.biquads.size() < ch) {
                Biquad bq = new Biquad();
                bq.configure(band.type, band.freq, band.gainDb, band.q, sampleRate);
                band.biquads.add(bq);
            }
        }

        for (int c = 0; c < ch; c++) {
            float[] data = b.data[c];
            for (Band band : bands) {
                Biquad bq = band.biquads.get(c);
                for (int i = 0; i < b.frames; i++) {
                    data[i] = bq.process(data[i]);
                }
            }
        }
    }

    public static final class Band {
        private final int sampleRate;
        private final EqType type;
        private volatile double freq;
        private volatile double gainDb;
        private volatile double q;
        private volatile boolean dirty = true;
        final List<Biquad> biquads = new CopyOnWriteArrayList<>();

        private Band(int sampleRate, EqType type, double freq, double gainDb, double q) {
            if (type == null) {
                throw new IllegalArgumentException("type is null");
            }
            this.sampleRate = sampleRate;
            this.type = type;
            this.freq = freq;
            this.gainDb = gainDb;
            this.q = q;
            validate(freq, q);
            validateGainDb(gainDb);
        }

        /**
         * Sets center frequency in Hz, exclusive of 0 and Nyquist.
         *
         * @throws IllegalArgumentException if out of range
         */
        public void setFreq(double f) {
            validateFreq(f);
            this.freq = f;
            this.dirty = true;
        }

        /** Center frequency in Hz. */
        public double getFreq() {
            return freq;
        }

        /**
         * Sets boost or cut in dB.
         * @param g gain in dB, must be finite (not NaN or infinity)
         * @throws IllegalArgumentException if g is not finite
         */
        public void setGainDb(double g) {
            validateGainDb(g);
            this.gainDb = g;
            this.dirty = true;
        }

        /** Gain in dB. */
        public double getGainDb() {
            return gainDb;
        }

        /**
         * Sets quality factor, must be positive.
         *
         * @throws IllegalArgumentException if q <= 0
         */
        public void setQ(double q) {
            validateQ(q);
            this.q = q;
            this.dirty = true;
        }

        /** Quality factor. */
        public double getQ() {
            return q;
        }

        /** Filter shape. */
        public EqType getType() {
            return type;
        }

        boolean needsReconfigure() {
            return dirty;
        }

        void clearDirty() {
            this.dirty = false;
        }

        private void validate(double freq, double q) {
            validateFreq(freq);
            validateQ(q);
        }

        private void validateGainDb(double g) {
            if (!Double.isFinite(g)) {
                throw new IllegalArgumentException("gainDb=" + g);
            }
        }

        private void validateFreq(double f) {
            if (!(f > 0.0 && f < sampleRate / 2.0)) {
                throw new IllegalArgumentException("freq=" + f);
            }
        }

        private void validateQ(double q) {
            if (!(q > 0.0)) {
                throw new IllegalArgumentException("q=" + q);
            }
        }
    }
}
