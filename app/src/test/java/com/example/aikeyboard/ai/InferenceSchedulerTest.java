// Debounce with latest-request-wins: while the user keeps typing, only the newest request
// for a model ever reaches the inference thread.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class InferenceSchedulerTest {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService inference = Executors.newSingleThreadExecutor();
    private final InferenceScheduler scheduler = new InferenceScheduler(timer, inference);
    private final List<String> ran = new CopyOnWriteArrayList<>();

    @After
    public void tearDown() {
        timer.shutdownNow();
        inference.shutdownNow();
    }

    private void settle() throws InterruptedException {
        Thread.sleep(250);
        CountDownLatch drained = new CountDownLatch(1);
        inference.execute(drained::countDown);
        assertTrue(drained.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void aNewerRequestForTheSameModelReplacesThePendingOne() throws Exception {
        scheduler.schedule("emo", 60, () -> ran.add("first"));
        scheduler.schedule("emo", 60, () -> ran.add("second"));
        scheduler.schedule("emo", 60, () -> ran.add("third"));
        settle();
        assertEquals(List.of("third"), ran);
    }

    @Test
    public void differentModelsDoNotReplaceEachOther() throws Exception {
        scheduler.schedule("emo", 30, () -> ran.add("emo"));
        scheduler.schedule("redact", 60, () -> ran.add("redact"));
        settle();
        assertEquals(List.of("emo", "redact"), ran);
    }

    @Test
    public void cancelAllStopsEverythingStillWaiting() throws Exception {
        scheduler.schedule("emo", 60, () -> ran.add("emo"));
        scheduler.schedule("redact", 60, () -> ran.add("redact"));
        scheduler.cancelAll();
        settle();
        assertEquals(List.of(), ran);
    }

    @Test
    public void workRunsOnTheInferenceThreadNotTheTimerThread() throws Exception {
        String[] threadName = new String[1];
        ExecutorService named = Executors.newSingleThreadExecutor(r -> new Thread(r, "inference"));
        try {
            new InferenceScheduler(timer, named)
                    .schedule("emo", 10, () -> threadName[0] = Thread.currentThread().getName());
            Thread.sleep(200);
            assertEquals("inference", threadName[0]);
        } finally {
            named.shutdownNow();
        }
    }
}
