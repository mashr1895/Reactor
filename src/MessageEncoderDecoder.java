/**
 * ================= MESSAGE ENCODER/DECODER INTERFACE ==================
 *
 * WHAT IS THIS?
 * This is an interface (a contract) that defines how raw bytes from
 * the network get converted into meaningful messages (decoding), and
 * how messages get converted back into raw bytes to send over the
 * network (encoding).
 *
 * WHY DO WE NEED IT?
 * When data travels over a network, it's just a stream of bytes.
 * We need a way to figure out where one message ends and another
 * begins. Different protocols use different rules:
 *   - Line-based: messages end with '\n' (newline)
 *   - Length-prefixed: first 4 bytes tell you the message size
 *   - Fixed-size: every message is exactly N bytes
 *
 * This interface lets us swap different encoding strategies without
 * changing the rest of the server code.
 *
 * HOW DOES IT FIT IN THE REACTOR PATTERN?
 * The Reactor reads raw bytes from the network. Those bytes are
 * passed one-by-one to decodeNextByte(). When a complete message
 * is assembled, the decoder returns it. Then the protocol processes
 * it, produces a response, and encode() converts that response
 * back into bytes to send to the client.
 *
 * The generic type <T> represents the message type.
 * For example, MessageEncoderDecoder<String> works with String messages.
 *
 * FLOW:
 *   [Network bytes] --> decodeNextByte() --> [Complete message of type T]
 *   [Response of type T] --> encode() --> [Network bytes]
 * =====================================================================
 */
public interface MessageEncoderDecoder<T> {

    /**
     * Receives a single byte from the network and tries to decode it.
     *
     * Why one byte at a time? Because in non-blocking I/O, we might
     * receive partial messages. By processing byte-by-byte, we can
     * handle any amount of data without needing the full message
     * up front. The decoder accumulates bytes internally until a
     * complete message is formed.
     *
     * @param nextByte the next byte read from the network
     * @return a complete decoded message if one is ready,
     *         or null if we need more bytes to complete the message
     */
    T decodeNextByte(byte nextByte);

    /**
     * Encodes a message into bytes so it can be sent over the network.
     *
     * @param message the message to encode
     * @return the byte array representing this message on the wire
     */
    byte[] encode(T message);
}
