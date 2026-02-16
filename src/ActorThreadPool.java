import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedList;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ====================== ACTOR THREAD POOL ============================
 *
 * WHAT IS THIS?
 * This class implements a thread pool that follows the "Actor Model"
 * concept. It ensures that tasks for the SAME actor (e.g., the same
 * client connection) are executed ONE AT A TIME, in order - but tasks
 * for DIFFERENT actors can run IN PARALLEL.
 *
 * WHY DO WE NEED THIS?
 * Imagine two clients, Alice and Bob, are both connected to our server.
 *   - Alice sends messages: "hi", "how are you"
 *   - Bob sends messages: "hello", "bye"
 *
 * We want:
 *   - Alice's messages to be processed in order (first "hi", then "how are you")
 *   - Bob's messages to be processed in order (first "hello", then "bye")
 *   - BUT Alice's and Bob's messages can be processed at the SAME TIME
 *     (in parallel) because they are independent of each other.
 *
 * A regular thread pool would NOT guarantee ordering per client.
 * This ActorThreadPool solves that by treating each client connection
 * as an "actor" with its own task queue.
 *
 * HOW DOES IT WORK?
 * 1. Each "actor" (identified by a key of type T) has a queue of tasks.
 * 2. When a task is submitted for an actor:
 *    - If the actor is NOT currently running a task -> run it immediately
 *    - If the actor IS currently running a task -> add it to the queue
 * 3. When a task finishes, the actor checks its queue:
 *    - If the queue has more tasks -> run the next one
 *    - If the queue is empty -> mark the actor as idle
 *
 * The generic type <T> is the actor identifier type. In this project,
 * T is NonBlockingConnectionHandler - each client connection is an actor.
 *
 * KEY CONCEPTS:
 * - "playingNow" = set of actors that currently have a task running
 * - "actors" map = stores the pending task queue for each actor
 * - Tasks for the same actor are serialized (one at a time)
 * - Tasks for different actors run in parallel on the thread pool
 * =====================================================================
 */
public class ActorThreadPool<T> {

    // Maps each actor to its queue of pending tasks (waiting to be executed).
    // Uses WeakHashMap so if an actor (e.g., a closed connection handler) is
    // no longer referenced anywhere, it gets automatically garbage-collected.
    private final Map<T, Queue<Runnable>> actors;

    // A read-write lock to protect the 'actors' map from concurrent access.
    // Multiple threads can READ at the same time, but only one can WRITE.
    // This is more efficient than a regular lock when reads are more common.
    private final ReadWriteLock actorsReadWriteLock;

    // A thread-safe set that tracks which actors are currently executing a task.
    // If an actor is in this set, any new tasks for it go into the queue instead.
    private final Set<T> playingNow;

    // The actual Java thread pool that runs the tasks.
    // Has a fixed number of threads (set in the constructor).
    private final ExecutorService threads;

    /**
     * Creates a new ActorThreadPool with the specified number of worker threads.
     *
     * @param threads the number of threads in the pool. More threads =
     *                more actors can run in parallel, but uses more memory.
     *                A good default is the number of CPU cores.
     */
    public ActorThreadPool(int threads) {
        // Create a fixed-size thread pool
        this.threads = Executors.newFixedThreadPool(threads);
        // WeakHashMap: entries get removed automatically when the key (actor)
        // is no longer referenced elsewhere - helps prevent memory leaks
        actors = new WeakHashMap<>();
        // ConcurrentHashMap.newKeySet() creates a thread-safe Set
        playingNow = ConcurrentHashMap.newKeySet();
        // ReentrantReadWriteLock allows multiple simultaneous readers
        actorsReadWriteLock = new ReentrantReadWriteLock();
    }

    /**
     * Shuts down the thread pool immediately.
     * Any currently running tasks will be interrupted.
     * Called when the server is closing.
     */
    public void shutdown() {
        threads.shutdownNow();
    }

    /**
     * Submits a task to be executed for a specific actor.
     *
     * The synchronized block ensures that checking "is this actor busy?"
     * and "start running / add to queue" happens atomically (no other
     * thread can interfere in between).
     *
     * @param actor the actor (e.g., connection handler) this task belongs to
     * @param r     the task to execute
     */
    public void submit(T actor, Runnable r) {
        synchronized (actor) {
            if (!playingNow.contains(actor)) {
                // Actor is idle - mark it as active and run the task now
                playingNow.add(actor);
                execute(r, actor);
            } else {
                // Actor is busy - add the task to its pending queue
                pendingRunnablesOf(actor).add(r);
            }
        }
    }

    /**
     * Gets (or creates) the pending task queue for a given actor.
     *
     * Uses a read lock first (to check if the queue exists), and
     * only acquires the write lock if we need to create a new queue.
     * This "read-first, write-if-needed" pattern is more efficient
     * because most of the time the queue already exists.
     *
     * BUG NOTE: This method has bugs in it! See the bug list for details.
     *
     * @param actors the actor whose queue we want (note: parameter naming is buggy)
     * @return the queue of pending tasks for this actor
     */
    private Queue<Runnable> pendingRunnablesOf(T actor) {
        // First, try to READ the existing queue (multiple threads can do this)
        actorsReadWriteLock.readLock().lock();
        Queue<Runnable> pendingRunnables = actors.get(actor);
        actorsReadWriteLock.readLock().unlock();
        // If no queue exists yet, CREATE one (only one thread can write)
        if (pendingRunnables == null) {
            actorsReadWriteLock.writeLock().lock();
            actors.put(actor, pendingRunnables = new LinkedList<>());
            actorsReadWriteLock.writeLock().unlock();
        }

        return pendingRunnables;
    }

    /**
     * Wraps a task with completion logic and submits it to the thread pool.
     *
     * The key idea: after the task runs, we ALWAYS call complete() in the
     * "finally" block. This ensures the actor checks its queue for more
     * work, even if the task threw an exception.
     *
     * @param r     the task to execute
     * @param actor the actor this task belongs to
     */
    private void execute(Runnable r, T actor) {
        threads.submit(() -> {
        try {
            // Run the actual task
            r.run();
        } finally {
            // After the task finishes (or crashes), check for more work
            complete(actor);
        }
        });
    }

    /**
     * Called when an actor's current task finishes.
     * Checks if there are more pending tasks in the actor's queue.
     *   - If yes: run the next task
     *   - If no: remove the actor from "playingNow" (mark as idle)
     *
     * Synchronized on the actor to prevent race conditions with submit().
     *
     * @param actor the actor whose task just completed
     */
    private void complete(T actor) {
        synchronized (actor) {
            Queue<Runnable> pending = pendingRunnablesOf(actor);
            if (pending.isEmpty()) {
            // No more tasks - mark the actor as idle
            playingNow.remove(actor);
            } else {
                // More tasks waiting - run the next one
                execute(pending.poll(), actor);
            }
        }
    }
}
