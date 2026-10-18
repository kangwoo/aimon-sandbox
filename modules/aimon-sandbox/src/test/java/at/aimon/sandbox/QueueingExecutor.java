package at.aimon.sandbox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** A background executor that only queues; the test runs the queue by hand, or refuses every task. */
public final class QueueingExecutor implements Executor {

    private final Deque<Runnable> queue = new ArrayDeque<>();
    private boolean refusing;

    @Override
    public synchronized void execute(Runnable task) {
        if (refusing) {
            throw new RejectedExecutionException("refusing, as the test asked");
        }
        queue.add(task);
    }

    /** Runs every queued task, including those queued while running, on the calling thread. */
    public void runAll() {
        while (true) {
            final Runnable next;
            synchronized (this) {
                next = queue.poll();
            }
            if (next == null) {
                return;
            }
            next.run();
        }
    }

    /** Forgets every queued task, as a node that died would. */
    public synchronized void drop() {
        queue.clear();
    }

    /** From now on every task is refused, as a full or shut-down pool would. */
    public synchronized void refuse() {
        refusing = true;
    }

    public synchronized int queued() {
        return queue.size();
    }
}
