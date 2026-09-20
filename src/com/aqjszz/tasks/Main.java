package com.aqjszz.tasks;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Main {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        TaskStore store = new TaskStore();
        TaskWorker worker = new TaskWorker(store);
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Handlers h = new Handlers(store, worker, exec);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", ex -> {
            try (ex) {
                h.health(ex);
            }
        });
        server.createContext("/tasks", ex -> {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                if ("/tasks".equals(path)) {
                    h.create(ex);
                } else {
                    h.get(ex);
                }
            }
        });
        server.setExecutor(exec);
        server.start();
        System.out.println("listening 127.0.0.1:" + port);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            server.stop(1);
            exec.close();
        }));
    }
}
