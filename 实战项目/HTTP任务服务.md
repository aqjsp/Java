# HTTP 任务服务

目标不是再写一个 Spring 教程。JDK 21 的 `jdk.httpserver` 里有 `com.sun.net.httpserver.HttpServer`：绑定端口、按 URI 前缀挂 `HttpHandler`、用 `Executor` 管线程。文档写明：找不到 handler 就 404；不设 executor 就用默认实现。

**代码在 [`src/com/aqjszz/tasks/`](../src/com/aqjszz/tasks/)，按 Java 21 写。下面讲设计，不维护第二份会漂的代码墙。**

```bash
javac --release 21 -d out src/com/aqjszz/tasks/*.java
java --class-path out com.aqjszz.tasks.TaskStoreTest
java --class-path out com.aqjszz.tasks.Main 8080
```

未命名模块能读 `jdk.httpserver`，不必写 `module-info`。命名模块要 `requires jdk.httpserver`。

---

## 一、目标与非目标

要解决的：

- `POST /tasks` 接单立刻 202，工人在另一条虚拟线程里跑（睡眠模拟阻塞 I/O）
- `GET /tasks/{id}` 查状态；`GET /health` 探活
- 状态机在 `ConcurrentHashMap` 上 CAS，不共享 `HashMap`
- 关停：shutdown hook 里 `server.stop` + `ExecutorService.close`

不解决的：落盘、鉴权、分布式 id、真正的 JSON RFC、对公网暴露。进程一关任务就没了。限流不靠给虚拟线程做池。

---

## 二、线程模型

每个 HTTP 交换一条虚拟线程：`server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())`。JEP 444：**Do not pool virtual threads**。工人也是 `exec.execute(() -> worker.run(id))`，同一条 per-task 执行器，不是再开平台线程池。

Java 21 handler 里不要 `synchronized` 包着读 body / 写响应，否则钉 carrier。共享状态用 CHM。

`HttpExchange` 生命周期按 Javadoc：读方法、读头、读 body、设响应头、`sendResponseHeaders`、写 body、关。`try (ex)` 保证异常路径也关。`Content-Length` 用 UTF-8 **字节** 数，不是 `String.length()`。

绑定 `127.0.0.1`。`setExecutor` 必须在 `start()` 之前。

---

## 三、状态机

`Task` 是 record，分量 final。变化是 `withStatus` 出新实例，`ConcurrentHashMap.replace(id, old, neu)` 按 equals CAS。旧状态对不上就再读再试。`putIfAbsent` 建任务，禁止 `get` 再 `put`。

```text
PENDING → RUNNING → DONE
                 → FAILED
                 → CANCELLED   // InterruptedException 恢复中断标记后进来
```

`TaskWorker.work` 里 `Thread.sleep`，虚拟线程会卸载。不要改成 `synchronized wait`。空 catch `InterruptedException` 会把取消吞掉。

id 用 `UUID.randomUUID()`，单进程够。CHM 不许 null value；record 里 `result`/`error` 为 null 表示没有，不把 null 当 map 空槽。

---

## 四、JSON 与路径

`Json.payloadOf` 只认 `{"payload":"..."}`，转义只处理 `\\` 和 `\"`。不是 RFC 实现。`quote` 按 code point 走。生产用库。

`HttpServer` context 是前缀：`/tasks` 会吃 `/tasks/xyz`。总 handler 看 `getRequestURI().getPath()`，精确 `/tasks` 走 create，否则当 get。`/foo` 接不到 `/foobar`。

---

## 五、关停与测试

`server.stop(1)` 的 1 是秒。`ExecutorService.close()`（19，AutoCloseable）shutdown 并等。虚拟线程卡在不可中断 native 上等不完——关停钩子的固有限制。

`TaskStoreTest` 不占端口：create → worker.run → DONE，缺 payload 抛 `IllegalArgumentException`。handler 的 400/404/405 起服务后用 `HttpClient` 打，端口 0 让系统分配。

压测时 dump 虚拟线程应在 `sleep` 上 park，carrier 大约核数，不应出现一千条平台线程。大量 VT 卡在 `synchronized` 就是钉住。

---

## 六、这里用到了前面哪几条

- 入门：UTF-8 字节长度 ≠ `String.length()`
- 对象：record 换实例，不改字段
- 集合：CHM、`putIfAbsent`、`replace`
- 异常：中断恢复标记；try-with-resources 关 exchange
- 并发 / 虚拟线程：per-task，不池，不用 synchronized 包 I/O
- 类加载：有包名；`jdk.httpserver` 在模块图里
- 网络：阻塞 I/O + VT，不在 handler 里叠 Selector

---

## 七、反模式

- handler 里 `new Thread().start()` 或再包一层有界队列「保护」虚拟线程
- `synchronized (store)` 锁整张 map
- `Content-Length` 用 `json.length()`
- `sendResponseHeaders` 之后改状态码
- 绑定 `0.0.0.0` 还开着无鉴权 POST
- 文档里复制一份和 `src/` 不一致的代码
