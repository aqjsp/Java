package com.aqjszz.tasks;

import java.time.Duration;

final class TaskWorker {
    private final TaskStore store;

    TaskWorker(TaskStore store) {
        this.store = store;
    }

    void run(String id) {
        if (!transition(id, Status.PENDING, Status.RUNNING, null, null)) {
            return;
        }
        try {
            Task t = store.get(id).orElseThrow();
            String result = work(t.payload());
            if (Thread.currentThread().isInterrupted()) {
                transition(id, Status.RUNNING, Status.CANCELLED, null, "interrupted");
                return;
            }
            transition(id, Status.RUNNING, Status.DONE, result, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transition(id, Status.RUNNING, Status.CANCELLED, null, "interrupted");
        } catch (RuntimeException e) {
            transition(id, Status.RUNNING, Status.FAILED, null, e.getMessage());
        }
    }

    private String work(String payload) throws InterruptedException {
        Thread.sleep(Duration.ofMillis(200));
        return "ok:" + payload;
    }

    private boolean transition(String id, Status from, Status to, String result, String error) {
        for (;;) {
            Task cur = store.get(id).orElse(null);
            if (cur == null || cur.status() != from) {
                return false;
            }
            Task neu = cur.withStatus(to, result, error);
            if (store.cas(id, cur, neu)) {
                return true;
            }
        }
    }
}
