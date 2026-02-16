/**
 * =================== CONNECTION HANDLER INTERFACE ====================
 *
 * WHAT IS THIS?
 * This interface defines what a "connection handler" must be able to do.
 * A connection handler is responsible for managing a single client
 * connection - reading data from it, processing messages, and
 * sending responses back.
 *
 * WHY DO WE NEED IT?
 * In the Reactor pattern, the Reactor itself does NOT read/write data
 * directly. Instead, it delegates that work to connection handlers.
 * Each connected client gets its own handler. This interface defines
 * the contract that all handlers must follow.
 *
 * The generic type <T> represents the message type.
 * For example, ConnectionHandler<String> handles String messages.
 *
 * HOW DOES IT FIT IN THE REACTOR PATTERN?
 * When the Reactor detects that a client has sent data (a "read" event),
 * it asks the connection handler to read and process that data.
 * When the Reactor detects that a client is ready to receive data
 * (a "write" event), it asks the handler to send queued responses.
 *
 * The NonBlockingConnectionHandler class implements this interface
 * using Java NIO (non-blocking I/O).
 * =====================================================================
 */
public interface ConnectionHandler<T> {

    /**
     * Called when data is available to read from the client.
     * Returns a Runnable task that, when executed, will decode the
     * incoming bytes into messages and process them using the protocol.
     *
     * Why return a Runnable instead of doing the work directly?
     * Because the Reactor's selector thread should NOT do heavy
     * processing - it needs to stay free to monitor ALL connections.
     * So we package the work as a Runnable and hand it off to the
     * thread pool to execute later.
     *
     * @return a Runnable task to process the read data, or null
     *         if the connection was closed
     */
    Runnable continueRead();

    /**
     * Called when the client is ready to receive data.
     * This method writes queued response messages back to the client.
     * Unlike continueRead(), this runs directly on the selector thread
     * because writing is usually fast and non-blocking.
     */
    void continueWrite();

    /**
     * Closes this connection and releases associated resources.
     * Called when the client disconnects or when the protocol
     * decides to terminate the connection (e.g., client sent "bye").
     */
    void close();
}
