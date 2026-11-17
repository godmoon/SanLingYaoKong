# SanLingYaoKong：三菱电机中央空调蓝牙遥控器

三菱电机中央空调（MELRemo / GEMINI 系列无线遥控器）的第三方 Android 蓝牙遥控器。

原 App 的协议是从 APK 反编译后自行实现的，不依赖原 App、原 SDK 或任何厂商服务。

## 当前版本能做什么

- 扫描附近的三菱电机遥控器（名称前缀 `M/R_`、`G/R_`、`M/R2`，或厂商数据 company ID `0x0014`）
- 连接后自动完成「连接确认 → 登录 → 读取状态」
- 访客模式，或 4 位十六进制用户密码登录
- 显示并控制：
    - 开关机
    - 模式：制冷 / 制热 / 除湿 / 送风 / 自动
    - 设定温度（0.5 °C 步进，支持圆盘拖动与 +/-）
    - 风速：自动 / 静音 / 低 / 中 / 高 / 强力 / 急速
    - 上下风向：自动 / 1~5 档 / 摆动
    - 百叶、左右风向、人感（MoveEye）、保持、内部清洁、换气
    - 室内温度、可调温度范围、模式限制与按键锁
- 状态定时轮询（2~30 秒可调）
- 断线自动重连（指数退避），连接失败自动重试
- 记住设备地址、登录方式、轮询间隔等设置
- 通信日志页：原始收发帧、复制、分享、清空

## 构建

环境要求：JDK 17、Android SDK 34。

```bash
export JAVA_HOME=/home/moon/android-sdk/jdk/jdk-17.0.9+9
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

单元测试（协议编解码，不依赖真机）：

```bash
./gradlew testDebugUnitTest
```

## 使用流程

1. 打开 App，进入「设备」页，授予蓝牙权限。
2. 点击「扫描」，让遥控器靠近手机。
3. 点设备卡片，选择访客或输入 4 位密码（例如 `32BC`），连接。
4. 连接成功后自动跳到「遥控」页，状态会定时刷新。
5. 「设置」页可以改登录方式、刷新间隔、自动重连、自动连接，并进入日志页。

## 调试接口（仅 Debug 版）

Debug 包会在 `127.0.0.1:18080` 起一个本地 HTTP 命令桥，可以直接用 `adb` + `curl` 驱动 App，
不用截图或读 UI 树；Release 包不会启动。

```bash
adb forward tcp:18080 tcp:18080
curl -s http://127.0.0.1:18080/help          # 命令列表
curl -s http://127.0.0.1:18080/state         # 连接阶段、扫描列表、完整空调状态
curl -s "http://127.0.0.1:18080/logs?limit=80&grep=RX"
curl -s "http://127.0.0.1:18080/connect?address=58:52:8A:C5:8D:7E&name=M/R_40MAAC_58528AC58D7E&guest=0&password=1752"
curl -s "http://127.0.0.1:18080/control?action=temp&value=24.5"
curl -s "http://127.0.0.1:18080/control?action=power&value=on"
curl -s "http://127.0.0.1:18080/wait?timeoutMs=15000"   # 等设备空闲，方便脚本串行执行
```

支持的 `action`：`power`、`mode`、`temp`（`value` 或 `delta`）、`fan`、`vane`、`louver`、
`rightleft`、`moveeye`、`hold`、`innerclean`、`vent`。返回值是 JSON，控制类命令会附带最新状态。

## 已知限制

- 只实现了家用机型（`O0`）的基础控制；商用 Lossnay 新风、夜间净化、日程、电量统计等尚未接入。
- 管理员工号（user_type=2 + license）尚未在界面开放。
- 未实现自动模式下的自动制冷 / 自动制热细分切换（状态能读出来，控制时按原 SDK 归一成「自动」）。
- 40MAAC 真机实测：开关机、模式、温度、风速、状态回报正常；上下风向、百叶、左右风向等由能力表判定为不支持，
  界面已自动隐藏。手动通过调试接口发送这些命令时，设备仍会回绝（L3 回执码非 0）。
- 原 SDK 的 `setSpeed` 对「强力/急速」（wire 8/9）直接返回 `INVALID_SET`，界面不再提供这两个选项。
- 能力表按机型追加的电力/换气条件块目前只解析到「是否存在」；相关功能页面尚未实现。

## 目录结构

```
app/src/main/java/com/openremo/
├── ble/RemoBleClient.kt        # 扫描、连接、通知、分片写
├── data/
│   ├── ProtocolEngine.kt       # L2/L3 会话与 Job 状态机
│   ├── RemoRepository.kt       # 连接监督、轮询、命令串行化、日志
│   └── Prefs.kt                # 设置持久化
├── protocol/
│   ├── Codec.kt                # L1/L2/L3、登录块、控制/状态编解码
│   ├── Capability.kt           # 登录时读到的机型能力表（j.b）解析
│   └── Model.kt                # 枚举 wire 值、AcState、ControlFlags
└── ui/                         # 单 Activity + 底部导航三页 + 日志页
```

协议细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 后续迭代（按优先级）

1. 真机联调：登录、控制、状态字节序、重连时序。
2. 定时、日程、夜间净化、Lossnay 新风。
3. 电量统计、AI、BLE 传感器等能力表已声明但尚未接入的功能。
4. 桌面小组件、快捷方式、通知栏控制。
5. 多设备管理与快速切换。
