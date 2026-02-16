/**
 * =================== MESSAGING PROTOCOL INTERFACE ====================
 *
 * WHAT IS THIS?
 * This interface defines the "business logic" of our server - i.e.,
 * what should happen when a message arrives from a client.
 *
 * Think of it this way:
 *   - The Reactor handles the "plumbing" (network I/O, connections)
 *   - The EncoderDecoder handles the "translation" (bytes <-> messages)
 *   - The Protocol handles the "brains" (what to DO with each message)
 *
 * WHY DO WE NEED IT?
 * We want to separate "how we communicate" from "what we communicate".
 * The same Reactor server can run different protocols just by swapping
 * this interface's implementation. For example:
 *   - EchoProtocol: echoes back whatever the client says
 *   - ChatProtocol: routes messages between users
 *   - GameProtocol: processes game commands
 *
 * HOW DOES IT FIT IN THE REACTOR PATTERN?
 * After the EncoderDecoder assembles a complete message from bytes,
 * that message is passed to process(). The protocol decides what to
 * do with it and returns a response (or null if no response is needed).
 * The response is then encoded back to bytes and sent to the client.
 *
 * FLOW:
 *   [Decoded message] --> process() --> [Response message or null]
 *
 * Each client gets its OWN protocol instance, so the protocol can
 * safely store per-client state (like whether to terminate).
 * =====================================================================
 */
public interface MessagingProtocol<T> {

    /**
     * Processes an incoming message from a client and produces a response.
     *
     * @param msg the message received from the client
     * @return a response message to send back, or null if no response
     */
    public T process(T msg);

    /**
     * Checks whether this connection should be closed.
     * Called after each message is processed and sent. If this returns
     * true, the server will close the connection to this client.
     *
     * @return true if the connection should be terminated
     */
    public boolean shouldTerminate();
}
