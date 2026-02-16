import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ============= NON-BLOCKING CONNECTION HANDLER =======================
 *
 * WHAT IS THIS?
 * This class manages a single client connection using non-blocking I/O.
 * Each connected client gets its own NonBlockingConnectionHandler.
 * It knows how to:
 *   1. Read data from the client (continueRead)
 *   2. Decode bytes into messages (using the encdec)
 *   3. Process messages through the protocol (using the protocol)
 *   4. Send responses back to the client (continueWrite)
 *
 * WHY "NON-BLOCKING"?
 * In traditional (blocking) I/O, when you call read(), your thread
 * WAITS until data arrives. If you have 1000 clients, you'd need
 * 1000 threads - very wasteful!
 *
 * In non-blocking I/O, read() returns immediately - either with data
 * or with "nothing available right now". This lets ONE thread monitor
 * MANY connections using a Selector (see Reactor.java).
 *
 * HOW DOES IT FIT IN THE REACTOR PATTERN?
 * The Reactor monitors all connections using a Selector. When it
 * detects that a client has sent data, it calls continueRead() on
 * that client's handler. The handler reads the bytes, but instead
 * of processing them right away (which would block the Reactor),
 * it returns a Runnable task. The Reactor then submits this task
 * to the ActorThreadPool, where a worker thread processes it.
 *
 * For writing, the Reactor calls continueWrite() which sends any
 * queued responses back to the client.
 *
 * BUFFER POOL OPTIMIZATION:
 * Instead of creating a new ByteBuffer every time we read, we reuse
 * buffers from a shared pool. This avoids garbage collection overhead
 * and improves performance. Think of it like a library - you borrow
 * a buffer, use it, and return it when done.
 *
 * BUG NOTE: This class has a syntax error in its declaration!
 * =====================================================================
 */
public class NonBlockingConnectionHandler<T> implementsConnectionHandler<T> {

    // Size of each buffer: 8KB (1 << 13 = 2^13 = 8192 bytes).
    // This is enough for most messages without wasting too much memory.
    private static final int BUFFER_ALLOCATION_SIZE = 1 << 13; //8k

    // A shared pool of reusable ByteBuffers (shared across ALL handlers).
    // "static" means all connection handlers share the same pool.
    // ConcurrentLinkedQueue is thread-safe, so multiple threads can
    // borrow and return buffers simultaneously.
    private static final ConcurrentLinkedQueue<ByteBuffer> BUFFER_POOL = new ConcurrentLinkedQueue<>();

    // The protocol that defines how to process messages (e.g., EchoProtocol)
    private final MessagingProtocol<T> protocol;

    // The encoder/decoder that converts between bytes and messages
    private final MessageEncoderDecoder<T> encdec;

    // Queue of ByteBuffers containing response data waiting to be sent.
    // When we process a message and generate a response, the encoded
    // response bytes go here until the Reactor says "you can write now".
    private final Queue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    // The actual network channel to this client.
    // SocketChannel is Java NIO's way of representing a TCP connection.
    private final SocketChannel chan;

    // Reference to the Reactor, so we can tell it to update what events
    // we're interested in (e.g., "I now have data to write, please
    // notify me when the client is ready to receive").
    private final Reactor reactor;

    /**
     * Creates a new handler for a client connection.
     *
     * @param reader   the encoder/decoder for this connection's messages
     * @param protocol the protocol for processing this connection's messages
     * @param chan     the network channel to the client
     * @param reactor  the Reactor managing this connection
     */
    public NonBlockingConnectionHandler(
    MessageEncoderDecoder<T> reader,
    MessagingProtocol<T> protocol,
    SocketChannel chan,
    Reactor reactor) {
    this.chan = chan;
    this.encdec = reader;
    this.protocol = protocol;
    this.reactor = reactor;
    }

    /**
     * Reads data from the client and returns a task to process it.
     *
     * This method is called by the Reactor's selector thread when data
     * is available. It does the MINIMUM work needed on the selector thread:
     *   1. Borrows a buffer from the pool
     *   2. Reads raw bytes from the network into the buffer
     *   3. Returns a Runnable that will do the heavy processing later
     *
     * The Runnable (which runs on a worker thread) does:
     *   1. Feeds each byte to the decoder
     *   2. When a complete message is decoded, passes it to the protocol
     *   3. If the protocol produces a response, queues it for writing
     *   4. Tells the Reactor "I have data to write" by updating interest ops
     *
     * @return a Runnable task to process the read data, or null if
     *         the client disconnected
     */
    public Runnable continueRead() {
        // Borrow a buffer from the shared pool (or create a new one)
        ByteBuffer buf = leaseBuffer();

        boolean success = false;
        try {
            // Try to read data from the client.
            // chan.read(buf) returns the number of bytes read, or -1 if
            // the client closed the connection.
            success = chan.read(buf) != -1;
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (success) {
            // flip() switches the buffer from "writing mode" (we wrote into it)
            // to "reading mode" (we'll now read from it). It sets the limit to
            // the current position and resets position to 0.
            buf.flip();

            // Return a Runnable that processes the data on a worker thread.
            // This is a lambda expression - it creates an anonymous Runnable.
            return () -> {
                try {
                    // Go through each byte we received
                    while (buf.hasRemaining()) {
                        // Feed each byte to the decoder. It returns null until
                        // it has a complete message (e.g., hit a newline).
                        T nextMessage = encdec.decodeNextByte(buf.get());
                        if (nextMessage != null) {
                            // We have a complete message! Process it.
                            T response = protocol.process(nextMessage);
                            if (response != null) {
                                // Encode the response and add to write queue
                                writeQueue.add(ByteBuffer.wrap(encdec.encode(response)));
                                // Tell the Reactor: "I now have data to write,
                                // please notify me when I can write"
                                // OP_READ | OP_WRITE means: notify me for both
                                // readable AND writable events
                                reactor.updateInterestedOps(chan, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                             }
                        }
                    }
                } finally {
                    // ALWAYS return the buffer to the pool when done,
                    // even if an exception occurred
                    releaseBuffer(buf);
                }
            };
        } else {
            // Client disconnected (read returned -1)
            releaseBuffer(buf);
            close();
            return null;
        }
    }

    /**
     * Closes this client's connection.
     * Called when the client disconnects or the protocol terminates.
     */
    public void close() {
        try {
            chan.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Writes queued response data back to the client.
     *
     * Called by the Reactor's selector thread when the client is ready
     * to receive data. Goes through the write queue and sends each
     * buffer's contents.
     *
     * Important detail: chan.write(top) might NOT write ALL the bytes
     * in one call (the network buffer might be full). If there are
     * remaining bytes, we stop and wait for the next write event.
     *
     * After all responses are sent:
     *   - If the protocol says to terminate -> close the connection
     *   - Otherwise -> tell the Reactor we only care about READ events
     *     now (no more data to write)
     */
    public void continueWrite() {
        // Keep writing until the queue is empty or we can't write more
        while (!writeQueue.isEmpty()) {
            try {
                // Peek (don't remove yet) the next buffer to write
                ByteBuffer top = writeQueue.peek();
                // Try to write it to the client
                chan.write(top);
                if (top.hasRemaining()) {
                    // Couldn't write everything - the network buffer is full.
                    // Stop here; the Reactor will call us again when
                    // the client is ready for more data.
                    return;
                } else {
                    // All bytes written successfully - remove from queue
                    writeQueue.remove();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                close();
            }
        }

        // All queued data has been sent
        if (writeQueue.isEmpty()) {
            if (protocol.shouldTerminate())
                // Protocol says "close the connection" (e.g., client sent "bye")
                close();
            else
                // Go back to only listening for read events
                // (we have nothing more to write right now)
                reactor.updateInterestedOps(chan, SelectionKey.OP_READ);
        }
    }

    /**
     * Borrows a ByteBuffer from the shared pool.
     * If the pool is empty, creates a new direct buffer.
     *
     * "Direct" buffers live outside the Java heap and are faster
     * for I/O operations because the OS can read/write them without
     * copying data through the Java heap.
     *
     * @return a ByteBuffer ready for writing (position=0, limit=capacity)
     */
    private static ByteBuffer leaseBuffer() {
        // Try to grab a recycled buffer from the pool
        ByteBuffer buff = BUFFER_POOL.poll();
        if (buff == null) {
            // Pool is empty - create a brand new buffer
            return ByteBuffer.allocateDirect(BUFFER_ALLOCATION_SIZE);
        }
        // Reset the buffer so it's ready to be written to again
        buff.clear();
        return buff;
    }

    /**
     * Returns a ByteBuffer to the shared pool for reuse.
     * This avoids creating new buffers all the time.
     *
     * @param buff the buffer to return to the pool
     */
    private static void releaseBuffer(ByteBuffer buff) {
        BUFFER_POOL.add(buff);
    }
}
