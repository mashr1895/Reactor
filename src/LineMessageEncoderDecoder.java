import java.util.Arrays;
import java.nio.charset.StandardCharsets;

/**
 * ============== LINE-BASED MESSAGE ENCODER/DECODER ===================
 *
 * WHAT IS THIS?
 * A concrete implementation of MessageEncoderDecoder that treats each
 * line of text (ending with '\n') as a separate message.
 * This is one of the simplest encoding schemes - it's the same way
 * protocols like HTTP/1.0 headers and SMTP work.
 *
 * HOW DOES DECODING WORK?
 * Bytes arrive one at a time (from the network). We store them in a
 * growing byte array. When we see a newline character '\n', we know
 * a complete message has arrived - so we convert all the accumulated
 * bytes into a String and return it.
 *
 * Example: if the client sends "hello\n", we receive:
 *   'h' -> null (not done yet)
 *   'e' -> null
 *   'l' -> null
 *   'l' -> null
 *   'o' -> null
 *   '\n' -> "hello" (complete message!)
 *
 * HOW DOES ENCODING WORK?
 * To send a message, we simply append '\n' to the string and convert
 * it to bytes. The receiver will know the message is complete when
 * it sees the '\n'.
 *
 * This class handles String messages, so it implements
 * MessageEncoderDecoder<String>.
 * =====================================================================
 */
public class LineMessageEncoderDecoder implements MessageEncoderDecoder<String> {

    // A byte array that accumulates incoming bytes until a full line is received.
    // Starts at 1024 bytes (1 << 10 = 2^10 = 1024). Will grow if needed.
    private byte[] bytes = new byte[1 << 10]; //start with 1k

    // Tracks how many bytes we've accumulated so far in the 'bytes' array.
    private int len = 0;

    /**
     * Called for each byte received from the network.
     * If the byte is a newline '\n', it means the message is complete,
     * so we convert all accumulated bytes into a String and return it.
     * Otherwise, we store the byte and return null (meaning: "keep
     * feeding me more bytes, the message isn't done yet").
     */
    @Override
    public String decodeNextByte(byte nextByte) {
        // If we hit a newline, the line/message is complete
        if (nextByte == '\n') {
            return popString();
        }
        // Otherwise, add this byte to our buffer and wait for more
        pushByte(nextByte);
        return null; //not a line yet
    }

    /**
     * Encodes a String message into bytes for sending over the network.
     * Simply appends a newline character so the receiver knows where
     * the message ends.
     */
    @Override
    public byte[] encode(String message) {
        return (message + "\n").getBytes(); //uses utf8 by default
    }

    /**
     * Adds a byte to the internal buffer. If the buffer is full,
     * it doubles in size (this is called "dynamic array" or
     * "amortized doubling" - a common technique for growing arrays
     * efficiently).
     */
    private void pushByte(byte nextByte) {
        // If our buffer is full, double its size
        if (len >= bytes.length) {
            bytes = Arrays.copyOf(bytes, len * 2);
        }
        // Store the byte and advance our position
        bytes[len++] = nextByte;
    }

    /**
     * Converts the accumulated bytes into a String and resets the buffer.
     * Called when we've received a complete line (hit a '\n').
     *
     * @return the complete line as a String (without the '\n')
     */
    private String popString() {
        // Convert the bytes we've collected into a UTF-8 String
        String result = new String(bytes, 0, len, StandardCharsets.UTF_8);
        // Reset the counter so we can start accumulating the next message
        len = 0;
        return result;
    }
}
