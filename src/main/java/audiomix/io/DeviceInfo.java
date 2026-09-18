package audiomix.io;

import javax.sound.sampled.Mixer;

/**
 * Immutable audio device descriptor: a JavaSound mixer that can open a
 * SourceDataLine (output) or a TargetDataLine (input). Instances with a
 * {@code null} {@link #getMixerInfo() mixer info} refer to the
 * system-default device; {@link DeviceManager} creates device-bound
 * instances on systems that have hardware.
 */
public final class DeviceInfo {

    private final String name;
    private final String description;
    private final boolean input;
    private final Mixer.Info mixerInfo;

    /**
     * Creates a logical device descriptor. No hardware is contacted.
     *
     * @param name        short display name
     * @param description human-readable description
     * @param isInput     true for an input (capture) device
     */
    public DeviceInfo(String name, String description, boolean isInput) {
        this(name, description, isInput, null);
    }

    DeviceInfo(String name, String description, boolean isInput, Mixer.Info mixerInfo) {
        this.name = name;
        this.description = description;
        this.input = isInput;
        this.mixerInfo = mixerInfo;
    }

    /** Returns the short display name. */
    public String getName() { return name; }

    /** Returns the human-readable description. */
    public String getDescription() { return description; }

    /** True for an input (capture) device. */
    public boolean isInput() { return input; }

    /**
     * Underlying JavaSound mixer identity, or {@code null} for the
     * system default device.
     *
     * @return mixer info or null
     */
    public Mixer.Info getMixerInfo() { return mixerInfo; }
}
