# 通知收纳（HyperOS / MIUI · LSPosed 模块）

Hook 小米 SystemUI，把任意通知**手动收纳**进「不常用通知」。

面向系统：HyperOS 4 / Android 17（同样适用于 HyperOS 1/2、MIUI 14+，只要按下面流程重新定位一次类名）。

---

## 一、先看清能力边界

这个工程我做了两件事，但只有一件是「开箱即用」的：

| 能力 | 状态 |
|---|---|
| 编译出可被 LSPosed 识别的模块 APK | ✅ 已完成并验证 |
| 枚举本机 SystemUI 的**全部类名**（不需要 root） | ✅ 开箱即用 |
| 反射出任意类的**完整方法签名**（在 SystemUI 进程内） | ✅ 开箱即用 |
| 在应用内列出当前通知、手动选出要收纳的那些、状态持久化 | ✅ 开箱即用 |
| 长按通知弹出「收纳」菜单（自带 overlay，不依赖 MIUI 原生菜单实现） | ✅ 定位到长按入口后可用 |
| **收纳后真的折叠进「不常用通知」区** | ⚠️ 必须先用探测模式在你机器上定位到判定点，填一行配置 |

为什么最后一项不能直接给死：

1. **我没有小米真机**。所有代码是在 Linux 沙箱里编译验证的，真机行为必须你来确认。
2. **HyperOS 4 的内部实现我没有可靠资料**。MIUI 的 SystemUI 是 AOSP 的深度魔改，混淆程度、类名、方法签名每个大版本都变，「不常用通知」这条逻辑落点的位置无法靠猜。
3. 所以工程被设计成**两阶段工具**：阶段一在设备上把真实类名和方法签名挖出来，阶段二填配置生效。**不需要改代码重新编译**，只改 JSON。

这个设计不是偷懒——对一个未知且混淆的目标，先探测再 hook 是唯一可靠的做法。

---

## 二、工程结构

```
miui-notif-shelter/
├─ app/src/main/
│  ├─ AndroidManifest.xml              # xposed 元数据（module/scope/sharedprefs）
│  ├─ assets/xposed_init               # 入口类名
│  └─ java/com/notifshelter/miui/
│     ├─ ShelterModule.java            # LSPosed 入口，按模式分流
│     ├─ DexScanner.java               # 纯字节扫 DEX 枚举类名（无隐藏 API）
│     ├─ ClassCatalog.java             # 关键词分组 + 方法签名格式化
│     ├─ Recon.java                    # SystemUI 侧探测 & 报告输出
│     ├─ ReportBridge.java             # SystemUI 侧：把报告分片广播回传给应用
│     ├─ ReconReport.java              # 应用侧：重组分片、落盘、通知界面刷新
│     ├─ HookEngine.java               # 配置驱动的三种 hook 策略
│     ├─ Prefs.java                    # SystemUI 侧跨进程读配置
│     ├─ ShelterPopup.java             # 长按后弹出的收纳菜单
│     ├─ ShelvedKeys.java              # 收纳状态唯一写入方 + 广播同步
│     ├─ ShelterListenerService.java   # 拿当前通知列表（应用侧）
│     ├─ ShelterReceiver.java          # 接收 SystemUI 的收纳/存活/报告广播
│     └─ SettingsActivity.java         # 设置界面
├─ keystore/debug.keystore             # 仅供本地构建的调试签名
└─ dist/notif-shelter-v0.2.1-debug.apk # 已构建好的产物
```

---

## 三、构建

需要 JDK 17 + Android SDK（platform 36、build-tools 36/37）。用 Android Studio 直接打开即可；命令行：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
```

> 两个环境相关的坑，已在工程里绕过，说明一下免得你踩：
> - **compileSdk 用 36 而不是 37**：Android 17 的平台包在 SDK 里叫 `android-37.0`（minor 版本命名），只有 AGP 9.x 认。本模块所有 hook 都走反射，编译期用不到高版本 API，用 36 编译、在 17 上跑没有任何问题。想升到 37 就把 AGP 换成 9.x。
> - **显式指定了 debug 签名**、并关掉了 release 的 `lintVital`：AGP 默认去创建 debug keystore、以及 lint 任务，在某些容器/CI 里会因 JDK 探测 cgroup 信息而 NPE 崩溃，跟模块无关。

---

## 四、安装与启用

```bash
adb install -r dist/notif-shelter-v0.2.1-debug.apk
```

1. **先打开一次应用**。首次启动会写入默认配置（`/data/data/com.notifshelter.miui/shared_prefs/config.xml`）——没有这个文件，SystemUI 侧读不到配置，模块会直接什么都不做。
2. 打开 **LSPosed 管理器 → 模块 → 通知收纳**，启用它。
3. **作用域勾选「系统界面」（com.android.systemui）**。清单里已声明默认作用域，通常会自动勾上。
4. 重启 SystemUI：LSPosed 管理器里对「系统界面」执行重启，或直接重启手机。

验证模块挂上了，两种看法——**手机上不需要电脑**：

- **LSPosed 管理器 → 日志**，搜 `MIUI-Shelter`；
- 或 `adb logcat -s MIUI-Shelter`。

看到 `initZygote：模块已加载`、`命中 SystemUI 进程`、`运行模式 = recon` 就对了。

> 模块里的**生命周期节点**（模块加载 / 读到什么模式 / 探测是否排队 / 接收器是否注册 / 报告是否回传）
> 都通过 `XLog.important()` 强制写进框架日志，所以 LSPosed 日志页里能看到这十来行。
> 报告正文上千行，仍然只在 logcat 和报告文件里，不会刷 LSPosed 的日志。

如果应用界面里「模块最近一次加载」有显示时间，说明存活广播也通了。

---

## 五、核心流程：定位「收纳判定点」

一共四步。**只有第 3 步需要你（或把日志发我）做判断**。

### Step 1 · 生成类名清单

应用里点 **「5. 探测本机 SystemUI 类名」→ 开始扫描 SystemUI 类名**。

它直接读 `/system*/…/SystemUI.apk` 里的 `classes*.dex`，按字节扫 class descriptor。不需要 root，也不会让系统卡顿。结果按 6 组归类：

| 组 | 关注什么 |
|---|---|
| ① 收纳 / 折叠 / 分区 | **最重要**。`uncommon` `shelter` `fold` `collapse` `section` `bundle` `silent` |
| ② 通知集合与管线 | `NotifCollection` `NotifPipeline` `Coordinator` |
| ③ 排序 / 重要性 | `Ranking` `Importance` `Interruption` |
| ④ 分组与堆叠 / 视图 | `GroupManager` `StackScroll` `Shelf` `NotifRow` |
| ⑤ 长按菜单 / 手势 | `MenuRow` `LongClick` `Guts` |
| ⑥ MIUI 定制类 | 名字带 `miui` 的 |

### Step 2 · dump 方法签名 → 在应用里直接看报告

切到 **「探测模式」**（第 2 节里选），**重启 SystemUI**。探测会在 12 秒后自动跑，把**组① 的前 60 个类**的方法签名全部 dump 出来。

想 dump 指定的类：先在应用里点 **「把候选类写入 recon_targets」**（写入组① 前 60 个），或直接改 `recon_targets`。

**取报告不用碰电脑、也不用翻目录**：等 12 秒后打开应用，第 7 节 **「探测报告」** 里就会显示正文和状态；点 **「拉取报告」** 可以再要一次（探测完成后 SystemUI 自己也会主动推一次）。

> 原理：报告由 SystemUI 侧分片广播回传（`ReportBridge` → `ReconReport`），走的是内存副本，
> 所以哪怕文件三处路径全写不进去也拿得到。报告会存在应用私有目录，退出重进还在。

要在手机上发给别人：点 **「保存到 Download」**（落在 `Download/miui_shelter_recon.txt`）或 **「分享」**。

<details>
<summary>备选：adb 抓日志（有电脑时）</summary>

```bash
adb logcat -c
adb logcat -s MIUI-Shelter > recon.log        # 重启 SystemUI 后跑一会儿，Ctrl-C
# 报告文件也可以直接拉（注意 uid 1000 写不进 /data/local/tmp，实际落点看 logcat 里的提示）
adb pull /data/local/tmp/miui_shelter/recon.txt
```

报告会按顺序尝试写三处：`/data/local/tmp/miui_shelter/recon.txt` → SystemUI 的外部目录 `recon.txt` → SystemUI 私有目录。
前一处对 SystemUI 来说不可写（它是 uid 1000，而 `/data/local/tmp` 是 `shell:shell 0771`），通常会落到后两处；logcat 里会打印实际落点。
</details>

### Step 3 · 判断哪个方法是「收纳判定」

报告存成文件后（「保存到 Download」）在电脑上过滤，或直接在应用界面里往下翻：

```bash
awk '/--- ① /{f=1} /--- ② /{f=0} f' recon.txt
```

然后在方法签名里搜判定型名字：

```bash
grep -nE "shouldHide|shouldShow|isUncommon|isSilent|isFold|isCollaps|shouldCollaps|isMinimi|getSection|getBundle" recon.txt
```

**一个方法要满足这四条，才是我们要的判定点：**

1. 名字像判定（`is*` / `should*` / `get*`）
2. **返回 `boolean`**（或返回一个「分区/分组」枚举，用于 `hook_field`）
3. 参数里有**通知实体**（`NotificationEntry` / `NotifStub` / `SbnKey` 之类），或它在某个 per-notification 对象的实例方法里
4. 注释里能看到它在**布局/绑定阶段**被调用（如果 dump 里有调用点的线索）

判断顺序建议：
- 先找**返回 boolean 且带 Entry 参数**的 `should*`/`is*` → 用 `hook_bool`
- 找不到就看有没有 **`section` / `bundle` / `group` 类型的字段**被赋值 → 用 `hook_field`
- 再找不到，就找栈布局里 `add/remove` 某个 section 的方法 → 改成在方法末尾改字段

### Step 4 · 填配置 → 切收纳模式

在应用 **「6. 高级：hook 配置」** 里填 JSON，保存，重启 SystemUI，模式切到「收纳模式」。

---

## 六、hook 配置格式

三种策略，都是 JSON 数组。全部字段都是可选的，除 `cls` / `method`。

### 通用的 key 解析

每个 hook 都要能认出「这条 hook 命中的是哪条通知」，靠这几个字段：

| 字段 | 含义 |
|---|---|
| `argKeyIndex` | 第几个参数携带通知 key。`-1` 表示参数里没有，从 `this` 对象上取（**长按入口通常用 -1**） |
| `keyGetter` | 从这个对象取 key 的 getter。**支持链式**，如 `"getEntry.getKey"`；默认 `getKey` |
| `keyField` | 或者直接读字段名 |
| `gate` | `shelved`（默认）只对已收纳通知生效；`always` 一律生效 |

解析顺序：指定参数 → `this` 对象；每个候选对象再依次试 `keyField` → `keyGetter` → 兜底字段 `key`。

### 【1】hook_bool —— 核心折叠手段

强制某个判定方法返回指定布尔值。

```json
[
  {
    "cls": "com.android.systemui.statusbar.notification.stack.SomeStackController",
    "method": "shouldHideInUncommonSection",
    "params": ["com.android.systemui.statusbar.notification.collection.NotificationEntry"],
    "ret": true,
    "gate": "shelved",
    "argKeyIndex": 0,
    "keyGetter": "getKey",
    "log": true
  }
]
```

- `params` 省略或 `null` → hook 该名字的**全部重载**（不确定签名时很好用）
- `log: true` 会在命中时打日志（前 20 条），用来确认配置是否真的生效

### 【2】hook_field —— 另一种折叠手段

方法执行后，把对象上的字段改成指定值。

```json
[
  {
    "cls": "com.android.systemui.statusbar.notification.collection.SomeEntry",
    "method": "setSection",
    "params": ["int"],
    "field": "mIsUncommon",
    "value": true,
    "onResult": false,
    "gate": "shelved",
    "argKeyIndex": -1,
    "keyGetter": "getKey"
  }
]
```

- `onResult: false` → 改 `this` 上的字段；`true` → 改**返回值对象**上的字段
- `value` 支持 boolean / int / long / 字符串

### 【3】hook_entry —— 长按通知的入口

```json
[
  {
    "cls": "com.android.systemui.statusbar.notification.row.SomeRowController",
    "method": "onLongClick",
    "params": [],
    "argKeyIndex": -1,
    "keyGetter": "getEntry.getKey",
    "action": "menu",
    "title": "通知收纳"
  }
]
```

- `action: "menu"` → 弹出自带菜单（收纳 / 移出 / 复制 key）
- `action: "toggle"` → 直接切换 + Toast
- 自带菜单是**独立 overlay 窗口**，不是注入 MIUI 原生菜单。原因：原生菜单的 item 类型和构建方式是版本相关的、最容易坏；只要找到长按入口，自带菜单就一定能弹。

---

## 七、如果判定点其实在 system_server 侧

这点必须提醒你。MIUI 的「不常用通知」有两种可能的实现层次：

- **SystemUI 侧实现**：SystemUI 自己决定哪条通知折叠起来 → 本模块的 hook 直接能搞定。
- **框架侧实现**：`NotificationManagerService`（跑在 `system_server`）在 ranking 阶段就把通知标成低优先级，SystemUI 只是照着渲染 → **只 hook SystemUI 可能改不动分组语义**，只能改显示。

怎么区分：如果组① 里能找到完整的「判定 → 分组 → 渲染」链路，是前者；如果 SystemUI 侧只有渲染、拿到的 ranking 里就已经带了标记，是后者。

真要动框架侧，扩展方法（工程量不大）：
1. `AndroidManifest.xml` 的 `xposed_scope` 数组里加上 `android`
2. `ShelterModule.handleLoadPackage` 里加一条 `"android".equals(lpparam.packageName)` 的分支，只挂 `com.android.server.notification.*`
3. 探测同样能复用——`Recon` 里把扫描目标换成 `/system/framework/framework.jar` 和 `miui-framework.jar`

需要的话把 `framework.jar` 的相关日志发我，我加。

---

## 八、排查

| 现象 | 原因 / 处理 |
|---|---|
| logcat 里连 `initZygote` 都没有 | LSPosed 没启用模块，或没重启 SystemUI。确认作用域勾了「系统界面」 |
| 「模块最近一次加载」显示的模式是 `off`，但应用里选的是探测/收纳 | SystemUI 启动那一刻读到的还是旧模式。**模式只在 SystemUI 进程启动时读一次**，改完必须重启 SystemUI |
| 想确认模块到底走到哪一步 | LSPosed 管理器 → 日志 → 搜 `MIUI-Shelter`。重点看 `运行模式 = ?`、`报告回传接收器已注册`、`探测已排队`、`已回传报告 N 个分片` 这四行，缺哪行就说明卡在哪一步 |
| `读不到模块配置` | 没先打开一次应用。打开一次生成 `config.xml` 后重启 SystemUI |
| 应用里「模块最近一次加载」一直是空 | 存活广播没到。确认模块已启用且 SystemUI 重启过；广播是 SystemUI 启动 6 秒后发的 |
| `找不到类，hook 跳过: xxx` | 类名填错了，或 `cls` 是接口/抽象类。用探测报告里的**完整二进制类名**（点号分隔） |
| `方法不存在，hook 跳过` | 方法名或 `params` 不对。`params` 先省略（hook 全部重载）确认方法存在，再补精确签名 |
| hook 都成功了但状态栏没变化 | 说明这个判定点不是真正的渲染入口。回到 Step 3，换一个候选；重点找**栈布局阶段**调用的方法 |
| 收纳后在应用里看得到、状态栏不折叠 | 大概率是第七节说的框架侧实现，或者需要**重新打开通知栏**才重绘（hook 只在布局时生效） |
| 改完配置没反应 | hook 结构只在 SystemUI 进程启动时注入一次。改 `hook_bool`/`hook_field`/`hook_entry` 后必须重启 SystemUI；**已收纳列表是实时生效的**，不用重启 |
| 点「拉取报告」回「还没有报告」 | SystemUI 内存里确实没有：模式没切到「探测模式」，或改完模式没重启 SystemUI，或 12 秒还没到 |
| 点「拉取报告」一直是「正在接收分片 x / y」 | 应用进程在中途被杀了，分片没凑齐。再点一次「拉取报告」即可（SystemUI 会重发全部） |
| 模块从 0.1.0 升到 0.2.0 后拉不到报告 | 老版本没有 `recon_token`。打开一次应用会自动补上，然后再重启 SystemUI |

清空所有收纳记录：

```bash
adb shell am broadcast -a com.notifshelter.miui.action.CLEAR_SHELVE -p com.notifshelter.miui
```

---

## 九、设计取舍（为什么这么做）

- **不硬编码类名**。这是最重要的一个决定。硬编码在 HyperOS 4 上必然是错的，而「探测 + 配置」让你换机型/换版本时只改 JSON。
- **收纳状态由应用独占写入**。SystemUI 和模块应用是不同 UID，Android 10+ 下互相直接写 SharedPreferences 不可靠。走广播回传最简单也最稳。
- **应用侧手动选通知**是刻意做的保底路径。即使折叠 hook 完全没适配，「要收纳哪些通知」这件事从第一天就能用。
- **长按用自带 overlay 菜单**，不注入原生菜单 item。原生 item 的构造签名是版本相关的、最容易碎。
- **DEX 字节扫描**而不是 `dexFile.entries()` 隐藏 API。虽然代码多一点，但不依赖任何隐藏 API，跨版本更稳，也不需要 root。
- **不挂 system_server**（默认）。作用域越小越稳，先确认是否真的需要。

---

## 十、下一步

把探测报告（应用里「保存到 Download」出来的 `miui_shelter_recon.txt`）里有价值的两段发我：

1. **组① 的完整类名列表**
2. **你觉得可疑的类的方法签名**（比如所有名字带 `uncommon`/`fold`/`section` 的类）

我按真实签名把 `hook_bool` / `hook_field` / `hook_entry` 三份配置写出来，你贴进应用保存即可生效——不用重新编译。