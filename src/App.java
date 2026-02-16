/**
 * ========================= APPLICATION ENTRY POINT ===================
 *
 * WHAT IS THIS?
 * This is the main class - the starting point of the program.
 * Currently it just prints "Hello, World!" but it should be updated
 * to create and start a Reactor server.
 *
 * HOW THE WHOLE SYSTEM FITS TOGETHER:
 * Here's a map of all the classes and how they relate:
 *
 *   App (you are here)
 *    |
 *    |-- Creates a Reactor (the main server)
 *         |
 *         |-- Uses a Selector to monitor all connections
 *         |-- Uses ActorThreadPool to process messages in parallel
 *         |-- For each new client connection, creates:
 *              |
 *              |-- NonBlockingConnectionHandler (manages one client)
 *                   |
 *                   |-- Uses MessageEncoderDecoder (bytes <-> messages)
 *                   |      (implemented by LineMessageEncoderDecoder)
 *                   |
 *                   |-- Uses MessagingProtocol (processes messages)
 *                          (implemented by EchoProtocol)
 *
 * INTERFACES vs IMPLEMENTATIONS:
 *   Server           <-- interface, implemented by Reactor
 *   ConnectionHandler <-- interface, implemented by NonBlockingConnectionHandler
 *   MessageEncoderDecoder <-- interface, implemented by LineMessageEncoderDecoder
 *   MessagingProtocol     <-- interface, implemented by EchoProtocol
 *
 * TO ACTUALLY START THE SERVER, this main method should look like:
 *   Reactor<String> reactor = new Reactor<>(
 *       4,                              // 4 worker threads
 *       8080,                           // listen on port 8080
 *       () -> new EchoProtocol(),       // protocol factory
 *       () -> new LineMessageEncoderDecoder()  // encoder factory
 *   );
 *   reactor.serve();  // starts the event loop (blocks forever)
 * =====================================================================
 */
public class App {
    public static void main(String[] args) throws Exception {
        System.out.println("Hello, World!");
    }
}
