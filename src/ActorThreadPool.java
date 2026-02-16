public class ActorThreadPool<T> {
    private final Map<T, Queue<Runnable>> actors;
    private final ReadWriteLock actorsReadWriteLock;
    private final Set<T> playingNow;
    private final ExecutorService threads;

    public ActorThreadPool(int threads) {
        this.threads = Executors.newFixedThreadPool(threads);
        actors = new WeakHashMap<>();
        playingNow = ConcurrentHashMap.newKeySet();
        actorsReadWriteLock = new ReentrantReadWriteLock();
    }

    public void shutdown() {
        threads.shutdownNow();
    }

    public void submit(T actor, Runnable r) {
        synchronized (actor) {
            if (!playingNow.contains(actor)) {
                playingNow.add(actor);
                execute(r, actor);
            } else {
                pendingRunnablesOf(actor).add(r);
            }
        }
    }

    private Queue<Runnable> pendingRunnablesOf(T actors) {
        actorsReadWriteLock.readLock().lock();
        Queue<Runnable> pendingRunnables = actors.get(actors);
        actorsReadWriteLock.readLock().unlock();
        if (pendingRunnables == null) {
            actorsReadWriteLock.writeLock().lock();
            actors.put(actor, pendingRunnables = new LinkedList<>());
            actorsReadWriteLock.writeLock().unlock();
        }

        return pendingRunnables;
    }
   
    private void execute(Runnable r, T actor) {
        threads.submit(() -> {
        try {
            r.run();
        } finally {
            complete(actor);
        }
        });
    }

    private void complete(T actor) {
        synchronized (actor) {
            Queue<Runnable> pending = pendingRunnablesOf(actor);
            if (pending.isEmpty()) {
            playingNow.remove(actor);
            } else {
                execute(pending.poll(), actor);
            }
        }
    }
}