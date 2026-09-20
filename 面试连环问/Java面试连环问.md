# Java面试连环问

面试不是默写 API。下面每组第一问是能说出口的那句，后面是顺着会追的。答第一句时，后面那些要在嘴里有着落。版本按 **Java SE 21**。把 8 的 PermGen、14 已删的 CMS 拿来当现状，直接停。

---

## 一、基本类型和装箱

**问：`int` 和 `Integer` 差在哪？**

`int` 是 primitive，变量里直接是 32 位位模式；`Integer` 是引用，变量里是指针，对象在堆上（或缓存在 IntegerCache）。赋值拷的东西不一样：`int` 拷值，`Integer` 拷引用。

**追：`Integer a = 127; Integer b = 127; a == b` 一定 true 吗？128 呢？**

127 一定 true。JLS §5.1.7：常量表达式装箱，`boolean` 的 true/false、`char` `\u0000`–`\u007f`、`byte/short/int/long` 的 **-128..127**，任意两次装箱结果必须 `==`。128 规范没保证。HotSpot 默认 128 是两个对象，`==` false，`equals` true。实现可以缓存更多（`-XX:AutoBoxCacheMax`），代码不能依赖。

**追：`Integer x = null; if (x == 1)` 会怎样？**

拆箱 NPE。`==` 一边引用一边 primitive，会拆箱。

**追：为什么不能 `List<int>`？**

泛型擦除后元素类型必须是引用。primitive 不是引用。只能 `List<Integer>`，每个元素一次装箱。热路径计数用 `int[]`。

**追：局部变量 `int n;` 打印 n，是 0 吗？**

编译失败。默认值只给字段和数组元素（§4.12.5）。局部要 definite assignment（§16）。Go 局部是零值，C++ 未初始化是 UB，别混。

---

## 二、equals / hashCode / 分派

**问：为什么覆盖 equals 必须覆盖 hashCode？**

契约：equals 为 true，hashCode 必须相等。HashMap 先按 hash 选桶，再 equals。hash 不同，根本走不到 equals，put 进去 get 不到。

**追：写成 `equals(Dog o)` 行不行？**

不行。那是重载，不是覆盖。`Object.equals` 的签名是 `equals(Object)`。HashMap 调的是 `equals(Object)`，你的 `equals(Dog)` 永远不会被用到。覆盖必须签名相同，写 `@Override`。

**追：子类加字段再覆盖 equals，会怎样？**

对称性容易碎。`Point.equals(ColoredPoint)` 只比坐标，反过来比颜色，一边 true 一边 false。值类型不要为加字段去继承，组合或 sealed + record。

**追：覆盖和重载怎么分？**

覆盖看运行时类型，`invokevirtual`。重载看编译期静态类型，字节码已经选好方法。`Animal a = new Dog(); a.speak()` 是覆盖；`f(a)` 在 `f(Animal)` / `f(Dog)` 里选 `f(Animal)` 是重载。

---

## 三、HashMap 和 ConcurrentHashMap

**问：HashMap 默认容量和负载因子？**

OpenJDK 21：容量 16，负载因子 0.75。阈值 `capacity * loadFactor`。容量保持 2 的幂，下标 `(n - 1) & hash`。

**追：树化阈值是多少？为什么还有 64？**

`TREEIFY_THRESHOLD = 8`，`UNTREEIFY_THRESHOLD = 6`，`MIN_TREEIFY_CAPACITY = 64`。桶里节点到 8 且表长还不到 64，先 resize 不树化，避免扩容和树化打架。哈希均匀时几乎走不到树。树化是防最坏哈希，不是 HashMap 变成 TreeMap。

**追：多线程用 HashMap 会死环吗？**

1.7 头插 + 扩容会。1.8 尾插不再死环，**仍会丢数据**。不是「8 之后 HashMap 线程安全」。多线程用 `ConcurrentHashMap`。

**追：CHM 和 HashMap 还能换着用吗？**

不能。HashMap 允许 null key/value；CHM 不允许，null 被内部用来表示「现在没有」。CHM 迭代 weakly consistent，不抛 CME，也不保证看见并发插入。`size()` 是估计。`get` 再 `put` 不是「没有才插入」，用 `putIfAbsent` / `computeIfAbsent`。`compute*` 过程中不要再进同一张 map。

**追：fail-fast 保证每次都抛 CME 吗？**

不保证。是尽力检测。没抛不等于安全。

---

## 四、异常

**问：checked 和 unchecked 怎么划？**

JLS §11.1.1：`RuntimeException` 及其子类、`Error` 及其子类是 unchecked。其余 `Exception` 是 checked。`Error` 不在 `Exception` 下，`catch (Exception e)` 接不住 OOM。

**追：覆盖方法能不能多抛一个 checked？**

不能（§8.4.8.3）。可以更窄。父 `throws IOException`，子可以 `FileNotFoundException`，不能 `SQLException`。

**追：try-with-resources 谁先关？close 抛异常呢？**

声明顺序反过来关。体里的异常是主异常，close 的进 `getSuppressed()`（§14.20.3）。

**追：`InterruptedException` 空 catch？**

中断状态丢了。捕获后 `Thread.currentThread().interrupt()`，再决定返回或改抛。虚拟线程同一套中断模型。

---

## 五、happens-before

**问：volatile 干什么？**

给这个字段的写和后续读一条 happens-before 边（§17.4.5）。读必须看见这条写，以及写之前同线程里按程序序发生的动作（传递）。它不是互斥，`n++` 仍丢更新。

**追：两个线程一个写 x=1 一个读 x，写在墙上时钟前面，读一定看见 1 吗？**

不一定。没有 hb 边就是 data race，规范允许看见旧值。

**追：正确同步的程序有什么保证？**

所有顺序一致执行都没有 data race，则所有执行看起来都顺序一致。按源码顺序推理合法。

**追：`synchronized` 和 `ReentrantLock` 可见性谁强？**

同一类。`Lock` 文档：成功 lock 等同 monitor 的 Lock，成功 unlock 等同 Unlock。多出来的是 `tryLock`、可中断、超时、多 Condition。Java 21 虚拟线程热路径上长时间阻塞，用 `ReentrantLock` 是为了不钉 carrier，不是因为可见性更强。

**追：非 volatile 的 long 读写原子吗？**

§17.7：可以当成两次 32 位写，线程可能看见撕裂。volatile long / 引用 始终原子。

**追：双重检查为什么要 volatile？**

`new Holder()` 的「写字段」和「把引用赋给 instance」可以重排。另一个线程看见非 null 引用、读字段，读到默认值。volatile 写 instance 把构造里的字段写 hb 过去。能用类初始化（`static final Holder INSTANCE = new Holder()`）就别手写双重检查——§12.4.2 那把初始化锁已经够。

**追：构造器里把 this 发布出去？**

this-escape。final 字段的 freeze 在构造器退出时（§17.5.1）。别人可能读到 final 的默认值。

---

## 六、虚拟线程

**问：虚拟线程是什么？**

还是 `java.lang.Thread`。平台线程一生占用一条 OS 线程；虚拟线程 M:N，不把 OS 线程占到代码结束，可换 carrier。`Thread.currentThread()` 是虚拟线程自己，看不见 carrier。JEP 444，21 正式。

**追：`new Thread(r).start()` 是虚拟线程吗？**

不是。公开构造器造平台线程。`Thread.ofVirtual().start(r)` 或 `Executors.newVirtualThreadPerTaskExecutor()`。

**追：要不要池化？**

不要。JEP 原文：Do not pool virtual threads。池分享贵的资源，虚拟线程不贵。

**追：Java 21 什么情况下卸不下来？**

钉在 carrier 上：① `synchronized` 方法或块里；② native / foreign function。钉住不算错，可扩展性变差。热路径阻塞 I/O 把 `synchronized` 换成 `ReentrantLock`。`Object.wait()` 和部分文件系统调用也不卸载，实现会临时把调度器线程数抬上去补偿。

**追：CPU 密集会更快吗？**

不会。一条虚拟线程算 CPU 时占一个 carrier，和平台线程打满核同类。它解决的是 I/O 并发被 OS 线程上限卡住。

---

## 七、GC 和堆

**问：21 默认收集器？**

G1。堆切成等大 Region，年轻代地址上不必连续。活对象拷到别的 Region（evacuate）。Humongous：对象 ≥ 半个 Region，直接占连续 Humongous Region，算老年代，搬走是最后手段。

**追：Mixed 和 Full 的区别？**

Mixed：一次 STW 里收年轻代 + 一组老年代 Region。Full：整堆 STW 原地压缩，文档说 very slow，是并发标记和 Mixed 扛不住时的退路，不是正常档位。

**追：`MaxGCPauseMillis` 是硬限制吗？**

不是。默认 200 毫秒，是目标。G1 选 collection set 时往上靠。设太小又不给堆，跟不上分配，掉进 Full GC。

**追：PermGen 呢？CMS 呢？**

PermGen 8 没了，类元数据在 Metaspace（本地内存）。CMS 14 移除。别当 21 的答案。

**追：对象头几个字节？**

HotSpot 实现，不是 JLS。64 位压缩类指针常见配置：mark 8 + klass 4，数组再加 length 4。随压缩、对齐、实验特性变。别说成语言规定。

---

## 八、类加载

**问：双亲委派是什么？**

`ClassLoader.loadClass` 默认先问 parent，找不到再自己 `defineClass`。保证 `java.lang.Object` 只有一份。它是默认实现，不是 JVMS 强制算法。SPI、热加载会打破。

**问：Java 9 之后三层加载器？**

Bootstrap（Java 里 `null`）、Platform（`getPlatformClassLoader`）、System/Application（`getSystemClassLoader`）。`String.class.getClassLoader()` 是 null。

**追：`Class.forName` 会初始化吗？**

单参数那个会。只要 Class 不要初始化：`forName(name, false, loader)`。

**追：什么触发 `<clinit>`？**

§12.4.1：new 实例、调这个类声明的静态方法、给它声明的静态字段赋值、用它声明的非常量静态字段。`T.class`、常量变量、`new T[]` 不触发。每个类一把初始化锁，递归同一线程视为已初始化，失败后进错误态不会重跑。

**追：两个加载器加载同一个类名，能互相强转吗？**

不能。定义加载器不同，`Class` 对象不同，强转 `ClassCastException`。

**追：模块 `exports` 和 `opens` 差在哪？**

`exports`：别的模块编译期能引用公开类型。`opens`：运行期反射能深访。框架扫私有字段要 opens，只 exports 不够。类路径代码活在未命名模块，读所有模块；命名模块默认不读未命名模块。分裂包非法。

---

## 九、Stream / Optional / switch

**问：`filter` 调完了，数据滤好了吗？**

没有。中间操作永远懒，终端操作才遍历源。流只能消费一次。

**追：`parallelStream` 随手加？**

不要。worker 是 ForkJoinPool common pool 的平台线程，I/O 和锁会把池卡住。CPU 密集纯函数才值得。虚拟线程救不了并行流。

**追：Optional 当字段、当参数？**

不当。它是可能空的返回值。自身不要 null。`orElse(new Costly())` 每次都求值，贵的默认值用 `orElseGet`。

**追：sealed + switch 为什么可以没有 default？**

JEP 441：选择器是 sealed 类型，case 覆盖全部许可实现，编译期穷尽。漏一种编不过。分开编译运行时冒出新实现，合成 default 会抛——穷尽是编译期近似。

---

## 十、现场：把一条请求串起来

假设你刚写了专栏里那个 `HttpServer` 任务服务。面试官说：为什么 POST 返回 202、任务放 CHM、工人用虚拟线程？

202：请求验收了，没做完。做完再 200，客户端要挂着等睡眠，把「接单」和「干活」缠死。

CHM：多条虚拟线程同时 create/get。HashMap 多线程丢数据。CHM 无 null、单元素有 hb、`putIfAbsent` 建任务、`replace` 做状态 CAS。

虚拟线程：工人 `Thread.sleep` 模拟 I/O，卸载 carrier，一千个在睡的任务不必一千条 OS 线程。不池。handler 里不用 `synchronized` 包 I/O，Java 21 会钉 carrier。

JSON 的 `Content-Length` 用 UTF-8 字节数，不用 `String.length()`。`sendResponseHeaders` 必须在写 body 之前。`InterruptedException` 要恢复中断标记。

能把这些连着说完，比背「HashMap 底层是数组加红黑树」多走三层。树化阈值 8 和 64 仍然要能报出来——那是第一层，不是最后一层。
