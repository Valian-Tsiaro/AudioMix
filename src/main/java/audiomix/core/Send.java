package audiomix.core;

/**
 * A single send routing from a {@link Channel} to an {@link AuxBus}.
 * Level is smoothed over ~10 ms; pre- / post-fader tap is a boolean gate.
 */
final class Send {

    final AuxBus bus;
    final ParamSmoother level;
    volatile boolean preFader;

    Send(AuxBus bus, int sampleRate, double level) {
        this.bus = bus;
        this.level = new ParamSmoother(sampleRate, 10.0);
        this.level.setValue(level);
    }
}
