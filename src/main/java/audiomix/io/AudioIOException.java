package audiomix.io;

/**
 * Runtime exception thrown on malformed or unsupported WAV files,
 * I/O errors during read/write, or invalid constructor arguments.
 */
public class AudioIOException extends RuntimeException {

    /**
     * @param message description of the error
     */
    public AudioIOException(String message) {
        super(message);
    }

    /**
     * @param message description of the error
     * @param cause   underlying I/O or parse exception
     */
    public AudioIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
