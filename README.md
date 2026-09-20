# Java

面向后端的 Java：对象模型、集合、并发、JVM 与面试连环问。

以 **Java SE 21 LTS** 为准（语言对 JLS 21，虚拟机对 JVMS 21 / HotSpot 21）。Swing、JavaFX、Applet 不进主线。阅读顺序：基础 → 进阶 → 实战 → 面试连环问。

知识点覆盖对照 [dev.java/learn](https://dev.java/learn/) 与 Oracle Tutorials 的 Language / Collections / Concurrency trails，正文按本专栏写法重写，不摘教程原文。

## 基础

- [Java入门](基础/Java入门.md) — 值是盒子还是引用、类型宽度、装箱缓存、从源码到 class
- [面向对象](基础/面向对象.md) — class / interface / record / enum、分派、equals 契约
- [集合与泛型](基础/集合与泛型.md) — Collection / Map、擦除、HashMap 桶与树化
- [异常与 I/O](基础/异常与IO.md) — checked 规则、try-with-resources、NIO.2、ByteBuffer

## 进阶

- [并发与内存模型](进阶/并发与内存模型.md) — happens-before、锁、j.u.c、虚拟线程
- [JVM 与垃圾回收](进阶/JVM与垃圾回收.md) — 运行时数据区、对象头、G1、安全点
- [类加载与模块](进阶/类加载与模块.md) — 三层加载器、双亲委派、JPMS
- [Stream 与现代语法](进阶/Stream与现代语法.md) — 流水线、Optional、sealed / pattern matching

## 实战项目

- [HTTP 任务服务](实战项目/HTTP任务服务.md) — JDK `HttpServer` + 虚拟线程，不引入 Spring

## 面试连环问

- [Java面试连环问](面试连环问/Java面试连环问.md)
