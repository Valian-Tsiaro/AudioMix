package audiomix.source;

import javax.sound.sampled.TargetDataLine;

import audiomix.io.DeviceInfo;

/**
 * Opens a TargetDataLine for a capture device; lets tests inject fakes.
 * Implementations open the line at the exact rate/channels or throw
 * {@link audiomix.io.AudioIOException}.
 */
public interface TargetDataLineFactory {

    /**
     * Opens the capture line.
     *
     * @param d           device (or null for system default input)
     * @param rate        sample rate in Hz
     * @param ch          channel count
     * @param preferFloat true to request PCM_FLOAT, false for PCM_SIGNED
     * @return open, unstarted line accepting the exact format
     * @throws audiomix.io.AudioIOException if the OS refuses the format
     */
    TargetDataLine open(DeviceInfo d, int rate, int ch, boolean preferFloat);
}
