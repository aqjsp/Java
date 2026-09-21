# Java

面向后端的 Java：对象模型、集合、并发、JVM 与面试连环问。

以 **Java SE 21 LTS** 为准（语言对 JLS 21，虚拟机对 JVMS 21 / HotSpot 21）。Swing、JavaFX、Applet、Spring 不进主线。阅读顺序：基础 → 进阶 → 实战 → 面试连环问。

正文按本专栏写法重写，不摘教程原文。事实对 JLS / JVMS / JEP / OpenJDK 21 源码。

## 基础

- [Java入门](基础/Java入门.md) — primitive/引用、装箱缓存、javap、classpath
- [面向对象](基础/面向对象.md) — 分派、equals、桥方法、序列化
- [集合与泛型](基础/集合与泛型.md) — HashMap 树化、CHM putVal、ArrayList.grow
- [异常与 I/O](基础/异常与IO.md) — checked、try-with-resources、ByteBuffer、charset
- [日期与时间](基础/日期与时间.md) — Instant/LocalDateTime/ZonedDateTime、DateTimeFormatter 线程安全

## 进阶

- [并发与内存模型](进阶/并发与内存模型.md) — happens-before、锁、线程池、虚拟线程、ThreadLocal
- [AQS与同步器](进阶/AQS与同步器.md) — state、CLH 变体、公平/非公平、Condition
- [CompletableFuture](进阶/CompletableFuture.md) — commonPool、thenCompose、异常、取消
- [泛型与类型系统](进阶/泛型与类型系统.md) — 擦除、型变、通配符捕获、类型推断、递归泛型
- [JVM 与垃圾回收](进阶/JVM与垃圾回收.md) — G1、安全点、JIT 分层、OOM 分类
- [类加载与模块](进阶/类加载与模块.md) — 三层加载器、SPI/TCCL、JPMS
- [反射与动态代理](进阶/反射与动态代理.md) — forName、Proxy 只能接口、MethodHandle
- [网络NIO与虚拟线程](进阶/网络NIO与虚拟线程.md) — 短写、Selector、21 阻塞 I/O + VT
- [Stream 与现代语法](进阶/Stream与现代语法.md) — 流水线、Collector、sealed switch

## 实战项目

- [HTTP 任务服务](实战项目/HTTP任务服务.md) — 源码在 [`src/com/aqjszz/tasks`](src/com/aqjszz/tasks)

## 面试连环问

- [Java面试连环问](面试连环问/Java面试连环问.md)
