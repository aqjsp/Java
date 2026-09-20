# Stream 与现代语法

`list.stream().filter(...).map(...).toList()` 看起来像一条链，跑起来不是「先滤成一张新表，再 map 成一张新表」。`java.util.stream` 包文档把结构钉死了：一个源，零个或多个 **中间操作**，一个 **终端操作**。中间操作永远懒，源上的遍历在终端操作执行时才开始。

这和 Python 的生成器、Go 里自己写的 pipeline 函数一个意思：拉，不是推。差别是 Java 把并行、短路、encounter order 写进了同一套 API，用错的方式也写进了同一份文档。

---

## 一、流水线什么时候真的跑

```java
Stream<String> s = names.stream()
        .filter(n -> n.startsWith("A"))
        .map(String::toLowerCase);
// 到这里一个元素都还没碰过
List<String> out = s.toList();     // 终端操作，现在才遍历
```

中间操作返回新的 `Stream`。`filter` 并不过滤，它造一个「被遍历时才过滤」的流。没有终端操作，整条链是死的。这也是为什么调试时在 `map` 里打日志，不接 `toList` / `count` / `forEach` 就什么都没有。

流 **只能消费一次**。文档：elements are only visited once；像 `Iterator`，要再走一遍得从源重新造流。`s.toList(); s.count();` 第二次抛 `IllegalStateException`。

源可以是集合、数组、生成器、I/O。`Stream.generate` / `iterate` 可以无穷。无穷流要能结束，链上得有短路操作：`limit`、`findFirst`、`findAny`、`anyMatch` 这类。短路是「有它才可能在有限时间结束」的必要条件，不是充分条件——`filter` 永远不让人过、后面再 `findFirst`，仍可能不结束。

`peek` 是中间操作，也懒。拿它当 `forEach` 用、还不接终端，什么都看不到。`peek` 的设计用途是调试，不是业务副作用。

`count()` 在某些流水线上能直接拿源的 size（没 filter 的 `list.stream().count()`），有 filter 就必须遍历。不要假设 count 是 O(1)。

---

## 二、别在流水线里改源

包文档把 **non-interference** 写得很死：行为参数（lambda）如果改了、或导致别人改了非并发的源，叫干扰。这条对串行、并行都成立。源不是并发容器时，跑流水线过程中改源，可以异常、可以错答案、可以根本不遵守规范。

终端操作 **开始之前** 改源是允许的，改完的内容会反映到被覆盖的元素上。开始之后不要动。

```java
List<String> names = new ArrayList<>(List.of("a", "ab", "b"));
names.stream()
     .filter(n -> n.startsWith("a"))
     .forEach(n -> names.remove(n));    // 干扰，可能 CME，可能错
```

副作用：`peek` 和 `forEach` 存在，不等于鼓励你在 `map` 里改外部的 `List`。并行时这些副作用没有遇到顺序，也没有 happens-before 以外的同步。统计用 `reduce` / `collect`，不要用 `forEach` 往共享 `ArrayList` 里 add。

有状态的中间操作（`sorted`、`distinct`、`limit`）要看见更多元素才能吐后面的；无状态的（`filter`、`map`）来一个处理一个。有状态操作在并行流上更贵，也更容易把 encounter order 的账做复杂。`sorted` 必须把整段缓冲完再往下吐，无穷流接 `sorted` 不会结束。

`distinct` 按 `equals`。并行 + 有序的 `distinct` 要保第一次出现的那个，贵。`unordered().distinct()` 可以快一些。

---

## 三、顺序、并行、encounter order

`Collection.stream()` 是串行；`parallelStream()` 或 `stream().parallel()` 是并行。并行用 ForkJoinPool 的 **common pool**，默认并行度是处理器数减一量级。虚拟线程救不了这条——并行流的 worker 是平台线程。CPU 密集的纯函数计算才值得 `parallel`；I/O、锁、同步容器，并行流会把 common pool 卡住，别的也用这个池的代码一起死（`CompletableFuture` 默认也用 common pool）。

有 encounter order 的源（List、数组、有序 stream）上，`findFirst`、`limit`、`toList` 要尊重顺序。`findAny`、`forEach` 不保证。`forEachOrdered` 保证，并行时会牺牲一部分并行。`unordered()` 等于告诉实现可以丢掉顺序约束，`distinct` / `limit` 在并行下会快一些。

`IntStream.range(0, n).parallel()` 是有序的。`Stream.generate` 默认无序。

不要把 `parallel()` 当加速器随手按。测过再留。数据量小、每元素几纳秒，并行的拆分/合并开销会比算本身大。阻塞 I/O 放进并行流，等于用 common pool 当线程池跑 I/O，核数条线程卡住，整个进程的并行流和默认 CompletableFuture 一起停。

自己指定池：把并行流包在 `ForkJoinPool` 的 `submit` 里跑，这是实现碰巧看当前线程的池，不是 API 契约。21 里别靠这个当生产方案。I/O 并发用虚拟线程；CPU 用自己的 `ForkJoinPool` 或平台线程池，别借用 common。

---

## 四、reduce 和 collect

### 1、reduce

`reduce` 是折叠。三个参数的形式：identity、accumulator、combiner。并行时 combiner 必须和 accumulator 一起满足结合律，identity 必须是真正的单位元。`reduce(0, Integer::sum)` 可以；`reduce(new ArrayList<>(), (a, t) -> { a.add(t); return a; }, ...)` 这种可变累加，identity 每次该是新容器，用错会把同一个 list 当 identity 用，并行下直接烂。可变累加走 `collect`。

两参数 `reduce(BinaryOperator)` 返回 `Optional`，空流是 empty。三参数返回 identity 类型，空流返回 identity。

### 2、Collector 四件套

接口文档：

- `supplier`：新建结果容器
- `accumulator`：把一个元素拧进容器
- `combiner`：两个容器合成一个
- `finisher`：最后变一次形（`IDENTITY_FINISH` 时可以是恒等）

串行：一个容器，逐个 accumulate。并行：每片一个容器，再 combine。所以 `Collectors.toList()` 能并行，`forEach(list::add)` 不能。

`toList()`（Stream 自己的终端操作，16 起）产出不可变 List，顺序保留。`Collectors.toList()` 产出可变 `ArrayList`，**不** 保证具体类型——文档只保证是 `List`。要指定类型用 `toCollection(ArrayList::new)`。`Collectors.toUnmodifiableList()` 不可变。

`groupingBy` 默认 HashMap + List；要并发用 `groupingByConcurrent`，collector 带 `CONCURRENT` 特征，源也得是并发的，否则又回到干扰问题上。`groupingBy` 的下游 collector 可以再 `mapping` / `counting` / `reducing`。

`joining` 只对 `CharSequence`。`partitioningBy` 是 `groupingBy` 的布尔特化，结果 Map 一定有 `true` / `false` 两个 key，即使某一边是空 List。

`Files.lines` 返回的流包着文件描述符，必须关。try-with-resources 包这个 `Stream`，不是包 `toList` 之后的那张表。纯内存的 `list.stream()` 不用关。

---

## 五、Optional：返回值，不是字段

`Optional<T>` 是可能空的返回值容器。类文档把它标成 value-based：相等的实例当可互换，**不要拿它当同步锁**。`Optional` 自身不要是 `null`——方法要么返回装了值的 Optional，要么返回 `empty()`，不要 `return null`。

```java
Optional<User> find(String id);

User u = find(id).orElseThrow(() -> new NotFound(id));
String name = find(id).map(User::name).orElse("anonymous");
```

- `of(x)`：`x` 是 null 就 NPE。你已经确定非 null 才用。
- `ofNullable(x)`：null 变成 empty。
- `orElse(default)`：默认值立刻求值。
- `orElseGet(supplier)`：empty 时才调 supplier。默认值很贵时用这个。
- `orElseThrow()`：empty 抛 `NoSuchElementException`。有业务异常就 `orElseThrow(YourEx::new)`。
- `map`：映射结果是 null 则变成 empty。
- `flatMap`：映射已经返回 Optional，不再包一层。
- `get()`：empty 就炸。能用上面那些就别 `get`。Java 10 的 `orElseThrow()` 无参就是更明确的 `get`。
- `ifPresent` / `ifPresentOrElse`（9）：有值做事，empty 走另一条。
- `or`（9）：empty 时换另一个 Optional。
- `stream()`（9）：empty 变空流，有值变单元素流，方便 `flatMap(Optional::stream)`。

不要用 Optional 当字段、当方法参数、当 Map 的 value。字段空用 `null` 或空集合；参数空用重载或专门的空对象。Optional 的开销是多一个对象，语义上它表达的是「返回值可能没有」，不是「所有可能缺的东西」。`Optional<Integer>` 不能区分「没有」和「值为 null」，本来就不该把 null 装进去——`ofNullable(null)` 是 empty。

`OptionalInt` / `OptionalLong` / `OptionalDouble` 避免装箱。Stream 的 `IntStream.findFirst` 返回 `OptionalInt`。

---

## 六、record、sealed、模式匹配 switch

`record` 在面向对象篇已经钉过：final 类、分量不可变、`equals`/`hashCode`/`toString` 按分量。作为 sealed 的许可实现很好用，因为 record 已经是 final。

`sealed interface Shape permits Circle, Rect {}` 把实现者名单钉死。许可类型必须和 sealed 类型同一模块（未命名模块则同一包）。这不是修饰符游戏：模式匹配 switch 的穷尽性靠它。

JEP 441（21 正式）把 switch 从「几个 case 标签 + fall-through」扩成模式匹配。要点：

**穷尽。** switch **表达式** 必须覆盖选择器的所有可能值，成功求值才能交出一个值。带 pattern 或 `null` 标签的 switch **语句**，或者选择器不是老那套（`char`/`byte`/`short`/`int` 及其包装、`String`、enum）时，语句也要穷尽。

```java
sealed interface S permits A, B, C {}
final class A implements S {}
final class B implements S {}
record C(int i) implements S {}

static int test(S s) {
    return switch (s) {
        case A a -> 1;
        case B b -> 2;
        case C c -> 3;
        // 不用 default。permits 名单就是全部
    };
}
```

漏了 `B`，编译失败。运行时若 classpath 上出现编译期没见过的实现（分开编译），编译器插入的合成 `default` 会抛。穷尽是编译期近似，不是运行时封印。

**null。** 老 switch 选择器是 null 就 NPE。现在可以 `case null ->`。没有 null 标签、又没有总管一切的模式时，null 仍 NPE。`case null, A a ->` 可以把 null 和某种类型写在一起。

**dominance。** 更宽的模式写在前面，后面的标签不可达，编译失败。`case String t` 后面再写 `default`，`default` 被主导。`case Object o` 几乎主导一切（除了 null，除非你也写了）。

**when。** `case C c when c.i() > 0 ->`。模式变量在 guard 里可见。guard 为 false 时这个 case 不匹配，继续往下。几个 `when` 都不中且没有剩余 case，穷尽检查仍按类型覆盖算——guard 失败不是漏类型，可能在运行时掉进合成 default。

**fall-through。** 箭头形式 `->` 不贯穿。冒号形式里，贯穿一个 **声明了模式变量** 的 case 是禁止的。不要把老的 case 贯穿习惯带进模式匹配。

`instanceof` 模式：`if (o instanceof String s)` 在 true 分支里 `s` 已经是 String，不必再强转。作用域规则和短路有关：`&&` 右边能看见左边绑上的变量；`||` 不行。`if (!(o instanceof String s)) return; use(s);` 在 16+ 合法，s 在后面可见。

这些语法不是「新写法更好看」。sealed + record + switch 穷尽，等于把代数数据类型接到 Java 类型系统上。漏一种实现，编译器打你，而不是运行到 default 才发现。Visitor 模式那套双分派，很多场合可以换成这一组。

文本块、`switch` 表达式、record、pattern matching，是 15–21 连着落地的。新代码能用表达式 switch 就不要再写一串 `if (x instanceof Foo)` 再强转。

---

## 七、完整走一遍：一条并行流为什么把服务打停

```java
List<URL> urls = loadUrls();                // 几千个
long ok = urls.parallelStream()
        .filter(u -> u.getHost().endsWith("example.com"))
        .map(u -> fetch(u))                 // 阻塞 HTTP
        .filter(Objects::nonNull)
        .count();
```

1. `parallelStream` 把工作切到 ForkJoinPool.commonPool。默认大约 `availableProcessors() - 1` 条平台线程。
2. 每条 worker 在 `fetch` 上阻塞。common pool 的线程全部卡住。
3. 同一 JVM 里别的 `parallelStream`、默认 `CompletableFuture.supplyAsync` 也用这个池，一起停。
4. 虚拟线程救不了：这些 worker 是平台线程，不是虚拟线程。
5. 正确写法：`urls.stream()` 串行；或者 `Executors.newVirtualThreadPerTaskExecutor()` 里每个 URL 一个任务；CPU 密集的纯计算才 `parallel`。

把 `fetch` 换成 `n * n` 这种纯函数，几千个整数，parallel 才可能有收益。I/O 和 parallel 叠在一起，是把线程池模型用错地方。

---

## 八、和三门语言对照

| | C++ | Go | Python | Java 21 |
| --- | --- | --- | --- | --- |
| 流水线 | ranges（20） | 手写 / 泛型函数 | 迭代器 / 生成器 | Stream，中间懒、终端触发 |
| 可空 | 指针 / `optional` | 指针 / comma-ok | `None` | 引用 null；返回值用 Optional |
| 代数数据 | variant / 手写 visitor | 接口 + 类型 switch | 鸭子 | sealed + record + 穷尽 switch |
| 并行折叠 | 自己管 | 自己管 | 基本不管 | `parallel()` + Collector，worker 是 common pool |

---

## 九、反模式

- 造了 Stream 不接终端操作，以为 `filter` 已经跑了。
- 一条流走两次。
- `parallelStream()` 里做 I/O 或抢锁。
- `forEach` 往共享 `ArrayList` add。
- 流水线执行中改源 List。
- Optional 当字段、当参数、`return null`。
- `orElse(new Costly())` 每次都 new，该用 `orElseGet`。
- 模式匹配 switch 不穷尽，靠运行时 default 混。
- 老 switch 的 fall-through 写进 pattern case。
- `sorted()` 接在无穷流上。
- 用 `peek` 写业务副作用。

进阶四篇到此。下面用 JDK 自带的 `HttpServer` 和虚拟线程，把这些规则收进一个能跑的服务，不引入 Spring。
