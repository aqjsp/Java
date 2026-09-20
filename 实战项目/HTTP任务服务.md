# HTTP 任务服务

目标不是再写一个 Spring 教程。JDK 21 的 `jdk.httpserver` 里有 `com.sun.net.httpserver.HttpServer`：绑定端口、按 URI 前缀挂 `HttpHandler`、用 `Executor` 管线程。文档写明：找不到 handler 就 404；不设 executor 就用默认实现。够把前面几篇接到一条能跑的链上。

服务做三件事：

- `POST /tasks` 提交一段耗时工作（这里用可中断的睡眠模拟 I/O）
- `GET /tasks/{id}` 查状态
- `GET /health` 探活

任务存在内存里的 `ConcurrentHashMap`。进程一关就没了。这是故意的：先把并发、虚拟线程、异常、JSON 手写走通，再谈落盘。

---

## 一、线程模型先定

每个请求一条虚拟线程。JEP 444：`Executors.newVirtualThreadPerTaskExecutor()`，不要池虚拟线程。

```java
HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
```

`backlog` 传 0 表示用系统默认的 accept 队列。`setExecutor` 必须在 `start()` 之前。executor 为 null 时，`start()` 用默认实现——那条路径不是虚拟线程，本项目不走。

`HttpExchange` 的生命周期（Javadoc 顺序，不要调换）：

1. `getRequestMethod()`
2. `getRequestHeaders()` 如需要
3. `getRequestBody()`，读完关掉
4. `getResponseHeaders()` 设头，不要在这里设 Content-Length
5. `sendResponseHeaders(code, length)` —— **必须在写 body 之前**
6. `getResponseBody()` 写完必须关，关了才结束这次 exchange

`length`：确切字节数；`-1` 表示 chunked，长度未知；`0` 表示没 body。关 response body 会顺带关 request body。`exchange.close()` 两个都关。用 try-with-resources 包 `HttpExchange`（它是 `AutoCloseable`）。

handler 里不要 `synchronized` 包着 I/O。Java 21 会把虚拟线程钉在 carrier 上。共享状态用 `ConcurrentHashMap` 和 `ReentrantLock`。

---

## 二、任务长什么样

```java
enum Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

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
```

`record` 分量是 final。状态变化不是改字段，是换一个 Task 放回 map。`ConcurrentHashMap.replace(id, old, neu)` 按 `Objects.equals` 比旧值，原子地换；CAS 失败就再读再试。record 按分量 equals，旧状态对不上就换不成，这正是状态机要的。不要 `get` 再 `put` 当「没有才插入」——集合篇写过，用 `putIfAbsent`。

id 用 `UUID.randomUUID()`。这不是分布式唯一的终极方案，单进程够。

---

## 三、目录和主类

```text
src/com/aqjszz/tasks/
    Main.java
    Task.java
    TaskStore.java
    TaskWorker.java
    Json.java
    Handlers.java
```

包名要有。未命名包别人 import 不了，入门篇写过。

模块：这个练习放类路径，不写 `module-info.java`。要用模块的话，`jdk.httpserver` 不是 `java.base` 的一部分，得 `requires jdk.httpserver`。类路径上的未命名模块读所有模块，所以 `javac` 加上 `--add-modules jdk.httpserver` 或直接把这个模块放进路径就能编过。21 的 `javac` 对 JDK 模块通常能直接看见。运行：

```bash
javac --release 21 -d out src/com/aqjszz/tasks/*.java
java --class-path out com.aqjszz.tasks.Main
```

---

## 四、Json：手写最小子集

不要为了三个字段拉 Jackson。请求体就 `{"payload":"..."}`，响应就几个字符串字段。自己扫一遍字节，够把 NIO.2、charset、异常契约用起来。

```java
final class Json {
    private Json() {}

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (cp < 0x20) b.append("\\u%04x".formatted(cp));
                    else b.appendCodePoint(cp);
                }
            }
        }
        return b.append('"').toString();
    }

    static String obj(String... kv) {
        if ((kv.length & 1) != 0) throw new IllegalArgumentException("odd kv");
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(quote(kv[i])).append(':').append(kv[i + 1]);
        }
        return b.append('}').toString();
    }

    /** 只认 {"payload":"..." }，字符串里的转义只处理 \\ 和 \"。 */
    static String payloadOf(String body) {
        String key = "\"payload\"";
        int k = body.indexOf(key);
        if (k < 0) throw new IllegalArgumentException("missing payload");
        int colon = body.indexOf(':', k + key.length());
        int q1 = body.indexOf('"', colon + 1);
        if (colon < 0 || q1 < 0) throw new IllegalArgumentException("bad payload");
        StringBuilder out = new StringBuilder();
        for (int i = q1 + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                if (i + 1 >= body.length()) throw new IllegalArgumentException("bad escape");
                out.append(body.charAt(++i));
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated payload");
    }
}
```

`quote` 按 code point 走，不按 `char`。入门篇：`char` 是 UTF-16 code unit，emoji 两个 char。JSON 必须用 UTF-8 字节长度算 `Content-Length`，不要用 `String.length()`。

```java
static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
}
```

这个解析器不是 JSON RFC 实现。生产用库。这里为了不把焦点稀释。

---

## 五、Store：一张 CHM，没有 null

```java
final class TaskStore {
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    Task create(String payload) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Task t = new Task(id, payload, Status.PENDING, null, null, now, now);
        Task prev = tasks.putIfAbsent(id, t);
        if (prev != null) throw new IllegalStateException("uuid collision");
        return t;
    }

    Optional<Task> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    boolean cas(String id, Task expected, Task neu) {
        return tasks.replace(id, expected, neu);
    }
}
```

`ConcurrentHashMap` 不允许 null value。`result` / `error` 在 record 里用 null 表示没有，是 Task 自己的事，不把 null 当 map 的「空槽」。`get` 读到的非 null 值，和当初的写入有 happens-before（CHM 文档）。

`Optional` 只出现在 `get` 的返回值上。不要把 Optional 存进 map。

---

## 六、Worker：可中断，状态用 CAS 推

```java
final class TaskWorker {
    private final TaskStore store;

    TaskWorker(TaskStore store) { this.store = store; }

    void run(String id) {
        if (!transition(id, Status.PENDING, Status.RUNNING, null, null)) return;
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
        // 模拟阻塞 I/O。虚拟线程在这类调用上会卸载 carrier。
        Thread.sleep(Duration.ofMillis(200));
        return "ok:" + payload;
    }

    private boolean transition(String id, Status from, Status to, String result, String error) {
        for (;;) {
            Task cur = store.get(id).orElse(null);
            if (cur == null || cur.status() != from) return false;
            Task neu = cur.withStatus(to, result, error);
            if (store.cas(id, cur, neu)) return true;
        }
    }
}
```

`InterruptedException` 是 checked。空 catch 等于把中断状态扔了，异常篇写过。捕获之后 `interrupt()` 自己，再把任务标成 CANCELLED。

`Thread.sleep` 在虚拟线程上会卸载。不要改成 `synchronized (this) { wait(200); }` 来「更像生产」——Java 21 里这会钉住 carrier，JEP 444 点名 `Object.wait()` 不卸载。

CAS 循环不是自旋空转：`replace` 失败说明别人改了这条，再读再试。任务状态机是单工人（下面提交后只 start 一条虚拟线程跑这个 id），冲突只应出现在取消和完成打架时。

---

## 七、Handler：把 exchange 走完

```java
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
        String path = ex.getRequestURI().getPath();          // /tasks/{id}
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
```

`create` 返回 202。任务在另一条虚拟线程里跑，这条请求线程立刻回去。不要在 handler 里 `worker.run` 同步执行——那就把「每个请求一条虚拟线程」变成「请求线程兼工人」，客户端超时和工人超时缠到一起。

路径匹配：`HttpServer` 的 context 是前缀。文档例子：`/foo` 能接到 `/foo`、`/foo/bar`，接不到 `/foobar`（没有斜杠分界）。所以：

- `/health` 挂 health
- `/tasks` 挂 create（只接受精确 `/tasks`，子路径让 get 处理）
- `/tasks/` 挂 get

更稳的做法是一个总 handler 自己拆路径。前缀陷阱比看起来容易踩：`createContext("/task", ...)` 会把 `/tasks` 也吃进去吗？不会，因为下一字符不是 `/` 也不是结束。反过来 `/tasks` 会吃 `/tasks/xyz`。拆的时候用 `getRequestURI().getPath()`，不要用 context 的 path 去减请求 URI 的 scheme。

---

## 八、Main：装起来，关干净

```java
public final class Main {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        TaskStore store = new TaskStore();
        TaskWorker worker = new TaskWorker(store);
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Handlers h = new Handlers(store, worker, exec);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", ex -> {
            try (ex) { h.health(ex); }
        });
        server.createContext("/tasks", ex -> {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                if ("/tasks".equals(path)) h.create(ex);
                else h.get(ex);
            }
        });
        server.setExecutor(exec);
        server.start();
        System.out.println("listening 127.0.0.1:" + port);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            server.stop(1);
            exec.close();          // ExecutorService 在 19 起是 AutoCloseable，会等任务
        }));
    }
}
```

`try (ex)` 保证 handler 抛异常时 exchange 仍关。`sendResponseHeaders` 还没调就炸，客户端会看到连接断，而不是半截 HTTP。要更友好，外层再包一层：捕获之后若还没发头，回 500。

`server.stop(delay)` 的 delay 是秒，等当前交换结束的最长时间，然后关监听。`ExecutorService.close()`（19 起，`AutoCloseable`）会 shutdown 并等待。虚拟线程任务如果卡在不可中断的 native 上，等不完——这是关停钩子的固有限制，不是这个 API 独有。

绑定 `127.0.0.1` 不是 `0.0.0.0`。练习服务不要暴露到局域网。

---

## 九、完整走一遍

```bash
curl -sS -X POST http://127.0.0.1:8080/tasks \
  -H 'Content-Type: application/json' \
  --data '{"payload":"hello"}'
# {"id":"...","status":"PENDING",...}   HTTP 202

curl -sS http://127.0.0.1:8080/tasks/<id>
# 200ms 内 RUNNING 或 DONE

curl -sS http://127.0.0.1:8080/health
# {"status":"up"}
```

并发压一下：

```bash
seq 1000 | xargs -P 100 -I{} curl -sS -X POST http://127.0.0.1:8080/tasks \
  -H 'Content-Type: application/json' --data '{"payload":"{}"}' -o /dev/null
```

1000 个任务、每个睡眠 200ms，虚拟线程会在 sleep 上卸载。平台线程池用 200 条去做同样的事，要么排队要么把 OS 线程打满。这就是 JEP 444 要解决的那笔账。CPU 打满的循环不要指望虚拟线程变快。

出错路径要自己打：

- 不是 JSON：400
- GET `/tasks/no-such`：404
- GET `/tasks`：405（create 只收 POST）
- 缺 payload：400

---

## 十、这里用到了前面哪几条

- 入门：UTF-8 字节长度 ≠ `String.length()`；`char` 不是一个字。
- 对象：record 当值，状态变化换实例，不改字段。
- 集合：CHM 无 null、`putIfAbsent`、`replace` CAS；不要共享 HashMap。
- 异常：`InterruptedException` 恢复中断标记；try-with-resources 关 exchange。
- 并发：CHM 的单元素 hb；虚拟线程 per task；不用 `synchronized` 包 I/O。
- 类加载：有包名；`jdk.httpserver` 在模块图里。
- Stream：本服务没用 Stream 硬凑。列表接口以后真要过滤再用，不要在 handler 里 `parallelStream`。

---

## 十一、反模式

- handler 里 `new Thread(...).start()` 又自己维护一套平台线程池。
- 给 `newVirtualThreadPerTaskExecutor` 外面再包一层有界队列「保护一下」。要限流，限的是任务数或令牌，不是虚拟线程池大小。
- `synchronized (store)` 把整张 map 锁住。
- `Content-Length` 用 `json.length()`。
- `sendResponseHeaders` 之后才发现要改状态码。头已经走了。
- 解析 JSON 用 `split(",")` 当解析器，payload 里有逗号就炸。上面那个扫描器已经够窄，再窄就错。
- 绑定 `0.0.0.0` 还开着没有鉴权的 POST。

这个服务不是产品。它是把 JLS / JEP / `HttpServer` 文档接到一次请求上的现场。面试里能把「为什么 202、为什么 CHM、为什么虚拟线程不池」顺着这条链路讲完，比背 Spring 自动配置有用。
