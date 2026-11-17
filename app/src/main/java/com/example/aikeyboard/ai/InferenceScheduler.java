// Debounce and hand-off for inference. Each model has at most one request waiting; a newer
// one replaces it. Work then runs on the single inference thread, never on the caller's.
package com.example.aikeyboard.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class InferenceScheduler implements AiCoordinator.Scheduler {

    private final ScheduledExecutorService timer;
    private final Executor inference;
    private final Map<String, ScheduledFuture<?>> waiting = new HashMap<>();

    public InferenceScheduler(ScheduledExecutorService timer, Executor inference) {
        this.timer = timer;
        this.inference = inference;
    }

    @Override
    public synchronized void schedule(String key, long delayMs, Runnable task) {
        ScheduledFuture<?> previous = waiting.get(key);
        if (previous != null) previous.cancel(false);
        // The timer thread only passes the task on; the model never runs there.
        waiting.put(key, timer.schedule(() -> inference.execute(task), delayMs,
                TimeUnit.MILLISECONDS));
    }

    /**
     * Drops everything still waiting. A run that has already started on the inference thread
     * cannot be stopped; the coordinator discards its result instead.
     */
    @Override
    public synchronized void cancelAll() {
        for (ScheduledFuture<?> future : waiting.values()) future.cancel(false);
        waiting.clear();
    }
}
