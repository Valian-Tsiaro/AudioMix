package audiomix.io;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates JavaSound audio devices. Never throws: on headless systems
 * or machines without audio hardware the lists are empty and
 * {@link #defaultOutput()} returns {@code null}.
 */
public final class DeviceManager {

    /**
     * Lists mixers that can open a SourceDataLine (playback devices).
     *
     * @return possibly-empty list of output devices
     */
    public List<DeviceInfo> listOutputs() {
        return list(true);
    }

    /**
     * Lists mixers that can open a TargetDataLine (capture devices).
     *
     * @return possibly-empty list of input devices
     */
    public List<DeviceInfo> listInputs() {
        return list(false);
    }

    /**
     * Returns the best-effort system default output device: the first
     * enumerated SourceDataLine-capable mixer (the JavaSound default is
     * conventionally listed first). On multi-output systems this may
     * not match the OS default.
     *
     * @return default output, or null if no device is available
     */
    public DeviceInfo defaultOutput() {
        List<DeviceInfo> outputs = list(true);
        return outputs.isEmpty() ? null : outputs.get(0);
    }

    private static List<DeviceInfo> list(boolean output) {
        List<DeviceInfo> devices = new ArrayList<>();
        Class<? extends DataLine> kind = output ? SourceDataLine.class : TargetDataLine.class;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mi);
            try {
                if (mixer.isLineSupported(new DataLine.Info(kind, null))) {
                    devices.add(infoFor(mixer, output));
                }
            } catch (IllegalArgumentException e) {
                // uninitialized mixer: skip
            }
        }
        return devices;
    }

    private static DeviceInfo infoFor(Mixer mixer, boolean output) {
        return new DeviceInfo(
                mixer.getMixerInfo().getName(),
                mixer.getMixerInfo().getVendor() + " - " + mixer.getMixerInfo().getDescription(),
                !output,
                mixer.getMixerInfo());
    }
}
