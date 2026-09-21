# Java面试连环问

面 Java 岗，面试官很少停在「HashMap 底层是数组加红黑树」。会顺着你的第一句往下挖：树化阈值为什么还有 64、1.8 还会不会丢数据、CHM 为什么不许 null、volatile 的边到底焊在哪个字段上。下面按真实出现过的点写，每题先给能说出口的答案，再「往下追」给规范、源码、反例、和 C++/Go/Python 的对照。版本按 **Java SE 21**。把 8 的 PermGen、14 已删的 CMS、1.7 的 Segment 拿来当现状，直接停。

---

### 1、`int` 和 `Integer` 差在哪？

能说出口：`int` 是 primitive，变量里直接是 32 位位模式；`Integer` 是引用，变量里是指针，对象在堆上（或缓存在 IntegerCache）。赋值拷的东西不一样：`int` 拷值，`Integer` 拷引用。

往下追：**`Integer a = 127; Integer b = 127; a == b` 一定 true 吗？128 呢？** 127 一定 true。JLS §5.1.7：常量表达式装箱，`boolean` 的 true/false、`char` `\u0000`–`\u007f`、`byte/short/int/long` 的 **-128..127**，任意两次装箱结果必须 `==`。128 规范没保证。HotSpot 默认 128 是两个对象，`==` false，`equals` true。实现可以缓存更多（`-XX:AutoBoxCacheMax`），代码不能依赖。

OpenJDK 21 的 `Integer.valueOf`：落在 `[low, high]` 就返回 `IntegerCache.cache[i + 128]`，否则 `new Integer(i)`。`low` 钉死 -128，`high` 默认 127 且 assert ≥ 127。`new Integer(127)` 不进缓存，和装箱的 127 `==` 为 false。

再追：**`Integer x = null; if (x == 1)` 会怎样？** 拆箱 NPE。`==` 一边引用一边 primitive，会拆箱。`int n = map.get(k)` 在 HashMap 里 key 不在时同样 NPE，不是 0。CHM 不许 null value，get 到 null 只表示没有这条。

再追：**为什么不能 `List<int>`？** 泛型擦除后元素类型必须是引用。热路径计数用 `int[]`。三元运算符一边 `Integer` 一边 `int` 会拆箱，`flag ? nullInteger : 0` 是 NPE 现场。

C++ 的 `int` 是盒子；Python 的 `a = 1` 是绑堆上任意精度 int。Java 一门语言里两套规则同时生效。

局部 `int n;` 打印 n？编译失败。默认值只给字段和数组元素（§4.12.5）。Go 局部是零值，C++ 未初始化是 UB。

---

### 2、覆盖和重载怎么分？`equals(Dog)` 行不行？

能说出口：覆盖看运行时类型，`invokevirtual`。重载看编译期静态类型，字节码已经选好方法。`Animal a = new Dog(); a.speak()` 是覆盖；`f(a)` 在 `f(Animal)` / `f(Dog)` 里选 `f(Animal)` 是重载。

往下追：**写成 `equals(Dog o)` 行不行？** 不行。那是重载，不是覆盖。`Object.equals` 的签名是 `equals(Object)`。HashMap 调的是 `equals(Object)`，你的 `equals(Dog)` 永远不会被用到。覆盖必须签名相同，写 `@Override`。

再追：**为什么覆盖 equals 必须覆盖 hashCode？** 契约：equals 为 true，hashCode 必须相等。HashMap 先按 hash 选桶，再 equals。hash 不同，根本走不到 equals，put 进去 get 不到。

再追：**子类加字段再覆盖 equals，会怎样？** 对称性容易碎。`Point.equals(ColoredPoint)` 只比坐标，反过来比颜色，一边 true 一边 false。值类型不要为加字段去继承，组合或 sealed + record。

再追：**静态方法能覆盖吗？** 不能。静态方法是隐藏，按静态类型解析。`Parent p = new Child(); p.staticM()` 调的是 Parent 那个。`private` 方法也不参与覆盖。

构造器里调可覆盖方法：子类字段还没赋，hook 看见的是默认值。这是 this-escape 的近亲。

C++ 默认静态绑定，要虚才动态。Go 接口是 itable。Java 实例方法默认虚。

---

### 3、HashMap 默认容量、负载因子、树化？

能说出口：OpenJDK 21 默认容量 16，负载因子 0.75，阈值 12。容量保持 2 的幂，下标 `(n - 1) & hash`。hash 先 `h ^ (h >>> 16)` 让高位参与。

往下追：**树化阈值是多少？为什么还有 64？** 集合篇那张表：`hashCode` 恒 1 时，**第 8 次 put 还不调 `treeifyBin`**（`binCount=6`）。第 9 次 `binCount=7` 才调；表长 16&lt;64 **只 resize 到 32**，链不拆（`1 & 16 == 0`）。第 11 次表长 64 才真树化。答「超过 8 就树化」少了这两步。`UNTREEIFY_THRESHOLD=6`。哈希均匀时 #9 的 resize 就把链拆开，走不到树。

再追：**多线程用 HashMap 会死环吗？** 1.7 头插 + 扩容会。1.8 尾插不再死环，**仍会丢数据、size 错**。不是「8 之后 HashMap 线程安全」。多线程用 `ConcurrentHashMap`。

再追：**`new HashMap<>(100)` 容量是 100 吗？** 不是。向上取到 2 的幂（128）。要装 N 个还不扩容，初始容量至少 `N / 0.75`。

再追：**null key 放哪？** hash 记 0，落在桶 0。CHM 不允许 null。

`resize` 按 hash 的新增那一位拆成 lo / hi，O(n) 不必重算 hash。`modCount` 只在结构修改时加，改 value 不加；迭代器拿它做 fail-fast，是尽力检测，不保证每次都抛 CME。

---

### 4、ConcurrentHashMap 和 HashMap 能换着用吗？

能说出口：不能。HashMap 允许 null key/value；CHM 不允许，null 被内部用来表示「现在没有」。CHM 迭代 weakly consistent，不抛 CME，也不保证看见并发插入。`size()` 是估计。`get` 读到的非 null 值和当初的写入有 happens-before。

往下追：**还是 Segment 分段锁吗？** 不是。那是 1.7。8 起是桶数组 + 链表/树，扩容可以逐桶转移，线程能协助扩容。面试答 Segment 16 是在答旧版。

再追：**`get` 再 `put` 当「没有才插入」？** 不是原子。用 `putIfAbsent` / `computeIfAbsent`。频率统计：`freqs.computeIfAbsent(k, x -> new LongAdder()).increment()`。`compute*` 对这条 key 串行，函数里不要再进同一张 map，文档写了会 `IllegalStateException`。

再追：**批量 `forEach` 原子吗？** 不保证对整张表原子，只是单元素 hb 的组合。

---

### 5、checked 和 unchecked 怎么划？InterruptedException 空 catch？

能说出口：JLS §11.1.1：`RuntimeException` 及其子类、`Error` 及其子类是 unchecked。其余 `Exception` 是 checked。`Error` 不在 `Exception` 下，`catch (Exception e)` 接不住 OOM。

往下追：**覆盖方法能不能多抛一个 checked？** 不能（§8.4.8.3）。可以更窄。父 `throws IOException`，子可以 `FileNotFoundException`，不能 `SQLException`。lambda 实现 `Runnable` 也不能把 checked 往外抛。

再追：**try-with-resources 谁先关？close 抛异常呢？** 声明顺序反过来关。体里的异常是主异常，close 的进 `getSuppressed()`（§14.20.3）。finally 里 `return` 会吞体里的异常，这是语言要把 suppressed 做成规则的原因。

再追：**`InterruptedException` 空 catch？** 中断状态丢了。阻塞 API 被中断时清标记再抛。捕获后 `Thread.currentThread().interrupt()`，再决定返回或改抛。虚拟线程同一套中断模型。空 catch 是线程停不下来的常见原因。`Thread.stop` 在 21 是死 API。

再追：**`ClassNotFoundException` 和 `NoClassDefFoundError`？** 前者是 `ClassLoader` 层 checked，后者是 JVM 解析符号引用失败，或 `<clinit>` 曾经失败后再用这个类。不是一回事。

---

### 6、volatile 干什么？两个线程写 x=1 读 x，写在前面一定看见 1 吗？

能说出口：给这个字段的写和后续读一条 happens-before 边（§17.4.5）。读必须看见这条写，以及写之前同线程里按程序序发生的动作（传递）。它不是互斥，`n++` 仍丢更新。

往下追：**墙上时钟写在读前面，读一定看见 1 吗？** 不一定。Table 17.4.5-A：允许先两边都读到 0，再写 A、写 B，`r1==r2==0`。那张执行序表在并发篇。

再追：**双重检查为什么要 volatile？** 字节码是 `new` / `putfield` / `putstatic`。无 volatile 时 B 可以看见 instance 非 null、x 仍是 0。有 volatile 的 putstatic 把 putfield hb 过去。能用类初始化就别手写。

再追：**构造器里把 this 发布出去？** this-escape。final 字段的 freeze 在构造器退出时（§17.5.1）。别人可能读到 final 的默认值。`final int[]` 只冻结引用，元素没有。

wait 必须 while 包条件，规范允许虚假唤醒。wait 释放 monitor，sleep 不释放。sleep 在 synchronized 里等于握着锁睡。

---

### 7、虚拟线程是什么？要不要池化？

能说出口：还是 `java.lang.Thread`。平台线程一生占用一条 OS 线程；虚拟线程 M:N，不把 OS 线程占到代码结束，可换 carrier。`Thread.currentThread()` 是虚拟线程自己，看不见 carrier。JEP 444，21 正式。

往下追：**`new Thread(r).start()` 是虚拟线程吗？** 不是。公开构造器造平台线程。`Thread.ofVirtual().start(r)` 或 `Executors.newVirtualThreadPerTaskExecutor()`。

再追：**要不要池化？** 不要。JEP 原文：Do not pool virtual threads。池分享贵的资源，虚拟线程不贵。从旧池迁过来，迁到 per-task ExecutorService，不是把池里的平台线程换成虚拟线程继续池。

再追：**Java 21 什么情况下卸不下来？** 钉在 carrier 上：① `synchronized` 方法或块里；② native / foreign function。钉住不算错，可扩展性变差。热路径阻塞 I/O 把 `synchronized` 换成 `ReentrantLock`。`Object.wait()` 和部分文件系统调用也不卸载，实现会临时把调度器线程数抬上去补偿。调度器不为钉住扩并行度。

再追：**CPU 密集会更快吗？** 不会。一条虚拟线程算 CPU 时占一个 carrier，和平台线程打满核同类。它解决的是 I/O 并发被 OS 线程上限卡住。Go 的 goroutine 是同类问题的另一种实现。

再追：**虚拟线程能让 JVM 保持活着吗？** 默认不能。`main` 结束只剩虚拟线程，JVM 可以退。要等，自己 join 或 Executor.close。

`ThreadLocal` 语义还在，百万条虚拟线程各一份会炸内存。别当缓存用。

---

### 8、线程池 execute 的顺序？newFixedThreadPool 有什么坑？

能说出口：`ThreadPoolExecutor.execute`：先看 core，不够就开线程；否则入队；队列满且 < max 再开线程；否则拒绝。不是「先入队再开到 max」。

往下追：**`newFixedThreadPool(n)`？** core = max = n，队列无界 LinkedBlockingQueue。任务堆积只涨队列，OOM 在堆上。`newCachedThreadPool` core=0、max=Integer.MAX_VALUE、SynchronousQueue，突发开爆线程。生产自己 `new ThreadPoolExecutor`，队列有界，拒绝策略写明。`CallerRunsPolicy` 能形成背压，`AbortPolicy` 抛异常，`DiscardPolicy` 默默扔。

再追：**shutdown 和 shutdownNow？** shutdown 不接新任务、等已提交的跑完。shutdownNow 中断在跑的、返回队列里没跑的。任务不响应中断就停不下来。`awaitTermination` 才是等到或超时。

I/O 密集 21 起用虚拟线程 per-task，不要开 200 的固定池硬扛。CPU 密集仍是核数附近的平台线程池。

---

### 9、21 默认收集器？Mixed 和 Full？对象头几个字节？

能说出口：G1。堆切成等大 Region，年轻代地址上不必连续。活对象拷到别的 Region（evacuate）。Humongous：对象 ≥ 半个 Region，直接占连续 Humongous Region，算老年代，搬走是最后手段。Region 1MB 时 512KB 数组已经 humongous。

往下追：**Mixed 和 Full 的区别？** Mixed：一次 STW 里收年轻代 + 一组垃圾多的老年代 Region。Full：整堆 STW 原地压缩，文档说 very slow，是并发标记和 Mixed 扛不住时的退路，不是正常档位。

再追：**`MaxGCPauseMillis` 是硬限制吗？** 不是。默认 **200 毫秒**，是目标。G1 选 collection set 时往上靠。设太小又不给堆，跟不上分配，掉进 Full GC。

再追：**IHOP？** Initiating Heap Occupancy Percent，老年代占用到这附近开始并发标记。21 默认 Adaptive IHOP，自己观察标记耗时和分配速率。

再追：**PermGen 呢？CMS 呢？** PermGen 8 没了，类元数据在 Metaspace（本地内存，不在 Java 堆）。CMS 14 移除。字符串常量池在堆上。别当 21 的答案。

再追：**对象头几个字节？** HotSpot 实现，不是 JLS。64 位压缩类指针常见配置：mark 8 + klass 4，数组再加 length 4。随压缩、对齐、实验特性变。空 Object 常 16 字节。别说成语言规定。

再追：**RSet？** 每个 Region 记谁可能指向我，card 默认 512 字节。跨区指针多，RSet 胖，这是 G1 相对 Parallel 的开销之一。

`System.gc()` 只是建议，生产常 `-XX:+DisableExplicitGC`。`finalize` 18 起 deprecated for removal，用 Cleaner。

---

### 10、双亲委派是什么？Java 9 之后三层加载器？

能说出口：`ClassLoader.loadClass` 默认先问 parent，找不到再自己 `defineClass`。保证 `java.lang.Object` 只有一份。它是默认实现，不是 JVMS 强制算法。SPI、热加载、Servlet 容器会打破。

往下追：**9 之后三层？** Bootstrap（Java 里 `null`）、Platform（`getPlatformClassLoader`）、System/Application（`getSystemClassLoader`）。`String.class.getClassLoader()` 是 null。ext 加载器没了，别答 8。

再追：**定义加载器和初始加载器？** 谁 `defineClass` 谁就是定义加载器。同一个名字不同定义加载器是不同 Class，强转 `ClassCastException`。热加载、OSGi、Tomcat 里「明明是同一个类」的根。

再追：**`Class.forName` 会初始化吗？** 单参数那个会。只要 Class 不要初始化：`forName(name, false, loader)`。TCCL 是线程上的钩子，线程池不传 TCCL，SPI 在池里找不到驱动。

再追：**什么触发 `<clinit>`？** §12.4.1：new 实例、调这个类声明的静态方法、给它声明的静态字段赋值、用它声明的非常量静态字段。`T.class`、常量变量（`static final int X = 1`）、`new T[]` 不触发。`static final Integer BOX = 1` 不是常量变量，会触发。每个类一把初始化锁 LC，递归同一线程视为已初始化，失败后进错误态不会重跑。两个类静态块交叉等待会锁死。

再追：**类什么时候卸载？** Class 对象、所有实例、定义加载器都不可达（§12.7）。加载器泄漏 = Metaspace 涨。

再追：**模块 `exports` 和 `opens`？** `exports`：别的模块编译期能引用公开类型。`opens`：运行期反射能深访。框架扫私有字段要 opens。类路径代码活在未命名模块，读所有模块；命名模块默认不读未命名模块。分裂包非法。21 没有 `--illegal-access=permit`。`jdk.httpserver` 不是 java.base，模块化项目要 `requires`。

---

### 11、`filter` 调完了，数据滤好了吗？Optional 当字段？

能说出口：没有。中间操作永远懒，终端操作才遍历源。流只能消费一次，第二次 `IllegalStateException`。

往下追：**`parallelStream` 随手加？** 不要。worker 是 ForkJoinPool common pool 的平台线程，I/O 和锁会把池卡住，默认 `CompletableFuture` 也用这个池。CPU 密集纯函数才值得。虚拟线程救不了并行流。数据量小并行开销比计算大。

再追：**Optional 当字段、当参数？** 不当。它是可能空的返回值。自身不要 null。value-based，不要当锁。`orElse(new Costly())` 每次都求值，贵的默认值用 `orElseGet`。`of(null)` NPE，`ofNullable(null)` 是 empty。`get()` 空就炸，用 `orElseThrow`。

再追：**sealed + switch 为什么可以没有 default？** JEP 441：选择器是 sealed 类型，case 覆盖全部许可实现，编译期穷尽。漏一种编不过。分开编译运行时冒出新实现，合成 default 会抛——穷尽是编译期近似。箭头 `->` 不贯穿。null 要显式 `case null`，否则 NPE。dominance：宽模式写前面，后面不可达。

再追：**PECS？** Producer Extends，Consumer Super。`List<? extends Number>` 只读成 Number，不能 add（除了 null）。`List<? super Integer>` 能 add Integer，get 只能当 Object。`List<String>` 不是 `List<Object>` 的子类型，数组协变已经犯过这错。

再追：**ArrayList 无参构造容量是 10 吗？** 无参指向共享空数组 `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`，第一次 add 才到 10。`new ArrayList<>(0)` 用另一份空数组，第一次按 minCapacity 长。之后大约 1.5 倍增长，不是 HashMap 那种翻倍。

---

### 12、ByteBuffer 为什么要 flip？

能说出口：四个索引 `0 ≤ mark ≤ position ≤ limit ≤ capacity`。写完 position 在已写长度、limit 在 capacity。`flip` 把 limit 设成旧 position、position 归零，通道才能读到刚才写进去的。忘了 flip，`channel.write` 写出的是空段。

往下追：**clear 会把内容填零吗？** 不会，只改指针。`compact` 把没读完的挪到下标 0 再接着写。`channel.write` 不保证一次写完，必须 `while (buf.hasRemaining())`。直接缓冲在堆外，短生命周期反复 `allocateDirect` 会打满堆外，靠 Cleaner 回收。

---

### 13、现场：把一条请求串起来

假设你刚写了专栏里那个 `HttpServer` 任务服务。面试官说：为什么 POST 返回 202、任务放 CHM、工人用虚拟线程？

202：请求验收了，没做完。做完再 200，客户端要挂着等睡眠，把「接单」和「干活」缠死。

CHM：多条虚拟线程同时 create/get。HashMap 多线程丢数据。CHM 无 null、单元素有 hb、`putIfAbsent` 建任务、`replace` 按 equals 做状态 CAS。record 当值，状态变化换实例不改字段。

虚拟线程：工人 `Thread.sleep` 模拟 I/O，卸载 carrier，一千个在睡的任务不必一千条 OS 线程。不池。handler 里不用 `synchronized` 包 I/O，Java 21 会钉 carrier。`new Thread().start()` 仍是平台线程。

JSON 的 `Content-Length` 用 UTF-8 字节数，不用 `String.length()`——`char` 是 UTF-16 code unit，emoji 长度是 2。`sendResponseHeaders` 必须在写 body 之前。`InterruptedException` 要恢复中断标记。`HttpExchange` 是 AutoCloseable，try-with-resources 关。绑定 `127.0.0.1` 不是 `0.0.0.0`。

能把这些连着说完，比背「HashMap 底层是数组加红黑树」多走三层。树化阈值 8 和 64 仍然要能报出来——那是第一层，不是最后一层。

---

### 14、对照速查

| 点 | 别答成 | 21 的说法 |
| --- | --- | --- |
| 默认 GC | CMS / Parallel | G1，Region，Humongous ≥ 半区 |
| CHM 结构 | Segment 16 | 桶 + 树，协助扩容 |
| 类加载器 | bootstrap/ext/app | bootstrap/platform/app |
| PermGen | 还在 | 8 没了，Metaspace 在本地内存 |
| 装箱 128 | 一定 == | 规范只保证 -128..127 |
| 虚拟线程 | 更快的 CPU / 要池化 | I/O 并发，不池，21 synchronized 会钉 |
| data race | 和 C++ 一样 UB | 不是 UB，可见性仍没了 |
| `List.of` | 可变 | 不可变，不许 null |
| finalize | 还用 | 18 deprecated for removal |
| JDK Proxy | 能代理类 | 只能接口；类代理要改字节码 |
| CF 默认池 | 「异步就行」 | `ForkJoinPool.commonPool()`，I/O 会打满 |
| AQS 公平 | 一定按排队跑 | `hasQueuedPredecessors` 挡插队；调度仍可不公平 |
| Selector | 比虚拟线程高级 | 21 默认阻塞 Socket + VT；海量空闲连接才需要多路复用 |

---

### 15、`Class.forName` 会跑静态块吗？Proxy 为什么代理不了 ArrayList？

能说出口：单参数 `forName` 会初始化（`<clinit>`）。只要 Class 不要初始化：`forName(name, false, loader)`。`Proxy.newProxyInstance` 第二参数必须是接口，传入 class 抛 `IllegalArgumentException`。

往下追：**`getMethod` 和 `getDeclaredMethod`？** 前者 public 继承链，后者本类声明（含 private，不含父类方法）。`invoke` 的 checked 包在 `InvocationTargetException`。`setAccessible` 打 JDK 内部包，21 要模块 `opens`，否则 `InaccessibleObjectException`。

再追：**equals 进不进 handler？** 进。`save("ab")` 那张参数表：proxy / Method / `args=["ab"]`。`hashCode` 里调 `proxy.hashCode()` 递归到栈溢出。`forName` 两次：第一次 `ExceptionInInitializerError`，第二次 `NoClassDefFoundError`，不重跑静态块。

Spring AOP：有接口默认 JDK Proxy，类代理才走 CGLIB。不是「CGLIB 更快所以默认」。

---

### 16、AQS 的 state 在 ReentrantLock 里是什么？公平锁挡谁？

能说出口：持有计数。0 没人持有；同一线程再 lock 就 +1，超过 `Integer.MAX_VALUE` 抛 Error。释放减到 0 才 `unpark` 后继。

往下追：**非公平怎么插队？** AQS 篇那张三线程表：A 持锁 → B 入队 park → A.unlock 把 state 置 0 并 unpark B → **B 还没跑到 tryAcquire** → C 的 `CAS(0,1)` 成功，owner=C，B 仍在队列。公平锁同一时刻 C 看见 `hasQueuedPredecessors`，不 CAS，B 先拿。`tryLock()` 不走这张表，永远可能插队。

再追：**Condition.await？** 必须已持锁；把节点挂到条件队列；**完全释放**（重入清零）；park；signal 把节点搬回同步队列再 acquire。业务条件仍要 `while`。读锁升级写锁死锁。

---

### 17、`supplyAsync` 默认打在哪个池？thenApply 和 thenCompose？

能说出口：`defaultExecutor()` = `ForkJoinPool.commonPool()`。和 `parallelStream` 抢同一池。阻塞 HTTP 往里丢，池打满。

往下追：**thenApply vs thenCompose？** 类型表：`thenApply(this::findUserAsync)` 得到 `CF<CF<User>>`，`join()` 拿到的还是 CF。compose 摊平。`allOf` 一个失败，计数器那张表证明另一个仍会跑完；`cancel(true)` 不中断 sleep。

再追：**join 和 get？** join 抛 unchecked `CompletionException`；get 抛 checked `ExecutionException`。`cancel(true)` **不中断** 正在跑的 Supplier。`allOf` 一个失败其余不会自动取消。`orTimeout` 只让 CF 异常完成，不取消 I/O。21 把 executor 显式传成 `newVirtualThreadPerTaskExecutor()`。

---

### 18、21 还要不要 Selector？write 一次写得完吗？

能说出口：普通业务 HTTP、每请求下游 I/O，用虚拟线程 + 阻塞 Socket。Selector 留给海量空闲连接、非 VT 运行时、Netty EventLoop。不要 VT 里再 select。

往下追：**`SocketChannel.write`？** 16KB 缓冲、write 返回 4096 那张表：position=4096，remaining=12288。这时 `clear()` 等于丢掉 12KB。循环写到 remaining=0，或等 OP_WRITE 从当前 position 继续。

再追：**JIT 单态调用点失效？** 某调用点只见过 Dog，C2 把 `invokevirtual` 收成直接调并内联；来了 Cat，去优化回解释，再编多态。逃逸分析三种结果：栈上分配、标量替换、同步消除——是实现，不是语言保证。`jstack` 看不见虚拟线程，用 `jcmd Thread.dump_to_file`。

---

### 19、ThreadLocal 会内存泄漏吗？为什么？

能说出口：会。`ThreadLocalMap.Entry` 的 key 是弱引用（ThreadLocal 对象），value 是强引用。ThreadLocal 字段没了，key 被 GC，Entry 变陈旧（`get()==null`），但 value 还被 `Thread → table → Entry` 强引用着。线程池里线程活很久，value 堆积不回收。

往下追：**为什么 key 要弱引用？** 让「你不再用这个 ThreadLocal 时它能被回收」。代价是产生陈旧 Entry。JDK 缓解：`get`/`set` 撞到陈旧槽顺手 `expungeStaleEntry` 清一格，但「碰上才清」，不保证。

再追：**正解？** 用完 `remove()`。线程池 `finally { tl.remove(); }`，否则复用线程带上一个任务的 value，既泄漏又串数据。`ThreadLocalMap` 是开放寻址 + 线性探测，不是 HashMap。`InheritableThreadLocal` 在线程池上几乎必错（值是创建时拷贝，池线程不新建）。虚拟线程别塞 ThreadLocal，跨作用域用 `ScopedValue`。

---

### 20、泛型擦除后还能拿到类型参数吗？List&lt;?&gt; 为什么不能 set？

能说出口：对象的运行时类型擦了（`ArrayList<String>().getClass()` 是 `ArrayList`），但字段/方法/超类子句里的类型参数写在 class 文件的 `Signature` 属性里，反射 `getGenericSuperclass` 能读。`new TypeRef<Map<String,Integer>>(){}` 匿名子类把类型固化进 class 文件——这就是 Jackson `TypeReference` / Guava `TypeToken` 的原理。

往下追：**`List<?>` 为什么不能 `set`？** `get` 返回 `capture of ?`，`set` 要同一个 capture，编译器不敢确认，拒绝。加私有 `<T>` helper，调用点把 `?` 捕获成同一个 `CAP#1`，get/set 面对同一个 T（捕获转换，JLS 5.1.10）。

再追：**型变？** 泛型不变（`List<String>` 不是 `List<Object>`），数组协变（运行时 `ArrayStoreException`）。PECS：`? extends` 只读、`? super` 只写。擦除擦成最左边界（没写就 Object），`get` 处编译器插 `checkcast`。递归泛型 `Enum<E extends Enum<E>>` 让 `compareTo` 只接受同族。

---

### 21、为什么 SimpleDateFormat 不能当 static 共享？java.time 呢？

能说出口：`SimpleDateFormat` 有内部可变 `Calendar` 字段，`format` 分步读写它，多线程共享一个实例会脏读——错日期，甚至 `ArrayIndexOutOfBoundsException`。Javadoc 明说 not synchronized。

往下追：**三条出路？** `synchronized` 包（退化串行）、`ThreadLocal<SimpleDateFormat>`（记得 remove）、换 `DateTimeFormatter`（不可变、线程安全，能 static final）。新代码走第三条。

再追：**Instant / LocalDateTime / ZonedDateTime？** Instant 是 UTC 绝对时刻（存时间戳）；LocalDateTime 没有时区，不对应任何绝对时刻（转 Instant 必须 `atZone` 补时区）；ZonedDateTime 带时区规则含夏令时。存 Instant/UTC，展示才转时区，ZoneId 用 IANA 名不用 `GMT+8`。`Duration`（纳秒/机器）vs `Period`（年月日/人类）：`plus(Period.ofMonths(1))` 到月末会取当月最后一天，`plusDays(30)` 死板 30 天。全是不可变值类型，`plusDays` 不接返回值等于白算。

