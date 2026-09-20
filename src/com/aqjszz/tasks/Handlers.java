package com.aqjszz.tasks;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executor;

final class Handlers {
    private final TaskStore store;
    private final TaskWorker worker;
    private final Executor exec;

    Handlers(TaskStore store, TaskWorker worker, Executor exec) {
        this.store = store;
        this.worker = worker;
        this.exec = exec;
    }

    void health(HttpExchange ex) throws IOException {
        reply(ex, 200, Json.obj("status", Json.quote("up")));
    }

    void create(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            reply(ex, 405, Json.obj("error", Json.quote("method")));
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String payload;
        try {
            payload = Json.payloadOf(body);
        } catch (IllegalArgumentException e) {
            reply(ex, 400, Json.obj("error", Json.quote(e.getMessage())));
            return;
        }
        Task t = store.create(payload);
        exec.execute(() -> worker.run(t.id()));
        reply(ex, 202, toJson(t));
    }

    void get(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            reply(ex, 405, Json.obj("error", Json.quote("method")));
            return;
        }
        String path = ex.getRequestURI().getPath();
        String id = path.substring("/tasks/".length());
        Optional<Task> t = store.get(id);
        if (t.isEmpty()) {
            reply(ex, 404, Json.obj("error", Json.quote("not found")));
            return;
        }
        reply(ex, 200, toJson(t.get()));
    }

    static String toJson(Task t) {
        return Json.obj(
                "id", Json.quote(t.id()),
                "status", Json.quote(t.status().name()),
                "payload", Json.quote(t.payload()),
                "result", t.result() == null ? "null" : Json.quote(t.result()),
                "error", t.error() == null ? "null" : Json.quote(t.error())
        );
    }

    static void reply(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
