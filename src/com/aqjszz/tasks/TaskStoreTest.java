package com.aqjszz.tasks;

final class TaskStoreTest {
    public static void main(String[] args) {
        TaskStore store = new TaskStore();
        Task t = store.create("hello");
        if (t.status() != Status.PENDING) {
            throw new AssertionError("create not PENDING");
        }
        if (store.get("no-such").isPresent()) {
            throw new AssertionError("missing id present");
        }
        TaskWorker w = new TaskWorker(store);
        w.run(t.id());
        Task done = store.get(t.id()).orElseThrow();
        if (done.status() != Status.DONE) {
            throw new AssertionError("expected DONE, got " + done.status());
        }
        if (!"ok:hello".equals(done.result())) {
            throw new AssertionError("result " + done.result());
        }
        try {
            Json.payloadOf("{}");
            throw new AssertionError("missing payload should fail");
        } catch (IllegalArgumentException ok) {
            // expected
        }
        if (!"hi".equals(Json.payloadOf("{\"payload\":\"hi\"}"))) {
            throw new AssertionError("payload parse");
        }
        System.out.println("TaskStoreTest ok");
    }
}
