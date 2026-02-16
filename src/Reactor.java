import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * ========================== THE REACTOR ==============================
 *
 * WHAT IS THE REACTOR PATTERN?
 * The Reactor pattern is a design pattern for handling many simultaneous
 * client connections efficiently using a SINGLE thread for I/O monitoring.
 *
 * Traditional approach (thread-per-client):
 *   - Each client gets its own thread
 *   - 1000 clients = 1000 threads = lots of memory and CPU wasted
 *
 * Reactor approach:
 *   - ONE thread (the "selector thread") monitors ALL connections
 *   - When something happens (new connection, data arrives, etc.),
 *     it "reacts" by dispatching the work to a thread pool
 *   - Much more efficient for handling many connections!
 *
 * ANALOGY:
 * Think of a restaurant:
 *   - Thread-per-client = one waiter per table (expensive!)
 *   - Reactor = one host who watches all tables. When a table needs
 *     something, the host tells an available waiter to go help them.
 *
 * HOW THIS CLASS WORKS:
 * 1. Opens a ServerSocketChannel and registers it with a Selector
 * 2. Enters the main "event loop" (the serve() method):
 *    a. selector.select() - BLOCKS until something happens on ANY channel
 *    b. Loops through all events that occurred:
 *       - ACCEPT event: a new client wants to connect -> handleAccept()
 *       - READ event: a client sent data -> handleReadWrite()
 *       - WRITE event: a client is ready to receive data -> handleReadWrite()
 *    c. Runs any tasks queued by worker threads (selector tasks)
 *    d. Goes back to step (a)
 *
 * IMPORTANT COMPONENTS:
 * - Selector: Java NIO class that monitors multiple channels at once
 * - SelectionKey: represents the registration of a channel with a selector,
 *   and tracks what events we're interested in (READ, WRITE, ACCEPT)
 * - ServerSocketChannel: listens for new TCP connections
 * - SocketChannel: represents an individual TCP connection to a client
 * - ActorThreadPool: processes messages without blocking the selector thread
 *
 * FACTORY PATTERN:
 * The Reactor uses "factories" (Supplier objects) to create new protocol
 * and encoder/decoder instances for each client. This way, each client
 * gets its own independent instances (important for per-client state).
 * =====================================================================
 */
public class Reactor<T> implements Server<T> {

    // The TCP port number this server listens on (e.g., 8080)
    private final int port;

    // Factory that creates a new MessagingProtocol for each client.
    // Using Supplier<> means we can call .get() to create a fresh instance.
    // Each client needs its own protocol instance for independent state.
    private final Supplier<MessagingProtocol<T>> protocolFactory;

    // Factory that creates a new MessageEncoderDecoder for each client.
    // Each client needs its own decoder because partial messages are
    // stored inside the decoder (they arrive at different rates per client).
    private final Supplier<MessageEncoderDecoder<T>> readerFactory;

    // The thread pool that processes messages using the Actor Model.
    // Each connection handler is an "actor" - its tasks are serialized
    // but different handlers' tasks run in parallel.
    private final ActorThreadPool<NonBlockingConnectionHandler<T>> pool;

    // The Java NIO Selector - the "heart" of the Reactor.
    // It watches multiple channels and tells us when events happen.
    private Selector selector;

    // Reference to the thread running the selector loop.
    // We need this to check "am I on the selector thread?" when
    // updating interest operations.
    private Thread selectorThread;

    // A queue of tasks that need to run on the selector thread.
    // Worker threads can't modify SelectionKeys directly (not thread-safe),
    // so they put tasks in this queue, and the selector thread runs them.
    // ConcurrentLinkedQueue is used because multiple worker threads might
    // add tasks simultaneously.
    private final ConcurrentLinkedQueue<Runnable> selectorTasks = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new Reactor server.
     *
     * @param numThreads     number of worker threads in the thread pool
     * @param port           TCP port to listen on
     * @param protocolFactory creates a new protocol instance per client
     * @param readerFactory  creates a new encoder/decoder instance per client
     */
    public Reactor(int numThreads,int port, Supplier<MessagingProtocol<T>> protocolFactory, Supplier<MessageEncoderDecoder<T>> readerFactory) {
            this.pool = new ActorThreadPool<>(numThreads);
            this.port = port;
            this.protocolFactory = protocolFactory;
            this.readerFactory = readerFactory;
        }


        /**
         * THE MAIN EVENT LOOP - the core of the Reactor pattern.
         *
         * This method runs forever (until interrupted or closed).
         * It uses a Selector to efficiently wait for events across
         * ALL connected clients simultaneously.
         *
         * The try-with-resources block ensures the Selector and
         * ServerSocketChannel are properly closed when done.
         */
        public void serve() {
            // Remember which thread is running the selector loop.
            // Worker threads need to know this to decide whether they
            // can modify SelectionKeys directly or need to queue a task.
            selectorThread = Thread.currentThread();

            // try-with-resources: automatically closes selector and serverSock
            // when the block exits (even if an exception occurs)
            try ( Selector selector = Selector.open();
                ServerSocketChannel serverSock = ServerSocketChannel.open()) {

                    // Store the selector so close() can access it
                    this.selector = selector; //just to be able to close

                    // Bind to the specified port - start listening for connections
                    serverSock.bind(new InetSocketAddress(port));
                    // CRUCIAL: set to non-blocking mode. Without this, accept()
                    // would block the entire event loop!
                    serverSock.configureBlocking(false);
                    // Tell the selector: "notify me when a new client wants to connect"
                    // OP_ACCEPT = we're interested in new connection events
                    serverSock.register(selector, SelectionKey.OP_ACCEPT);

            // ==================== THE EVENT LOOP ====================
            // This loop is the beating heart of the Reactor pattern.
            // It runs continuously, waiting for events and dispatching them.
            while (!Thread.currentThread().isInterrupted()) {

                // BLOCK here until at least one channel has an event.
                // This is what makes the Reactor efficient - instead of
                // busy-waiting or polling, we sleep until something happens.
                selector.select();

                // Run any tasks that worker threads queued for us
                // (e.g., "please change my interest ops to include WRITE")
                runSelectionThreadTasks();

                // Process all events that occurred
                for (SelectionKey key : selector.selectedKeys()) {
                    if (!key.isValid()) {
                        // Channel was closed - skip it
                        continue;
                    } else if (key.isAcceptable()) {
                        // A new client wants to connect!
                        handleAccept(serverSock, selector);
                    } else {
                        // An existing client has data to read or is ready for writes
                        handleReadWrite(key);
                    }
                }
                // IMPORTANT: we must clear the selected keys set manually.
                // The Selector adds to it but never removes - that's our job.
                selector.selectedKeys().clear();
            }
            // ========================================================

            } catch (ClosedSelectorException ex) {
            //do nothing - server was requested to be closed
            } catch (IOException ex) {
            //this is an error
                ex.printStackTrace();
            }

            System.out.println("server closed!!!");
            // Clean up the thread pool when the server stops
            pool.shutdown();
        }

    /**
     * Updates what events we're interested in for a given channel.
     *
     * WHY IS THIS NEEDED?
     * When a handler has data to send, it needs to tell the Reactor:
     * "Hey, I now want to be notified when the client is writable."
     * This is done by changing the "interest ops" of the SelectionKey.
     *
     * THE THREADING PROBLEM:
     * SelectionKeys are NOT thread-safe. Only the selector thread
     * should modify them. But worker threads (from the ActorThreadPool)
     * need to request changes. So:
     *   - If we're already on the selector thread: just do it directly
     *   - If we're on a worker thread: queue the change as a task
     *     and wake up the selector so it processes the queue
     *
     * @param chan the channel whose interest ops we want to change
     * @param ops  the new interest operations (e.g., OP_READ | OP_WRITE)
     */
    void updateInterestedOps(SocketChannel chan, int ops) {
        // Find this channel's SelectionKey in our selector
        final SelectionKey key = chan.keyFor(selector);
        if (Thread.currentThread() == selectorThread) {
            // We're on the selector thread - safe to modify directly
            key.interestOps(ops);
        } else {
            // We're on a worker thread - queue the change
            selectorTasks.add(() -> {
                if(key.isValid())
                    key.interestOps(ops);
            });
            // Wake up the selector from its select() call so it
            // processes our queued task
            selector.wakeup();
        }
    }

    /**
     * Handles a new client connection (ACCEPT event).
     *
     * When a new client connects:
     * 1. Accept the connection (creates a SocketChannel)
     * 2. Set the new channel to non-blocking mode
     * 3. Create a new handler with fresh protocol and encoder instances
     * 4. Register the channel with the selector for READ events
     *    (we want to know when this client sends data)
     * 5. Attach the handler to the key so we can find it later
     *
     * @param serverChan the server channel that received the connection
     * @param selector   the selector to register the new channel with
     */
    private void handleAccept(ServerSocketChannel serverChan,Selector selector) throws IOException {
        // Accept the new client - creates a channel for this specific client
        SocketChannel clientChan = serverChan.accept();
        // Must be non-blocking to work with the selector
        clientChan.configureBlocking(false);
        // Create a new handler with FRESH protocol and decoder instances.
        // readerFactory.get() and protocolFactory.get() create new instances
        // so each client has independent state.
        final NonBlockingConnectionHandler<T> handler = new
            NonBlockingConnectionHandler<>(
                readerFactory.get(),
                protocolFactory.get(),
                clientChan,this);
        // Register with selector: we want READ events, and attach the handler
        // so when a READ event fires, we can find the right handler
        clientChan.register(selector, SelectionKey.OP_READ, handler);
    }

    /**
     * Handles READ and WRITE events for an existing client connection.
     *
     * Gets the handler that was attached to the key during handleAccept(),
     * then delegates the work:
     *   - READ: asks the handler to read data and get a processing task,
     *     then submits that task to the ActorThreadPool
     *   - WRITE: asks the handler to send queued responses
     *
     * Note: a key can be both readable AND writable at the same time,
     * so we check both conditions (not if/else).
     *
     * @param key the SelectionKey for the channel with events
     * 
     */
    @SuppressWarnings("unchecked") // because of the cast to NonBlockingConnectionHandler<T>
    private void handleReadWrite(SelectionKey key) {
        // Get the handler we attached to this key in handleAccept()
        NonBlockingConnectionHandler<T> handler =
        (NonBlockingConnectionHandler<T>) key.attachment();

        if (key.isReadable()) {
            // Client sent data - ask the handler to read it
            // continueRead() returns a Runnable task for processing
            Runnable task = handler.continueRead();
            if (task != null) {
                // Submit the task to the thread pool.
                // The handler IS the actor - ensures tasks for the same
                // client are executed in order.
                pool.submit(handler, task);
            } else if (!key.isValid()) {
                // Client disconnected during read - nothing more to do
                return;
            }
        }

        if (key.isWritable()) {
            // Client is ready to receive data - send queued responses
            handler.continueWrite();
        }
    }

    /**
     * Runs all tasks that worker threads have queued for the selector thread.
     * These are typically updateInterestedOps changes that need to happen
     * on the selector thread because SelectionKeys aren't thread-safe.
     */
    private void runSelectionThreadTasks() {
        while (!selectorTasks.isEmpty()) {
            selectorTasks.remove().run();
        }
        }

    /**
     * Closes the server by closing the selector.
     * This causes selector.select() in the event loop to throw
     * ClosedSelectorException, which breaks the loop and shuts
     * everything down.
     */
    public void close() throws IOException {
        selector.close();
    }
}
