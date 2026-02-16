import java.time.LocalDateTime;

/**
 * ======================= ECHO PROTOCOL ===============================
 *
 * WHAT IS THIS?
 * A simple implementation of MessagingProtocol that "echoes" messages
 * back to the client. Whatever the client sends, this protocol sends
 * it back with a fun echo effect.
 *
 * For example, if the client sends "hello", the server responds with:
 *   "hello .. lo .. lo .."
 * (It takes the last 2 characters and repeats them as an "echo".)
 *
 * WHY IS THIS USEFUL?
 * Echo protocols are great for testing! They let you verify that
 * the entire pipeline works:
 *   client sends data -> Reactor receives it -> decoder builds message
 *   -> protocol processes it -> encoder converts response -> client
 *      receives the echo
 *
 * WHEN DOES THE CONNECTION END?
 * When the client sends the message "bye", the protocol sets a flag
 * to terminate the connection. After the echo response is sent, the
 * server will close this client's connection.
 *
 * Each client gets its OWN EchoProtocol instance, so the
 * shouldTerminate flag is per-client (one client saying "bye"
 * doesn't disconnect other clients).
 * =====================================================================
 */
public class EchoProtocol implements MessagingProtocol<String> {

    // Flag to track whether this client wants to disconnect.
    // Starts as false - we only set it to true when the client sends "bye".
    private boolean shouldTerminate = false;

    /**
     * Processes an incoming message from the client.
     * 1. Checks if the message is "bye" (if so, marks connection for closing)
     * 2. Logs the message with a timestamp
     * 3. Creates and returns an echo response
     *
     * @param msg the message received from the client
     * @return the echo response to send back
     */
    @Override
    public String process(String msg) {
        // If the client sent "bye", we should close the connection after responding
        shouldTerminate = "bye".equals(msg);
        // Log the message to the server console with a timestamp
        System.out.println("[" + LocalDateTime.now() + "]: " + msg);
        // Create and return the echo response
        return createEcho(msg);
    }

    /**
     * Creates the echo effect by taking the last 2 characters of the
     * message and repeating them.
     *
     * Example: "hello" -> echoPart = "lo"
     *          Result: "hello .. lo .. lo .."
     *
     * If the message is shorter than 2 characters (e.g., "a"),
     * it uses whatever is available (e.g., echoPart = "a").
     *
     * @param message the original message
     * @return the message with echo effect appended
     */
    private String createEcho(String message) {
        // Get the last 2 characters (or fewer if the message is short)
        // Math.max ensures we don't get a negative index for short messages
        String echoPart = message.substring(
        Math.max(message.length() - 2, 0), message.length());
        // Build the echo: "message .. lastTwo .. lastTwo .."
        return message + " .. " + echoPart + " .. " + echoPart + " ..";
    }

    /**
     * Tells the server whether this connection should be closed.
     * Returns true only after the client has sent "bye".
     */
    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}
