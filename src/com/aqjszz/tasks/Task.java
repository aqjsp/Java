package com.aqjszz.tasks;

record Task(
        String id,
        String payload,
        Status status,
        String result,
        String error,
        long createdAt,
        long updatedAt
) {
    Task withStatus(Status s, String result, String error) {
        return new Task(id, payload, s, result, error, createdAt, System.currentTimeMillis());
    }
}
