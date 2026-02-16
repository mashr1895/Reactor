import java.io.IOException;

/**
 * ========================== SERVER INTERFACE ==========================
 *
 * WHAT IS THIS?
 * This is the top-level interface for any server in the Reactor pattern.
 * Think of it as a "contract" - any class that says "I am a Server"
 * must provide these methods.
 *
 * WHY DO WE NEED IT?
 * In the Reactor pattern, the Reactor class IS a server. But we want
 * to define the idea of "what a server can do" separately from
 * "how the Reactor does it". This way, if we ever want a different
 * type of server (e.g., a thread-per-client server), it can also
 * implement this same interface.
 *
 * The generic type <T> represents the type of messages this server
 * handles. For example, Server<String> handles String messages.
 *
 * HOW DOES IT FIT IN THE REACTOR PATTERN?
 * The Reactor class implements this interface. The serve() method
 * starts the main event loop (the "heart" of the Reactor pattern),
 * and close() shuts everything down gracefully.
 * =====================================================================
 */
public interface Server<T> {

    /**
     * Starts the server.
     * This method begins listening for incoming connections and
     * processing events. In the Reactor pattern, this kicks off
     * the main "event loop" that waits for things to happen
     * (new connections, data arriving, etc.) and dispatches them.
     *
     * This method typically blocks - meaning it runs forever
     * (or until the server is told to stop).
     */
    void serve();

    /**
     * Stops the server and releases all resources.
     * This closes the selector, all open connections, and shuts
     * down the thread pool.
     *
     * @throws IOException if an I/O error occurs while closing
     */
    void close() throws IOException;
}
