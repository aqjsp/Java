package com.aqjszz.tasks;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TaskStore {
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    Task create(String payload) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Task t = new Task(id, payload, Status.PENDING, null, null, now, now);
        Task prev = tasks.putIfAbsent(id, t);
        if (prev != null) {
            throw new IllegalStateException("uuid collision");
        }
        return t;
    }

    Optional<Task> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    boolean cas(String id, Task expected, Task neu) {
        return tasks.replace(id, expected, neu);
    }
}
