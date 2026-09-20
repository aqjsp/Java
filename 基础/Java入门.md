# Java入门

C++ 写 `int a = 1;`，编译器在当前栈帧开一块固定宽度的存储，把 `1` 写进去。Go 的 `var a int = 1` 同理：名字对应一块盒子。Python 写 `a = 1`，做的事情完全不同——堆上找（或 intern）一个 `int` 对象，再把名字 `a` 绑到这个对象上。

Java 两边都有。

```java
int a = 1;                 // 盒子。局部变量表里直接是 32 位 1
Integer boxed = 1;         // 引用。堆上（或缓存里）一个 Integer，变量里是指针
List<Integer> b = new ArrayList<>();
List<Integer> c = b;       // 复制引用，不是复制 ArrayList
```

JLS 21 §4.1 把类型劈成两类：**primitive types** 和 **reference types**。对应两种能放进变量、能当参数、能当返回值的数据：primitive values 和 reference values。另外还有一个没有名字的 **null type**，唯一的值是 `null`，不能声明「null 类型的变量」。

这不是风格问题。后面装箱缓存、`==` 踩坑、泛型不能写 `List<int>`、`synchronized` 锁的是对象头而不是 `int` 本身，全从这个劈法长出来。

![Java 变量：基本类型是盒子，引用类型是指针](../image/java-name-box.svg)

---

## 一、变量是存储位置，不是标签

JLS §4.12 第一句：A variable is a **storage location** and has an associated type。赋值改的是这个位置里的值。

primitive 的值不和别的 primitive 共享状态（§4.2）。`int a = 1; int b = a; a = 2;` 之后 `b` 仍是 1——拷的是位模式。

reference 的值是指向对象的引用，或 `null`。`c = b` 拷的是引用。`b.add(1)` 两边都看见。这和 Go 的 slice 头拷贝、C++ 的指针拷贝是一类；和 `std::vector` 的拷贝构造、Go 的值类型 struct 赋值不是一类。

局部变量在字节码里是局部变量表的槽。`int` / `float` / 引用占 1 槽，`long` / `double` 占 2 槽。对象本身在堆上（逃逸分析可能把未逃逸对象标量替换到栈/寄存器，那是 JIT 的优化，语言规则仍当它是堆对象）。

方法参数：形参是新的存储位置，primitive 拷值，引用拷引用。没有 C++ 那种可 reseat 的 `T&`，也没有 Python「名字换绑」——你改不了调用方那个引用变量指向谁，只能顺着引用改对象。

```java
static void bumpInt(int n) {
    n++;                    // 改的是形参槽，调用方的 a 不动
}
static void bumpList(List<Integer> xs) {
    xs.add(1);              // 改的是堆上的 list，调用方看见
}
```

---

## 二、八种基本类型，宽度钉死

JLS 规定的宽度（与 C++ 的「实现定义」相反，也与 Go 的 `int` 随架构变相反）：

| 类型 | 宽度 | 默认值（字段 / 数组元素，§4.12.5） |
| --- | --- | --- |
| `byte` | 8 位有符号 | `(byte)0` |
| `short` | 16 位有符号 | `(short)0` |
| `int` | 32 位有符号 | `0` |
| `long` | 64 位有符号 | `0L` |
| `char` | 16 位无符号 UTF-16 code unit | `'\u0000'` |
| `float` | 32 位 IEEE 754 | `0.0f` |
| `double` | 64 位 IEEE 754 | `0.0d` |
| `boolean` | 语言层不是「1 位」，JVM 里常用 1 字节 | `false` |

引用类型默认值是 `null`。

只对 **字段和数组元素** 做默认初始化。局部变量没有默认值，使用前必须赋值，否则编译失败（definite assignment，§16）。C++ 未初始化局部是 UB；Go 局部也是零值。Java 在局部上比 Go 严、比 C++ 安全。

`char` 不是「一个字符」。它是 UTF-16 的一个 code unit。emoji、部分汉字在 UTF-16 里是 surrogate pair，两个 `char`。`String.length()` 返回的是 code unit 数，不是用户看到的字数。要按用户感知遍历，用 `codePoints()` 或 `Character.charCount`。

整数溢出：有符号整数运算按补码绕回，**不是** C++ 那种有符号溢出 UB。`Integer.MAX_VALUE + 1 == Integer.MIN_VALUE`。`Math.addExact` 才会在溢出时抛 `ArithmeticException`。

浮点：`0.1 + 0.2` 不等于 `0.3`，这是二进制浮点，不是 Java 独有。钱用 `BigDecimal`，构造时用 `new BigDecimal("0.1")`，不要用 `new BigDecimal(0.1)`——后者把已经圆过的 `double` 再精确化，更错。

`boolean` 不能和整数互转。没有 C 那种 `if (n)`。

---

## 三、装箱：值相等，身份不一定相等

自动装箱是 boxing conversion（JLS §5.1.7）。`Integer x = 1;` 语义上等价于 `Integer.valueOf(1)`，不是 `new Integer(1)`（后者在 9 起 deprecated，21 里构造器仍在但不要用）。

规范对 **常量表达式** 的装箱身份有硬保证：

- `boolean` 的 `true` / `false`
- `char` 在 `'\u0000'` 到 `'\u007f'`
- `byte` / `short` / `int` / `long` 在 **-128 到 127**

任意两次装箱，结果必须 `==`。这是语言规则，不是「HotSpot 碰巧缓存」。实现可以缓存更多，但 **你不能依赖 128 也 `==`**。

```java
Integer a = 127;
Integer b = 127;
System.out.println(a == b);      // true，规范保证

Integer c = 128;
Integer d = 128;
System.out.println(c == d);      // 未规定。HotSpot 默认 false
System.out.println(c.equals(d)); // true，比的是 int 值
```

`Integer.valueOf(int)` 的 Javadoc 允许缓存，常见实现缓存 -128..127，可用 `-XX:AutoBoxCacheMax` 扩大上限，不能缩小到小于 127。比较包装类型一律 `equals`，或先拆箱再比 primitive。

`==` 对引用是身份；对 primitive 是值。两边类型不一致时会拆箱。`Integer x = null; if (x == 1)` 拆箱 NPE——这是真实事故，不是 trivia。

`int` 和 `Integer` 在泛型、集合里不是一回事。`List<int>` 非法（擦除后要成 `List` 的元素类型，primitive 不是引用）。`List<Integer>` 合法，每个元素一次装箱。高频计数用 `int[]` 或第三方原始集合，不要用 `ArrayList<Integer>` 当热路径。

---

## 四、从源码到 `main`

```
Hello.java  --javac-->  Hello.class  --java Hello-->  类加载 / 解释 / JIT
```

![从 .java 到跑起来](../image/java-compile-run.svg)

`javac` 产出的 class 文件魔数是 `CAFEBABE`。里面是常量池和字节码，不是机器码。`java` 启动器找到主类、加载、链接、初始化，再调 `public static void main(String[])`。

HotSpot 默认先解释。方法调用次数、循环回边次数到阈值，C1（客户端编译器）/ C2（服务端）编成本地码。类型假设失效会 **去优化** 回解释，再编。所以「Java 慢」如果指启动后稳态，多数时候已经在跑本地码；如果指第一次请求，解释 + 类加载才是账。

`jshell` 是 REPL，21 里用来试语法可以，不当生产入口。单文件 `java Hello.java`（11 起）内部仍是编译再跑，省略的是你自己调 `javac`。

包名必须和目录对应：`package com.foo;` 的源文件在 `com/foo/` 下。`module-info.java` 是模块，进「类加载与模块」篇。现在记住：没写 `package` 的类在未命名包，别的包 import 不了它——所以生产代码必须有包名。

---

## 五、字符串：不可变，字面量进池

`String` 是 class，值是引用。字面量 `"hi"` 在类加载时进入字符串常量池，相同字面量 `==`。`new String("hi")` 在堆上另造一个，内容相同身份不同。`intern()` 把堆上的塞进池并返回池里那份。

不要用 `==` 比 `String`。`equals` 比 UTF-16 内容。`StringBuilder` 可改；`StringBuffer` 方法带 `synchronized`，单线程用 Builder。循环里 `s = s + x` 每次新 `String`，javac 对 **编译期能看见的** `+` 链会改成 `StringBuilder`，循环里的 `+` 不会自动帮你抽到循环外。

`String` 不可变：任何「修改」都是新对象。所以它可以当 `HashMap` 的 key——内容不变，hashCode 稳定。自己写的可变类当 key，见集合篇。

---

## 六、数组是对象，协变，运行时会查

`String[]` 是引用类型，也是对象，有 `length` 字段（不是方法）。`new int[3]` 三个元素默认 0；`new String[3]` 三个 `null`。

数组协变：`String[]` 是 `Object[]` 的子类型。

```java
Object[] arr = new String[1];
arr[0] = Integer.valueOf(1);   // 编译过，运行 ArrayStoreException
```

这是 JLS 点名的「赋值兼容性在数组上要做运行时检查」（§10.5）。泛型故意做成 **不变**（`List<String>` 不是 `List<Object>` 的子类型），就是为了把这类错误提前到编译期。`List` 没有协变这个口。

---

## 七、和三门语言对照，钉死

| | C++ | Go | Python | Java |
| --- | --- | --- | --- | --- |
| `int` 名字 | 盒子 | 盒子（`int` 宽度随架构） | 标签，绑堆上任意精度 int | 盒子，固定 32 位 |
| `obj` 赋值 | 看类型：值拷 / 指针拷 | 值拷；slice/map/chan 拷头 | 复制绑定 | 复制引用 |
| 局部未赋值 | UB | 零值 | 运行时 NameError | 编译失败 |
| 有符号溢出 | UB（通常绕回） | 静默绕回 | 变更大的 int | 补码绕回，不是 UB |
| 空 | 指针 nullptr；引用不能空 | `nil` 对指针/slice/interface | `None` 是对象 | 只有引用能 `null`，拆箱 NPE |

---

## 八、反模式

- 用 `==` 比 `Integer` / `String`。
- `new Integer(n)`。走 `valueOf` 或自动装箱。
- 局部变量依赖「反正是 0」。字段才有默认值。
- 热路径 `List<Integer>` 做算术。
- `char` 当「一个字」。
- 主类塞未命名包，然后奇怪为什么别人 import 不了。

下一篇把 class、interface、record、enum、分派和 `equals`/`hashCode` 钉完。类型体系对了，对象模型才能谈。
