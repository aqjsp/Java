# 异常与 I/O

JLS §11.1.1 把异常类分成两堆：

- **unchecked**：`RuntimeException` 及其子类，加上 `Error` 及其子类。编译器不强迫你写 `throws` 或 `catch`。
- **checked**：`Throwable` 的其余子类。也就是 `Exception` 里除掉 `RuntimeException` 的那一支。

`Error` 单独挂在 `Throwable` 下，不在 `Exception` 下，就是为了让 `catch (Exception e)` 接不住 `OutOfMemoryError` / `StackOverflowError`。普通代码不该恢复 `Error`。

`Throwable` 不能是泛型类（§8.1.2）。所以没有 `class Fail<T> extends Exception`。

---

## 一、checked 是方法契约

方法要抛 checked，必须写进 `throws`，否则编译失败。覆盖方法的 `throws` **不能新增** 父方法没有声明的 checked（§8.4.8.3）。父方法 `throws IOException`，子方法可以只抛 `FileNotFoundException`（更窄），不能改抛 `SQLException`。

这是 Java 相对 C++/Python/Go 最刺的设计。Go 用 `error` 返回值，调用方看见；C++ 异常不区分 checked；Python 全是运行时。Java 把「调用方必须看见」做成了类型系统的一部分。讨厌它的人很多，规则仍然是规则：对 **可恢复、调用方不处理就是 bug** 的失败用 checked（`IOException`、`InterruptedException` 在阻塞 API 上）；对 **编程错误** 用 unchecked（`IllegalArgumentException`、`NullPointerException`、`IllegalStateException`）。

`InterruptedException` 是 checked。吞掉它等于把中断状态扔了。正确做法：捕获后 `Thread.currentThread().interrupt()` 再决定返回或改抛。虚拟线程同样走这套中断模型（JEP 444）。

不要用 checked 包一层再变成 `RuntimeException` 只为了少写 `throws`，除非你在框架边界、文档写明「全部改非检查」。乱包会把契约藏起来。

---

## 二、try-with-resources

`AutoCloseable.close()` 允许抛 `Exception`。Javadoc 强烈建议：先释放底层资源、标成已关，再抛；**不要在 close 里抛 `InterruptedException`**，它被抑制时会和中断状态纠缠。

```java
try (InputStream in = Files.newInputStream(path);
     OutputStream out = Files.newOutputStream(dst)) {
    in.transferTo(out);
}
```

声明顺序：先 `in` 后 `out`。关闭顺序 **反着来**：先 `out.close()` 再 `in.close()`。体里抛的异常是主异常；close 抛的进 `getSuppressed()`。这是语言规则（JLS §14.20.3），不是「最好如此」。

`close` 应当幂等。流已经关了再关，不该炸。`FilterOutputStream` 历史上 close 会再 flush，flush 失败时可能把流留在半关——用具体实现时看文档。

不要把 `Stream`（`java.util.stream`）一律套 try-with-resources。文档写了：纯内存流没资源；包着 I/O 的（`Files.lines`）才需要关。乱关没有 I/O 的 stream 只是噪音。

---

## 三、NIO.2：路径和字节，不是 `File` 字符串拼接

`java.io.File` 还在，新代码用 `java.nio.file.Path` / `Files` / `FileSystem`。

- `Path` 是路径，不保证文件存在。
- `Files.readAllBytes` / `readString`（Java 11+）适合小文件。大文件用 `newInputStream` + 缓冲，或 `FileChannel`。
- `Files.lines` 返回的 `Stream<String>` **必须关**，背后是文件描述符。
- 默认 `StandardCharsets.UTF_8` 写清楚，不要依赖 `Charset.defaultCharset()`（跟 OS、跟 JVM 启动参数）。
- `Files.move` / `copy` 的 `REPLACE_EXISTING`、`ATOMIC_MOVE` 不是处处能原子。同一 `FileStore` 上 `ATOMIC_MOVE` 才可能；跨盘会抛 `AtomicMoveNotSupportedException`。

`FileChannel` 能 `force(true)` 把内容和元数据刷到盘。数据库、WAL 才需要想这个。普通业务拷文件，`InputStream.transferTo` 够。

阻塞 I/O 在平台线程上会占住那条 OS 线程。虚拟线程在 **Java 21** 里，多数阻塞 File/Socket API 会卸载虚拟线程；`synchronized` 仍可能把虚拟线程钉在 carrier 上（JEP 444 原文；后续版本在解钉，21 按 21 说）。I/O 模型选虚拟线程时，锁用 `java.util.concurrent` 的锁，少在热路径 `synchronized`。

---

## 四、ByteBuffer：四个指针，不是一块随便读写的数组

NIO 通道读写不直接吃 `byte[]`，吃 `ByteBuffer`。类文档把缓冲区状态钉成四个索引：

- `capacity`：这块有多大，分配时钉死
- `limit`：第一个不能读写的下标
- `position`：下一个要读写的下标
- `mark`：`reset` 回去的位置，未定义时 `reset` 抛 `InvalidMarkException`

不变量：`0 ≤ mark ≤ position ≤ limit ≤ capacity`。

![ByteBuffer flip](../image/java-bytebuffer.svg)

```java
ByteBuffer buf = ByteBuffer.allocate(8);
buf.put((byte) 1).put((byte) 2).put((byte) 3).put((byte) 4);
// position=4, limit=8：还能写 4 字节

buf.flip();
// limit←旧 position（4），position←0：只能读刚才写进去的 4 字节

while (buf.hasRemaining()) {
    byte b = buf.get();
}
buf.compact();   // 没读完的挪到下标 0，position 设到剩余长度，limit=capacity，接着写
buf.clear();     // position=0, limit=capacity。不清内存，只改指针
buf.rewind();    // position=0，limit 不动，再读一遍
```

`flip` 是写完转读。`clear` 是读完（或不要了）转写整块。`compact` 是「读了一半，剩下的当新内容的前缀继续写」。三者不是同义词。忘了 `flip`、对着刚写完的 buffer 做 `channel.write`，写出的是 position 到 limit 的空段，文件里什么都没有。这是 NIO 入门第一坑。

`allocate` 在堆上，数组能 `array()` 拿出来。`allocateDirect` 在堆外，减少一次用户态拷贝，分配和释放更贵，适合长寿命、反复给通道用的缓冲。直接缓冲没有 `array()`，`hasArray()` 为 false。`ByteOrder` 默认 `BIG_ENDIAN`，和网络字节序一致；和本地内存布局打交道再 `order(ByteOrder.nativeOrder())`。

`FileChannel.read(buf)` 从通道读进 buffer，`position` 往前走。读到 -1 是 EOF。把同一块 buffer 循环读写，每次读之前确认 `limit` 还剩空间，写通道之前确认已经 `flip`。

---

## 五、`catch` 的顺序和多 catch

先子类后父类。`catch (Exception e)` 写在 `catch (IOException e)` 前面，编译失败。

`catch (IOException | SQLException e)`：`e` 实质 final，不能再赋。两个类型的最小共同超类是 `Exception`，对该变量只能调两者都有的方法。

不要 `catch (Throwable t)` 除非在线程顶。`catch (Exception e)` 已经接不住 `Error`，这是层次故意的。

---

## 六、反模式

- `e.printStackTrace()` 当错误处理。
- `catch (Exception e) { throw new RuntimeException(e); }` 无说明地剥掉 checked。
- `catch (InterruptedException e) {}` 空。
- `new FileInputStream` 不关。用 try-with-resources。
- 依赖默认 charset。
- 用 `File.renameTo` 当原子提交。返回 `boolean` 还不抛原因；换 `Files.move`。
- `channel.write(buf)` 之前忘了 `flip`。
- 把 `clear` 当「把内容填零」。它只改指针。

基础四篇到此。进阶从内存模型开始：没有 happens-before，上面这些对象在两个线程之间读到什么，规范不保证。
