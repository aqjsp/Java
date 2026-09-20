# JVM 与垃圾回收

`new` 出来的对象，规范只保证它是引用类型的实例，活在共享的堆上。堆怎么切、对象头几个字节、哪次 GC 停多久，是 **HotSpot 的实现**，不是 JLS。面试把实现细节说成语言规则，是错的；把实现细节说成「大概」，也是错的。这篇按 JVMS 21 的运行时数据区讲规范部分，按 HotSpot 21 / G1 调优文档讲实现部分，两层分开。

---

## 一、运行时数据区（JVMS §2.5）

规范列了这些：

**PC 寄存器。** 每条 JVM 线程一个。线程在跑 Java 方法时，PC 是当前指令地址；跑 native 时，PC 未定义。

**Java 虚拟机栈。** 每条线程一块，和线程一起创建。存的是 **帧**。帧在方法调用时建、返回或没接住的异常时毁。规范说栈内存不必连续，帧甚至可以分配在堆上——实现自己决定。HotSpot 默认还是每线程一块栈，`-Xss` 调大小，撑满抛 `StackOverflowError`；要扩展抛不出时是 `OutOfMemoryError`。

**堆。** 所有线程共享。对象实例、数组在这里。`OutOfMemoryError` 和堆相关的那条从这里来。GC 管的就是这块。

**方法区。** 所有线程共享。按类存运行时常量池、字段和方法数据、方法/构造器的代码，包括类初始化、实例初始化那些特殊方法。JVMS 把方法区说成逻辑区域；HotSpot 8 起类元数据在 **Metaspace**（本地内存，不在 Java 堆里），字符串常量池在堆上。别把「永久代」拿到 21 来说——PermGen 在 8 已经没了。

**运行时常量池。** 每个类一份，从 class 文件常量池来，解析过程会把符号引用变成直接引用。`String.intern` 跟字符串池有关，不是这份常量池本身。

**本地方法栈。** 给 `native` 用，也是每线程。实现可以和 JVM 栈合成一块。HotSpot 大体是这样。

帧里三件套（§2.6）：局部变量表、操作数栈、当前类的运行时常量池引用。`int` / 引用占局部变量表 1 槽，`long` / `double` 占 2 槽。字节码 `iload` / `istore` / `iadd` 围着这两块转。逃逸分析把未逃逸对象拆成标量，是 JIT 在这套模型上的优化，语言规则仍当对象在堆上。

---

## 二、对象在堆上长什么样（HotSpot）

64 位 HotSpot、压缩类指针打开时（21 默认常见配置）：对象头是 **mark word 8 字节 + klass 指针 4 字节**。数组再加 4 字节 length。字段按对齐规则排，对象整体对齐到 8 字节。压缩 oops 打开时，堆上的引用常是 4 字节，堆上限大约 32GB 量级；再大就关压缩，引用变 8 字节，对象整体变胖。

mark word 里塞：哈希码（第一次调 `System.identityHashCode` / 默认 `hashCode` 才算，算完写回）、GC 分代年龄、偏向/轻量/重量锁状态。`synchronized` 锁的就是这块，不是旁边另开一把独立的锁对象——所以 `synchronized (x)` 和 `x` 的身份绑死。

这些数字随是否压缩、是否 32 位、是否开了 Lilliput 实验而变。面试答「对象头 12 字节」要补一句前提；答「规范规定 12 字节」直接错。

分配：线程本地 TLAB（Thread-Local Allocation Buffer）从 Eden 切一小块，`new` 大多是 bump pointer，打满再向堆要。TLAB 是实现，规范不管。大对象可能不走 TLAB。

---

## 三、G1：堆切成 Region

JDK 9 起服务端默认收集器是 G1，21 仍然是。文档对 G1 的定性：generational、incremental、parallel、mostly concurrent、stop-the-world、evacuating，并且在每次 STW 里盯着停顿目标。

![G1：堆切成 Region，年轻代不连续](../image/java-g1-regions.svg)

堆被切成大小相同的 Region。年轻代（Eden + Survivor）和老年代在地址上 **不必连续**——一排 Region 里红的是 Eden、蓝的是 Old，中间可以夹着空闲。Region 大小由堆容量算出，或用 `-XX:G1HeapRegionSize` 钉死。

**Humongous**：对象大小 **大于等于半个 Region**。它不进 Eden，直接占连续的 Humongous Region，逻辑上算老年代。G1 对 humongous 主要判活、死了就回收；搬走它是非常慢的最后手段。大数组、大 `byte[]` 缓冲区会走这条路。Region 1MB 时，512KB 的数组已经 humongous。

**Young GC：** STW，把 Eden/Survivor 里的活对象拷到新的 Survivor 或直接进 Old（年龄到了）。拷走之后源 Region 变空闲。这就是 evacuate。

**并发标记：** 堆占用到了 IHOP（Initiating Heap Occupancy Percent）附近，Young GC 会带着 Concurrent Start。标记大部分和 mutator 并发。Remark、Cleanup 是 STW。21 默认 **Adaptive IHOP**：G1 自己观察标记要多久、标记期间老年代涨多快，去调阈值；`-XX:InitiatingHeapOccupancyPercent` 在自适应还没学够时当初始值。关掉自适应才是固定百分比。

**Mixed GC：** 空间回收阶段。一次 STW 里既处理年轻代，又疏散一组老年代 Region。挑哪些 Old，看残留垃圾多少（garbage first）。这阶段反复 Mixed，直到再回收老年代不划算，然后回到 Young-only。

**Remembered set：** 每个 Region 记「谁可能指向我」。GC 时要修正被搬走对象的外来指针，靠这个。实现上堆按 card 切，默认 512 字节一张，RSet 存的是 card 索引。年轻代每次都收，RSet 一直维护；老年代候选的 RSet 多半在 Remark 和 Cleanup 之间懒建。

**Full GC：** 并发标记和 Mixed 扛不住，堆里腾不出对象要的空间，G1 做整堆 STW、原地压缩。文档原话：very slow。看见 Full GC 是事故，不是「G1 的一种正常档位」。

**停顿目标：** `-XX:MaxGCPauseMillis`。HotSpot 默认 **200 毫秒**。这是 **目标**，G1 选 collection set 的大小时往这个数上靠，不是硬实时，也不是「每次 GC 都 ≤ 200ms」。设成 20 还不给够堆，只会逼 G1 每次少收一点，最终跟不上分配，掉进 Full GC。

G1 的默认配置既不是纯吞吐、也不是最低延迟，是「相对小而均匀的停顿 + 还过得去的吞吐」。要极致吞吐看 Parallel；要更短停顿、能接受更大堆和更多并发开销，21 里有 ZGC（JEP 377 已正式），那是另一套染色指针，本篇不展开。

---

## 四、引用强度

`java.lang.ref` 把可达性分成档（包文档）：

- 强引用：普通字段、局部变量。活着，GC 不收。
- 软引用 `SoftReference`：内存紧时才收。适合做接近堆上限的缓存，不适合当「保证还在」的存储。
- 弱引用 `WeakReference`：下一次发现它弱可达就收。`WeakHashMap` 的 key 是弱引用，GC 后条目没了；文档说它靠 `ReferenceQueue.poll` 在访问时清。
- 虚引用 `PhantomReference`：必须配队列。对象被收回后入队，用来做清理通知。比过时的 `finalize` 可控。
- `Cleaner`：建在虚引用上的清理器，代替 `finalize`。

引用对象本身如果都不可达了，它不会入队。要用队列，得有人握着这份 `Reference`。`WeakHashMap` 自己握着。

不要用 `SoftReference` 当连接池。连接的生命周期跟内存压力无关。

---

## 五、常见参数和该看的日志

日常先动这几个，别一上来抄 30 个 `-XX`：

- `-Xms` / `-Xmx`：堆的起止。生产上两者相等，避免运行中扩堆触发的停顿。
- `-XX:MaxGCPauseMillis`：目标，不是契约。
- `-XX:G1HeapRegionSize`：一般让它自己算。明确要控制 humongous 阈值时再钉。
- `-Xlog:gc*`：21 的统一日志。旧的 `-XX:+PrintGCDetails` 已经走 `Xlog`。

看日志先看：Young/Mixed 的停顿有没有持续顶到目标、有没有 `Pause Full`、humongous 分配是不是很频繁、to-space exhausted 有没有出现。调参没有「通用最优」，对着这一份日志改。

`jcmd <pid> GC.heap_info`、`jstat -gc`、JFR 的 GC 事件，比在代码里猜更准。

---

## 六、和分配、并发的交界

逃逸分析：方法里 `new` 的对象没有逃出方法，JIT 可能标量替换，栈上拆字段，看起来像「没分配」。这是优化，`-XX:-DoEscapeAnalysis` 能关。正确性仍按堆对象来。

`finalizer` 线程、`Reference` 处理线程、G1 的并发标记线程，都是 JVM 自己的线程。`Runtime.availableProcessors()` 不等于「我的业务线程能用的核」。容器里 CPU 限额和这个数对不上时，并行 GC 线程可能过多，21 对容器感知比老版本好，仍要在 cgroup 里核对。

虚拟线程不改变 GC 模型。百万虚拟线程的栈是堆上的 continuation，会增加堆压力。这是用虚拟线程换 I/O 并发时要算的账，不是「虚拟线程不占内存」。

---

## 七、反模式

- 把 PermGen、CMS 当 21 的默认故事讲。CMS 在 14 移除。
- 看见 Full GC 还觉得「反正会压缩」。
- 把 `MaxGCPauseMillis=20` 当 SLA。
- 用堆缓存（大 `HashMap`）当本地缓存，然后怪 G1 Mixed 停顿。
- `System.gc()` 当释放内存的 API。它只是建议，生产上常被禁用（`-XX:+DisableExplicitGC`）。
- 把对象头字节数说成 JLS 规定。

下一篇把类怎么进方法区钉完：加载、链接、初始化、双亲委派、模块。GC 收的是堆上的实例；类本身活在方法区 / Metaspace，卸不卸得掉是另一套规则。
