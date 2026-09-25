# FairPlay 成功方法研究报告

> 研究对象：Apple FairPlay 在 AirPlay 生态中的实际作用，以及第三方发送端（sender）要「成功」需要做什么。
> 面向本项目 AirPlayDroid（Android 发送端，GPLv3）。
> 所有结论都尽量落到**一手来源**（源码 / 协议规范 / 项目文档）上，并标注置信度。
> 撰写日期：基于 2026 年可获取的源码快照。

---

## 0. 结论摘要（TL;DR）

1. **「FairPlay 不是一件事」是本报告最重要的结论。** 至少存在 4 条互不相同的技术路径：FairPlay SAP 握手、RAOP 音频的 ekey 密钥封装、MFiSAP（`/auth-setup`）、以及 playfair 解密算法。它们保护的东西、需要的角色、今天的死活状态都不同。混为一谈会得出完全错误的工程判断。

2. **本项目 README 的「不需要 FairPlay」是对的，但只对 AirPlay 2 / HomeKit 配对这条路成立。** 原文其实限定了范围（"On tvOS 26.6"、"at least for the Apple TV tested"），但标题句 "Screen mirroring works, and needs no FairPlay" 读起来像普遍结论。**对旧版（AirPlay 1）镜像，这个结论不成立。**

3. **旧版 AirPlay 1 屏幕镜像确实需要 FairPlay，这一判断是正确的。** 证据：`doubletake` 是一个**发送端**，它实现了完整 FairPlay SAP，并把 **AppleTV3,2（2013 第三代，AirPlay 1 legacy 设备）**列在已验证设备表中。它自己的测试接收器文档写道：AppleTV3 profile「authenticate the FairPlay key, decrypt legacy AES-CTR video」——即旧版视频流是 **AES-CTR 加密**，密钥来自 FairPlay。（置信度：高）

4. **AirPlay 1 镜像的密钥封装格式已完全公开，且 72 字节 `FPLY` ekey 布局可从公开抓包直接验证。** 我把 nto 规范里 `POST /stream` 的 `param1` 和 RAOP `a=fpaeskey` 两个真实 base64 blob 解码后，字节布局与 `doubletake` 源码中的 `wrapFairPlayKey` **逐字段吻合**。这是本报告最硬的一条证据。（置信度：高）

5. **SAP 握手是「m1→m2→m3→m4」四消息 RTSP 交换，真正的难点在 m2→m3 之间的 128 字节挑战 → 20 字节响应。** 该算法已被完整逆向并有 6 种语言的可移植实现（Go/C/Rust/C#/**Kotlin**/Python），~500 KB 表格，**无依赖**，LGPL-3.0-or-later。**Kotlin 移植版直接可用于本项目。**（置信度：高）

6. **该 Kotlin 实现只支持 FairPlay mode 3**，且这是**唯一被观察到的 mode**。m2 的 byte 13 选择 mode，不同 mode 用不同 CBC IV 和 AES round key，同一个挑战会产出完全不同的响应。（置信度：高）

7. **「冻结重放」的 m3 会被严格接收端拒绝**（`RTSP/1.0 466 Key Management Error`），必须用 per-session 的 `NewFPSAPSession` 生成新的 local SAP。（置信度：中——症状单一来源）

8. **对本项目的直接含义**：`/play`（视频 URL 移交）这条路确实不需要 FairPlay，现有代码是对的；但 README roadmap 里「镜像到 PIN / transient 配对接收端」这一项**低估了工作量**——真正的缺口是**整条 legacy 镜像路径**（FairPlay + fp-setup + AES-CTR 视频 + `/stream`），远不止配对方式。（置信度：高）

9. **许可证上是可行的**：`objevovat` 的 FairPlay SAP 实现是 LGPL-3.0-or-later，而本项目是 GPLv3。LGPL-3.0 与 GPLv3 兼容，可以合法合入。但 playfair 是 GPL，RPiPlay 自己声明「The legal status of that library is unclear」。**法律风险见 §10。**（置信度：高，法律判断非法律意见）

10. **RAOP 音频（`et=1`）不需要 FairPlay**：它只是用接收端 RSA 公钥做 OAEP 加密 16 字节 AES key。这是最简单的路径，且 AirPort Express 的私钥 2004 年就被逆向公开了。（置信度：高）

---

## 1. 概念澄清：FairPlay 不是一件事

这是全报告的地基。把下面 5 项混为一谈，是所有错误判断的根源。

| 名称 | 保护什么 | 谁需要它 | 今天还用吗 |
|---|---|---|---|
| **FairPlay SAP v1**（`/fp-setup`，FPLY v3） | 镜像 / 音频流密钥的**认证与封装** | **发送端与接收端都要** | AirPlay 1 legacy 路径仍然使用 |
| **FairPlay SAP v2.5**（`/fp-setup`，版本字节 `0x02`） | 同上，新版本记录格式 | 发送端与接收端 | 旧版；未带 `X-Apple-ET` 时 HomePod 会提供它 |
| **RAOP ekey**（`a=fpaeskey` / `rsaaeskey`） | 音频载荷的 AES 密钥传输 | 发送端封装、接收端解封 | 仍在用（`et=3/5`） |
| **MFiSAP**（`/auth-setup`，`et=4`） | 第三方 MFi 硬件认证 | **只有持 MFi 芯片的硬件** | 在用，但**软件无法合法实现** |
| **playfair** | 逆向出来的 **FairPlay 解密算法** | 主要是**接收端**解密 | 历史产物，见 §4 |

**关键区分**：SAP 是「证明你是合法发送端」的**认证握手**；它本身不解密任何内容。`objevovat` 项目明确写道：

> This is an **authentication handshake, not FairPlay Streaming DRM.** It decrypts no content and extracts no content keys.

这也意味着：**FairPlay SAP ≠ 播放受 DRM 影片**。要播 iTunes 商店的受保护内容，那是另一套（FairPlay Streaming / FPS），与本报告的镜像/音频路径无关。

### 1.1 为什么「不需要 FairPlay」在某些情况下确实成立

因为 **HomeKit 配对（pair-setup / pair-verify）在 AirPlay 2 时代接管了认证职责**。`doubletake` 的代码把这件事写得很清楚——镜像视频流的密钥来自 pair-verify 的 ECDH 共享密钥：

```
SHA-512(fairplay_decrypt(ekey) || ecdh_secret)[:16]
```

只有在 HAP 配对完成后才混入 pair-verify secret。这正是本项目 `MirrorSession.kt` 走的路：

```kotlin
HKDF(pair-verify secret, "DataStream-Salt$videoId", "DataStream-Output-Encryption-Key", ...)
```

**两条路是互斥的**：HAP 配对路径用 HKDF 派生视频密钥，legacy 路径用 FairPlay 派生。本项目实现了前者，所以它「不需要 FairPlay」——但这是**因为它只支持 HAP 路径**，不是因为 FairPlay 过时了。

---

## 2. FairPlay SAP v1（`/fp-setup`）

### 2.1 记录帧格式（FPLY）

所有消息共享同一个 12 字节头（[`doubletake/fpsap.go`](https://github.com/omarroth/doubletake/blob/master/internal/airplay/fpsap.go)，[握手文档](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake/blob/main/docs/03-the-handshake.md)）：

```
offset  bytes                 含义
0       46 50 4c 59           "FPLY" magic
4       03 01 TT 00           version 03 01, 消息类型 TT, 00
8       00 00 00 LL           big-endian body 长度
12      ...                   body
```

### 2.2 四消息布局

| 消息 | 总长 | body 长 | body 结构 |
|---|---|---|---|
| **m1**（发送端→接收端） | 16 | `0x04` | `02 00 CC bb`，`CC` = **能力位掩码**（默认 `0x03`） |
| **m2**（接收端→发送端） | 142 | `0x82` | `02` + **mode 字节** + 128 字节挑战 |
| **m3**（发送端→接收端） | 164 | `0x98` | mode 字节 + `8f 1a 9c` 标签 + 128 字节加密 local SAP + **20 字节响应** |
| **m4**（接收端→发送端） | 32 | `0x14` | 回显 m3[144:164] |

**易错点（`doubletake` 源码显式警告）**：m1 里的 `CC` 是**能力掩码，不是 mode**。发送端在 m1 宣告能力，接收端在 m2 的 byte 13 选择 mode。两者都是小整数、都能到 3，混淆它们是一个经典错误。

### 2.3 难点：128 字节 → 20 字节

这是整个 FairPlay 里唯一真正难的部分。`objevovat` 把它拆成两阶段：

```
128-byte payload → [Phase 1: white-box AES] → 128-byte GP buffer
                 → [bridge: fpsapcore]      → 20-byte digest
                 → [Phase 2: white-box MD5] → 20-byte response
```

而 `doubletake` 的实现显示，最终 20 字节的组成是：

```go
copy(out[:4], whiteboxOutput[:4])   // 前 4 字节来自第二个白盒网络
copy(out[4:], digest[:])            // 后 16 字节来自 MD5 家族摘要
```

**为什么难**：算法从未公开，只能从 Apple 二进制逆向。历史上的唯一做法是在 ARM64 模拟器里跑 Apple 的 `airtunesd` 二进制——`Slave in the Magic Mirror` 项目就是这么干的（[README](https://github.com/espes/Slave-in-the-Magic-Mirror)：*"The DRM is handled by calling into the original Apple TV server binary using a pure-python ARM interpreter"*）。

**现在不用了**：`objevovat` 项目已把算法完整还原为可移植代码（详见 §8.1）。

### 2.4 移植陷阱（`objevovat` 移植指南，高价值）

如果自己移植，这些坑**每一个都会产出看起来合理的错误哈希**：

1. **ring index 的 uint32 下溢**。`(i - 155) % 210` 中 `i` 是 32 位无符号，`i < 155` 时先回绕 2³² 再取模。正确答案是 `(i+101) % 210`，**不是** `(i+55) % 210`。`x[0]` 必须是 **101**，naive 写法给 55。
   - Kotlin 必须走 `((i - 155).toUInt() % 210u).toInt()`，直接用 `Int` 会得到 **-155**（负数，索引越界）。
2. **`rotateOrZero` 不是 rotate**：count 为 0 时返回 **0**，不是输入值。而 0 是常见活路径。
3. **`wideSeed` 的索引计算比 byte 宽**：先移位再取模，掩码到 8 位会改变结果。
4. **Go 的 `&^` 是 AND-NOT（清位）**，不是 XOR。译成 `a & ~b`。
5. **全部算术 mod 256，且语句顺序是算法的一部分**。很多行读取上一行刚写入的单元，重排即错。

**验证方法**：必须用独立的语料（40 条 sap_hash + 30 条 bridge 向量），**不能**用生成期望值的同一个表达式去测。

---

## 3. FairPlay SAP v2.5

- **版本字节是 `0x02`**（v3 是 `0x03`）。未带 `X-Apple-ET` 头时，HomePod 会给出：

```
46504c59 02 01 02 00 00000082 02 03 <128-byte challenge>
         ^^ version 0x02
```

- **`X-Apple-ET: 32` 是关键头**。`objevovat` 记录了一个很有教育意义的**被撤回的结论**：他们曾以为「真机讲 v2.5、我们实现 v3，这是根本性不兼容」。加上 `X-Apple-ET: 32` 后，**同一个设备**开始提供版本字节 `0x03` 并接受正确响应。

> The encryption type is something the sender asks for; the version difference was a consequence of not asking, not a property of the hardware.

**方法论教训（值得记住）**：一个拒绝所有变体（包括你预期会被拒绝的）的控制器，**并没有定位故障**。它与「我们在被评判且失败」和「我们根本没被评判」都一致。只有**成功的变体**才能区分二者。

- **注意区分**：`X-Apple-ET` 是现代接收端的要求，只由 `objevovat` 项目对 HomePod 确立；2011 年代的 AirPlay 1 源码（shairplay / RPiPlay / shairport-sync）都不发它。

---

## 4. playfair：被逆向出来的解密算法

- **它是什么**：一组从 Apple 二进制中恢复的 FairPlay 解密函数，以 `default_sap` 为根，通过 `generate_session_key` → `generate_key_schedule` → `cycle` 派生会话密钥。核心是（[`RPiPlay/lib/playfair/playfair.c`](https://github.com/FD-/RPiPlay/blob/master/lib/playfair/playfair.c)）：

```c
void playfair_decrypt(unsigned char* message3, unsigned* cipherText, unsigned char* keyOut)
{
    unsigned char* chunk1 = &cipherText[16];
    unsigned char* chunk2 = &cipherText[56];
    generate_session_key(default_sap, message3, sapKey);
    generate_key_schedule(sapKey, key_schedule);
    z_xor(chunk2, blockIn, 1);
    cycle(blockIn, key_schedule);
    for (i = 0; i < 16; i++) keyOut[i] = blockIn[i] ^ chunk1[i];
    x_xor(keyOut, keyOut, 1);
    z_xor(keyOut, keyOut, 1);
}
```

- **重要：playfair 主要是「接收端」工具。** 它做的是**解密** `chunk1`/`chunk2`（即 ekey 记录的第 16–31 和 56–71 字节），从接收端角度取回 AES key。**发送端需要的是反向操作**（封装），见 §5。

- **它覆盖什么**：AirPort Express / Apple TV 的 SAP 解密与旧式 FairPlay 流解密。**不覆盖** FairPlay Streaming DRM。

- **法律史**：由 EstebanKubata 创建，GPL 许可。RPiPlay 的免责声明直白：

> This project makes use of a third-party GPL library for handling FairPlay. **The legal status of that library is unclear.** Should you be a representative of Apple and have any objections against the legality of the library and its use in this project, please contact me and I'll take the appropriate steps.

- **血缘**：UxPlay、RPiPlay、juhovh/shairplay 属**同一支**（UxPlay README 自述 RPiPlay "in turn derives from ... shairplay, and playfair"），它们的 `fairplay_playfair.c` 近乎相同。**真正独立的支系约 4 支**，不是 6 支。

---

## 5. RAOP 音频路径（这是最容易做的一条）

### 5.1 `et` 各值的含义

来自 [nto 非官方规范](https://nto.github.io/AirPlay.html#servicediscovery-airtunesservice)：

| `et` | 含义 | 发送端要做什么 |
|---|---|---|
| `0` | 不加密 | 直接发 |
| `1` | **RSA**（AirPort Express） | 用接收端 RSA 公钥 OAEP 加密 AES key |
| `3` | **FairPlay** | 用 FairPlay 封装 72 字节 ekey |
| `4` | **MFiSAP**（第三方设备） | 需 `/auth-setup` + MFi 协处理器 ❌ |
| `5` | **FairPlay SAPv2.5** | 同 3，新版本 |

### 5.2 `et=1`（RSA）：**不需要 FairPlay**

规范原文（AirPort Express Authentication）：

1. 客户端在 `ANNOUNCE` 里用 `Apple-Challenge` 头发一个 128-bit 随机数；
2. 生成 128-bit AES key，用 **RSA 公钥 + OAEP** 加密，连同 IV 放进 SDP 的 `rsaaeskey` 和 `aesiv`；
3. 接收端用 RSA 私钥解出 AES key，并用私钥对 `Apple-Challenge` 签名，回 `Apple-Response`；
4. 客户端用公钥验签。

**AirPort Express 的私钥早在 2011 年就被提取并公开**（RPiPlay README 记载："In April 2011, a talented hacker extracted the AirPlay private key from an AirPort Express"）。`shairport-sync` 的 `common.c` 里就叫 `super_secret_key`，`airplay2-receiver` 里叫 `AIRPORT_PRIVATE_KEY`——**两者内容相同**。

### 5.3 `et=3/5`（FairPlay）：需要 ekey 封装

发送端生成 16 字节随机 AES key，用 FairPlay 把它包成 **72 字节 `FPLY` 记录**。`doubletake` 的 `wrapFairPlayKey` 是完整参考实现：

```
[0:16]  FPLY 头：46 50 4c 59 01 02 01 00 00 00 00 3c 00 00 00 00
[16:32] per-key 随机掩码（mask）
[32:36] big-endian 原始密钥长度（16）
[36:56] HMAC-SHA1(session MAC key, record[0:36] || rawKey)   ← 20 字节
[56:72] AES 加密的 (rawKey XOR mask)                          ← 16 字节
```

**我做的独立验证**：把 nto 规范中两个真实 blob 解码，布局**逐字段吻合**（详见 §5.4）。这说明这个格式不是某个项目的臆测，而是**线上真实格式**。

### 5.4 交叉验证记录（本报告最硬证据）

解码 `nto` 规范里两个 base64 blob：

**A. RAOP `a=fpaeskey`（ALAC 音频，`ANNOUNCE`）**
```
46 50 4c 59 | 01 02 01 00 | 00 00 00 3c | 00 00 00 00
f1 4e 9c d7 be cd 66 f9 fe 7e 0b e4 a6 64 13 60      ← mask (16B)
00 00 00 10                                            ← 长度 = 16 ✓
94 3c 7a f6 b7 93 77 01 c5 f4 b6 8d 9a 18 91 51 14 c0 6d   ← HMAC-SHA1 (20B)
c2 f8 6e b6 00 71 e0 24 67 8f 58 8a b5 e6 eb 63 78        ← AES 包裹 (16B)
总长 = 72 ✓
```

**B. 镜像 `POST /stream` 的 `param1`** —— 同样的 72 字节、同样的 `FPLY`/`0x3c`/`0x10` 字段布局。

结论：**RAOP 音频与 AirPlay 1 镜像共用同一种 72 字节 ekey 封装格式**。这是很强的交叉印证。（置信度：高）

### 5.5 `et=4`（MFiSAP）：**不要碰**

- `et=4` 且 feature bit 14 清零的设备（如 Denon AVR-X2500H）在 `/fp-setup` 上返回 **`404`**，因为它们**根本不实现 FairPlay SAP**，而是走 `/auth-setup`（MFi）。
- `404` 与 `403` 看起来都像失败，**含义相反**：`404` = 「此设备没有 FairPlay」，`403` = 「你还没配对」。
- MFi 认证依赖**硬件协处理器**，软件无法合法实现。本项目应明确**不支持**。

---

## 6. 屏幕镜像：两条完全不同的路

**这是「需不需要 FairPlay」的真正分界线。**

### 6.0 硬件实测结论（2026-09-24，本次研究新增）⭐

本报告的核心论断已在**真实 legacy 接收端**上验证。被测设备是 **AS-2112123AG**（MiraScreen/AirPlay dongle），它自报：

| 项 | 值 |
|---|---|
| `model` | `AppleTV3,2` |
| `sourceVersion` | `220.68`（`Server: AirTunes/220.68`） |
| `features` | `61647880183`，bit 14 (FPSAPv2.5) **SET**、bit 27 (LegacyPairing) **SET**、bit 46/48 **clear** |
| `statusFlags` | `68` (0x44)，bit 6 (OneTimePairingRequired) **SET** |
| 端口 | RTSP 在 **5000**（不是 7000） |

**关键实测结果**：

1. **`POST /fp-setup` 返回真实的 142 字节 m2**，帧格式完全正确：
   ```
   46 50 4c 59 | 03 01 02 00 | 00 00 00 82 | 02 03 <128B challenge>
   FPLY          v3, m2        body=130      marker, MODE=3
   ```
   → **旧版接收端确实要求 FairPlay，用户判断正确。**

2. **m2 的挑战是静态的，且与公开多年的 RPiPlay/shairport-sync 表逐字节相同。**
   连续三次会话返回**完全相同**的 142 字节 body（md5 `d0e6e9db402b52d2f36cb95192a1f316`），只有 `Date` 头不同。
   > ⚠️ **这与 `objevovat` 对 HomePod 的记录相反**（该文档称真机每次会话发新挑战）。说明**廉价 dongle 直接重放了捕获的 m2**，而 Apple 真机不是。这是本报告对既有文献的一个修正。

3. **该 dongle 会真正校验 m3。** 发送一个帧格式正确但响应全零的 m3，得到 **12 字节拒绝帧**：
   ```
   1e 1e 1e 1e | 03 01 04 9c | 00 00 00 00
   ```
   而发送一个**完全没有 m3** 的连接只得到 m1 的响应（311 字节，无 m3 回复）。
   → **帧格式必须正确，否则连拒绝都拿不到。**

4. **对既有文献的修正**：`centuryplay` 的文档记录该错误帧为 `1e1e1e1e **02** 01 04 9c`（version 字节 = 2）。**实测是 `03`**，即 version 字节跟随协商到的版本（此处 m2 是 v3）。文档的 `0x02` 应视为未在 v3 会话中验证。

5. **`/fp-setup` 的拒绝藏在 body 里，HTTP 状态仍是 `200`。** 只检查状态行的发送端不会发现自己被拒绝了。

**含义**：legacy 路径的**发送端框架**可以完全离线开发和回归测试（本仓库已实现，见 §8.6）。但**计算正确 20 字节响应**仍需 §8.1 的白盒核心。

### 6.1 路径 A：AirPlay 2 / HAP 配对 —— **不需要 FairPlay** ✅

- 控制通道在 pair-verify 后切换为 HAP 帧（ChaCha20-Poly1305）。
- 视频密钥：`HKDF(pair-verify secret, "DataStream-Salt<id>", "DataStream-Output-Encryption-Key")`。
- 音频密钥：`shk`（随机，在加密通道内传送）。
- **本项目已在 tvOS 26.6 上验证成功**（AppleTV6,2，密码模式）。

### 6.2 路径 B：AirPlay 1 legacy —— **需要 FairPlay** ⚠️

这是用户指出的那一条，**判断正确**。

**证据链**：

1. **`doubletake`（发送端）实现了 FairPlay SAP，并验证过 AppleTV3,2**（2013 第三代，legacy 设备）。其 README 的 Tested Devices 列表包含：
   - `AppleTV3,2 (2013 3rd generation)`
   - `AppleTV11,1 (4K, 2021 2nd gen)`
   - `AppleTV14,1 (4K, 2022 3rd gen) + Homepod (1st gen)`

2. **其测试接收端的 `appletv3` profile 明确写**：
   > Its AppleTV3 and UxPlay profiles also authenticate the FairPlay key, **decrypt legacy AES-CTR video**, and validate the resulting AVCC/NAL structure.

   即 legacy 视频是 **AES-CTR 加密**，密钥由 FairPlay 提供。

3. **`doubletake` 区分了两条路的密钥来源**：
   > Encrypted HAP sessions keep FairPlay material in stream descriptors; **plaintext/raw sessions use available legacy FairPlay roots on control and media SETUP.**

4. **RPiPlay（接收端）确认 legacy 镜像路径使用 FairPlay**：其 `lib/playfair/` 用于镜像会话；README 的协议史部分说明，连到 AppleTV 3 时「the communication is still visible in plain」，即**控制通道明文，但流仍受 FairPlay 保护**——这与路径 A 的「控制通道加密 + HKDF 派生」形成对照。

5. **nto 规范**的 AirPlay 1 镜像端点（端口 7100，`/stream.xml` + `POST /stream`）中，`param1` 定义为：
   > `param1` | data | (72 bytes) | **AES key, encrypted with FairPlay**

   而 `param2` 是 16 字节 AES IV。这与 §5.4 解码结果一致。

**结论**：legacy 镜像需要 **FairPlay SAP + `fp-setup` + 72 字节 ekey + AES-CTR 视频帧**。本项目目前**完全没有**这条路径。

### 6.3 反驳意见与反例（诚实记录）

- **nto 规范开头声明**：*"It does not explain the FairPlay authentication (SAPv2.5) used by iOS devices and OS X Mountain Lion to protect audio and screen content."* —— 所以该规范本身**不提供** FairPlay 细节，只提供了 `param1` 字段的语义。它的字节布局证据来自我自己的解码，不是规范解释。
- **`objevovat` 的硬件验证只覆盖 HomePod，不覆盖 Apple TV**。两台 Apple TV 都在评估响应**之前**就拒绝了（`AppleTV11,1` 在 pair-setup M1 得 `470`；`AppleTV6,2` 完成配对后对**所有** `/fp-setup` m3 一律 `403`，无论对错）。**Apple TV 上 FairPlay 响应是否被接受，目前未被任何公开项目证实。**（置信度：这是重要的未解问题）
- **`doubletake` 的 FairPlay 实现可能来自 ARM64 模拟器或白盒表**，其 README 免责声明：*"The majority of code for this project was written by LLMs."* —— 引用其行为描述时需保留这一保留意见。
- **`shairport-sync` 作为测试目标是无效的**：它 `/fp-setup` 一律回 `200`，**对故意损坏的响应也回 `200`**。它没有 FairPlay 可验证，所以它的「接受」不携带任何信息。

---

## 7. 视频 URL 移交（`/play`）

**结论：不需要 FairPlay。** 本项目现有实现方向正确。

- `POST /play` 只把 URL 交给接收端，**手机不传像素**（nto 规范 §4.1；RPiPlay README：*"AirPlay on an AppleTV effectively runs a web server on the device and sends the URL to the AppleTV, thus avoiding the re-encoding of the video"*）。
- 需要的是 HTTP Digest（`flags` bit 7）——本项目已实现。
- **注意**：nto 规范提到 Apple TV 有一个 **`VideoFairPlay`** feature bit（bit 2），含义是「支持受 FairPlay DRM 保护的视频」。这是**内容层 DRM**，与 `POST /play` 移交一个普通 URL 无关。普通 MP4/HLS URL 不走 FairPlay。

**本项目 README 记录的未解问题**（`/play` 返回 200 但 TV 只转圈、`/playback-info` 返回 500）**与 FairPlay 无关**。README 自己给出的下一步（在 pair-verify 加密通道内发 `/play`）方向合理。已排除的项：内容格式、可达性、Digest。

---

## 8. 可行方法清单（按可行性排序）

### 8.1 ⭐ 直接采用现成的 FairPlay SAP 实现（推荐）

**[objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake)**

| 项目 | 说明 |
|---|---|
| 做什么 | 从零重实现 AirPlay 2 **发送端**所需的 FairPlay SAP 握手 |
| 替代了什么 | ~1.07 MB Apple 二进制 + ARM64 模拟器 → **~500 KB 可移植代码** |
| 语言 | Go（完整）、**C / Rust / C# / Kotlin / Python**（Phase-1 bridge 核心） |
| 依赖 | **零** |
| 性能 | 完整交换 **5.19 µs**，零分配（对比 doubletake 模拟器路径 24.96 µs） |
| 验证 | 142 golden vectors、70/70 每语言一致性、**3 台真机 HomePod**（接受正确、拒绝损坏） |
| 许可 | **LGPL-3.0-or-later** |

**为什么对本项目特别合适**：

1. **有 Kotlin 移植版**：`ports/kotlin/FairPlaySapCore.kt`，入口 `bridgeX9HeadForSap(localSap, gp) -> ByteArray(20)`，**只依赖 Kotlin/JVM 标准库**。本项目 `:protocol` 是纯 Kotlin/JVM、零 `android.*`——**架构完全吻合**。
2. **许可证兼容**：LGPL-3.0-or-later 可合入 GPLv3 项目。
3. **该 Kotlin 文件自带 4 条移植陷阱注释**，与 §2.4 一致，说明作者踩过并记录了坑。

**局限（必须知道）**：
- **只支持 mode 3**（唯一被观察到的 mode）。
- **硬件验证只在 HomePod 上**，Apple TV 未被证实。
- **必须用 per-session 路径**，冻结重放会被严格接收端以 `466 Key Management Error` 拒绝。
- `ports/` 下的核心是 `fpsapcore` 的移植，**带 copyleft**（LGPL-3.0-or-later），合入即受其约束。

### 8.2 参考 `doubletake` 的 Go 实现

[omarroth/doubletake](https://github.com/omarroth/doubletake) —— 完整的镜像发送端，包含：
- `internal/airplay/fairplay.go`：完整 SAP 握手流程
- `internal/airplay/fpsap.go`：m1/m2/m3/m4 记录、白盒网络
- `internal/airplay/fairplay_crypto.go`：**`wrapFairPlayKey`（72 字节 ekey 封装）** ← §5.3 的参考
- LGPL-3.0-or-later

**注意**：README 声明大部分代码由 LLM 编写，作者不建议在生产/安全敏感环境使用。

### 8.3 参考 RPiPlay / shairplay 的 playfair

仅当需要**接收端**解密时。发送端应参考 §8.2 的 `wrapFairPlayKey`。**GPL，法律状态不明确**。

### 8.4 ❌ 不推荐：ARM64 模拟器

`Slave in the Magic Mirror` 的做法（在 Python ARM 解释器里跑 `airtunesd`）。已被 §8.1 取代，且需要分发 Apple 二进制——**法律与工程双输**。

### 8.5 ❌ 不可行：MFiSAP（`et=4`）

需要 MFi 硬件协处理器。软件无法合法实现。

### 8.6 ✅ 已实现：本地 legacy 接收端测试夹具（本仓库）

**`protocol/src/main/kotlin/.../devtools/MockLegacyReceiver.kt`** —— 一个本地 mock 的 AirPlay 1 接收端，用**真实抓包字节**复现那台 dongle 的可观察行为：

```
MockLegacyReceiver [port] [--accept-fresh]
```

- `GET /info` → 返回抓到的 2235 字节 XML plist
- `POST /fp-setup` + m1 → 返回抓到的 142 字节 m2
- `OPTIONS` → 返回真实的 `Public` 方法表
- 其他路径 → 真实的 `404`

**两种会话策略**：

| 策略 | 行为 | 能证明什么 |
|---|---|---|
| `REJECT_ALL`（默认） | 拒绝所有 m3，与真机拒绝错误密钥一致 | 帧格式、失败路径 |
| `ACCEPT_FRESH_SESSION` | 接受**未见过**的 local SAP；拒绝逐字节重放 | **可捕获 frozen replay 这个真实 bug** |

**为什么这个设计重要**：`ACCEPT_FRESH_SESSION` **不需要白盒密钥表**（那是 ~500 KB 的逆向数据），却能捕获真实发送端最常见的失败——把捕获的 144 字节前缀写死，导致每次会话发出**完全相同**的 m3，严格接收端会以 `RTSP/1.0 466 Key Management Error` 拒绝。

> 一个「全部接受」或「全部拒绝」的 mock 什么都证明不了。`MockLegacyReceiverSessionTest` 用**同一 mock、同一请求形状**，仅凭 local SAP 是否新鲜得出相反判定——这才是有效对照。

**同批产出**：
- `protocol/.../fairplay/FairPlayRecords.kt` —— FPLY 帧编解码（m1/m2/m3/m4 + 错误帧识别），带严格的帧校验
- `protocol/src/test/resources/fairplay/` —— 5 个真实抓包夹具
- `FairPlayRecordsTest` + `MockLegacyReceiverTest` + `MockLegacyReceiverSessionTest`

**仍未覆盖**：mock **不算**正确的 20 字节响应，所以无法跑通一次**成功**的镜像会话。要覆盖那段，需要接入 §8.1 的白盒核心，或直接对真机测试。

### 8.7 关于「用现成开源接收端做靶机」

评估过 [UxPlay](https://github.com/FDH2/UxPlay)、[shairport-sync](https://github.com/mikebrady/shairport-sync)、[RPiPlay](https://github.com/FD-/RPiPlay) 作为本机靶机，**结论是不划算**：

| 项目 | 障碍 |
|---|---|
| UxPlay | 需 cmake + GStreamer + libplist + OpenSSL；Windows 需 MSYS2 全量依赖；本机无 cmake/pkg-config/make |
| shairport-sync | 需 autotools + ALSA/PipeWire 音频栈；**且它 `/fp-setup` 一律回 200，连损坏的响应也回 200**，作为 FairPlay 靶机**无信息量** |
| RPiPlay | 依赖树莓派 OpenMAX/ilclient 或 GStreamer；本机 Docker 未运行、WSL 被拒 |

**更重要的一点**：这些是**接收端**，而本项目的缺口在**发送端**。接收端只能验证「我们发的东西它收不收」，不能验证「我们算的响应对不对」——后者需要一台会**真正校验**的接收端（真机，或 §8.1 的白盒核心 + mock）。

**本机可用的工具链**：`g++`/`gcc`（MSYS2 mingw64）、Python 3.13、Java 21、dotnet、Docker CLI（daemon 未运行）、winget。**没有** cmake/make/pkg-config/go/rustc。

---

## 9. 对本项目 AirPlayDroid 的具体建议

### 9.1 修正 README 的表述（高优先级，零成本）

当前（README 第 15、18–26 行）：
> ✅ **Screen mirroring** | Picture **and** sound, to an Apple TV on tvOS 26.6. **No FairPlay.** Verified on one unit.

**问题**：虽然原文有 "On tvOS 26.6" 限定，但加粗的 "No FairPlay" 会被读成普遍结论。

**建议改为**：
> ✅ **Screen mirroring**（**AirPlay 2 / HomeKit 配对路径**）| Picture and sound, to an Apple TV on tvOS 26.6. **No FairPlay** — this path derives the video key via HKDF from the pair-verify secret. Verified on one unit.
> ⬜ **Legacy (AirPlay 1) mirroring** | Not supported. Requires the FairPlay SAP handshake (`/fp-setup`), a 72-byte `FPLY` ekey, and AES-CTR video framing. See `docs/fairplay-research.md`.

同时 §"Current limitations" 应加一条：**只支持 HAP 配对路径；AppleTV 2/3 等 legacy 接收端需要 FairPlay，未实现。**

### 9.2 Roadmap 重新评估

| Roadmap 项 | 真实缺口 | 需要 FairPlay？ |
|---|---|---|
| ⬜ 镜像到 PIN（`flags` bit 9）/ transient 配对 | **仅配对方式**（LegacyPairing/SRP 已有基础） | ❌ 若接收端是 AirPlay 2 |
| ⬜ `/play` 在加密通道内 | 通道选择 | ❌ |
| ⬜ RAOP 音频-only | ALAC/`et` 协商 + ekey | ⚠️ 取决于 `et`：`1` 否，`3/5` 是 |
| **❌ 缺失项：legacy（AirPlay 1）镜像** | **整条路径**：FairPlay SAP + `fp-setup` + ekey + AES-CTR + `/stream` | ✅ **是** |

**建议**：新增一个 roadmap 条目明确列出 legacy 镜像，并标注它需要 FairPlay。当前 roadmap **完全没有提到**这条路径，容易让人误以为「镜像已经全支持了」。

### 9.3 若要做 legacy 镜像，落地路径

1. 在 `:protocol` 新增 `fairplay/` 包，移植 §8.1 的 Kotlin `FairPlaySapCore.kt` + `FairPlayBridge.kt`。
2. **先离线验证**：跑通 142 golden vectors / 40+30 一致性语料，再碰真机。
3. 实现 `POST /fp-setup` 两阶段（m1→m2，m3→m4），带 `X-Apple-ET: 32`。
4. 用 `wrapFairPlayKey` 生成 72 字节 ekey。
5. 实现 legacy 镜像端点（7100 端口 `/stream.xml` + `POST /stream`，`param1`/`param2`）。
6. AES-CTR 加密视频帧。
7. **警告**：仓库已有 `LegacyPairing.kt` / `LegacyAirPlaySrp.kt`，但**它们解决的是配对，不是 FairPlay**。不要误以为 legacy 支持已经做了一半——真正的重头在 FairPlay。

### 9.4 明确不要做的事

- ❌ **不要**尝试 MFiSAP（`et=4`）。
- ❌ **不要**为 `/play` 引入 FairPlay——它不需要，引入只会增加风险和复杂度。
- ❌ **不要**用 `shairport-sync` 作为 FairPlay 的验证目标（它一律回 200）。
- ❌ **不要**用「冻结重放」的 m3 对真机（会得 `466`）。
- ❌ **不要**在硬件上反复试错配对——README 已警告会触发 HomeKit 反暴力破解 backoff（TLV `Error=3`）。**先离线验证**。

### 9.5 关于 `/play` 的 500 错误

**与 FairPlay 无关**，不要被 FairPlay 带偏。README 的假设（在加密通道内发 `/play`）是合理方向。建议先加日志区分「无 session」与「播放失败」两种情况。

---

## 10. 法律与合规风险

**本节不是法律意见。**

| 事项 | 风险 | 说明 |
|---|---|---|
| **LGPL-3.0 的 FairPlay SAP 实现**（§8.1） | **低-中** | 许可证与 GPLv3 兼容，可合法合入。但底层白盒表是 Apple 派生**数据**，项目自述：「The white-box tables are Apple-derived *data* — the same recovered tables already public in the upstream project, no new exposure.」 |
| **playfair** | **中-高** | GPL，但 RPiPlay 自述「The legal status of that library is unclear」。含 Apple 派生密钥材料。 |
| **分发 Apple 二进制**（模拟器路线） | **高** | 直接侵犯版权，避免。 |
| **AirPort Express RSA 私钥** | **中** | 2011 年即公开，广泛使用于 shairport-sync / airplay2-receiver。历史包袱，但已事实公开化。 |
| **DMCA §1201 反规避** | **中** | 美国法下逆向规避技术措施存在争议。playfair 历史上与相关争议相关联。 |
| **MFi 商标/认证** | **高** | 声称 MFi 兼容而无授权，风险明确。 |

**降低风险的做法**：
1. 优先选 **LGPL-3.0** 的 §8.1，而非 GPL 的 playfair。
2. 保留清晰的 `NOTICE` / SPDX 头与来源归属。
3. 不声称 MFi 兼容。
4. 不分发 Apple 二进制。
5. 考虑到本项目已是 GPLv3 且公开在 GitHub，**FairPlay 相关的法律姿态与 RPiPlay/UxPlay 等既有项目处于同一水平**——不是新风险类别，但也不是零风险。

---

## 11. 未解决问题与下一步验证方法

按「能否用现有验证」排序。

### 11.1 高优先级

| 问题 | 现状 | 验证方法 |
|---|---|---|
| **Apple TV 到底接不接受我们算出的 FairPlay 响应？** | **未知**。两台 Apple TV 都在评估前拒绝。这是最大的空白。 | 在完整 HomeKit 配对后重试 `/fp-setup`。`objevovat` 明确说这「untested」。需要一台能完成 PIN 配对的 Apple TV。 |
| **本项目能否在 AppleTV3 上跑通 legacy 镜像？** | 未实现 | 需要一台 AppleTV3 + 实现 §9.3 全部 7 步。`doubletake` 已验证可行，可作为对照。 |
| **mode 3 是不是永远成立？** | 所有观察都是 mode 3 | 构造 m1 能力掩码的变体，观察接收端是否选其他 mode。`objevovat` 说「not established」。 |

### 11.2 中优先级

| 问题 | 验证方法 |
|---|---|
| `466 Key Management Error` 是否真是冻结重放的症状？ | 单一来源。用真机分别发冻结 m3 与 session m3 对比。 |
| HomePod 是否每次会话都发**新的** 128 字节挑战？ | `objevovat` 是**推断**，未直接证实。连续两次会话抓包对比。 |
| 142 golden vectors 是否独立可复现？ | `objevovat` 承认其「两个独立实现」其实同源（airfry 是 doubletake 的移植）。需第三方独立复现。 |

### 11.3 低成本实验（建议立刻做）

1. **离线跑通 FairPlay SAP 语料**：用 `fpsap verify` 或 Kotlin 移植版跑 142 vectors。**零硬件、零风险**，能立刻确认实现可用性。
2. **在本项目加一个 FairPlay 能力探测**：对已发现的设备读 `et` 与 feature bit 14，在 UI 上标注「此设备需要 FairPlay / 此设备不需要」。这能立刻把 §6 的分界线变成用户可见的信息。
3. **用 `MirrorProbe` 对 legacy 接收端做 `/fp-setup` 探测**：只发 m1，观察是 `404`（无 FairPlay）、`403`（需配对）还是 `200 + m2`（可继续）。这一条探测就能确定某台设备属于哪条路径。

---

## 12. 参考来源

### 发送端实现（最重要）

- [omarroth/doubletake](https://github.com/omarroth/doubletake) — AirPlay 镜像**发送端**（Go, LGPL-3.0-or-later）
  - [`internal/airplay/fairplay.go`](https://github.com/omarroth/doubletake/blob/master/internal/airplay/fairplay.go) — 完整 SAP 握手流程
  - [`internal/airplay/fpsap.go`](https://github.com/omarroth/doubletake/blob/master/internal/airplay/fpsap.go) — m1–m4 记录、白盒网络、descriptor
  - [`internal/airplay/fairplay_crypto.go`](https://github.com/omarroth/doubletake/blob/master/internal/airplay/fairplay_crypto.go) — **`wrapFairPlayKey`（72 字节 ekey）**
  - [README](https://github.com/omarroth/doubletake/blob/master/README.md) — Tested Devices（含 **AppleTV3,2**）、receiver profiles
- [objevovat/fairplay-sap-core-...](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake) — 6 语言 FairPlay SAP 核心（**LGPL-3.0-or-later**）
  - [`ports/kotlin/FairPlaySapCore.kt`](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake/blob/main/ports/kotlin/FairPlaySapCore.kt) — **Kotlin 移植，本项目可直接用**
  - [握手文档](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake/blob/main/docs/03-the-handshake.md) — FPLY 字节布局
  - [移植指南](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake/blob/main/docs/06-porting-guide.md) — 5 大陷阱
  - [Pairing](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake/blob/main/docs/12-pairing.md) — 硬件矩阵、`X-Apple-ET: 32`
  - [Limitations](https://github.com/objevovat/fairplay-sap-core-airplay2-sender-authentication-handshake/blob/main/docs/09-limitations.md) — mode 3 限制、验证范围

### 接收端实现（了解对端解析什么）

- [FD-/RPiPlay](https://github.com/FD-/RPiPlay) — [`lib/playfair/playfair.c`](https://github.com/FD-/RPiPlay/blob/master/lib/playfair/playfair.c)、[README 协议史](https://github.com/FD-/RPiPlay/blob/master/README.md)（极佳的历史综述）
- [mikebrady/shairport-sync](https://github.com/mikebrady/shairport-sync) — [`rtsp.c`](https://github.com/mikebrady/shairport-sync/blob/master/rtsp.c) 的 `handle_fp_setup`、[`common.c`](https://github.com/mikebrady/shairport-sync/blob/master/common.c) 的 `super_secret_key`
- [openairplay/airplay2-receiver](https://github.com/openairplay/airplay2-receiver) — [`ap2/playfair.py`](https://github.com/openairplay/airplay2-receiver/blob/master/ap2/playfair.py)（含 `AIRPORT_PRIVATE_KEY`、reply 表）
- [FDH2/UxPlay](https://github.com/FDH2/UxPlay)

### 协议规范

- [nto 非官方 AirPlay 规范](https://nto.github.io/AirPlay.html) — `et` 表、`rsaaeskey`、`/stream` 的 `param1`、`VideoFairPlay` bit
- [philippe44 RAOP-Player auth_protocol](https://htmlpreview.github.io/?https://github.com/philippe44/RAOP-Player/blob/master/doc/auth_protocol.html) — 配对与认证协议规范

### 历史 / 法律

- [espes/Slave-in-the-Magic-Mirror](https://github.com/espes/Slave-in-the-Magic-Mirror) — ARM64 模拟器路线的历史样本
- [EstebanKubata/playfair](https://github.com/EstebanKubata/playfair) — playfair 原始项目（GPL）

---

## 附录 A：本报告的证据分级

| 结论 | 置信度 | 依据 |
|---|---|---|
| 72 字节 ekey 布局 | **高** | 我独立解码两个真实 blob，与 doubletake 源码逐字段吻合 |
| AirPlay 1 legacy 镜像需要 FairPlay | **高** | **真机实测**（AS-2112123AG 返回 142 字节 m2）+ doubletake 验证过 AppleTV3,2 |
| legacy 接收端会校验 m3 | **高** | **真机实测**：错误 m3 得到 12 字节拒绝帧，无 m3 则无回复 |
| `/fp-setup` 的拒绝藏在 body（HTTP 200） | **高** | **真机实测** |
| 该 dongle 的 m2 挑战是静态的 | **高** | **真机实测**：3 次会话 body 完全相同，且与公开表逐字节一致 |
| AirPlay 2 / HAP 路径不需要 FairPlay | **高** | 本项目已在 tvOS 26.6 验证；doubletake 源码显示 HKDF 派生 |
| `et=1` 不需要 FairPlay | **高** | nto 规范 + shairport-sync/airplay2-receiver 源码 |
| Kotlin FairPlay 实现可直接用 | **高** | 源码已读，纯 Kotlin/JVM、零依赖、许可兼容 |
| 错误帧 version 字节跟随协商版本（实测 `03`） | **中-高** | 单次真机实测；与 centuryplay 文档的 `02` 冲突，未见 v2 会话实测 |
| mode 3 是唯一 mode | **中-高** | objevovat 全部观察 + 本次真机也是 mode 3；但作者自述「not established」 |
| 真机每次发新挑战 | **低（被本次实测修正）** | objevovat 对 HomePod 如此；**该 dongle 明确不是** |
| 冻结重放得 `466` | **中** | 单一来源（objevovat），未与作者自己的硬件矩阵对照 |
| Apple TV 接受 FairPlay 响应 | **未验证** | 两台 Apple TV 都在评估前拒绝；无公开项目证实 |
| 计算正确的 20 字节响应 | **未实现** | 需要白盒核心；mock 只覆盖帧与会话语义 |

## 附录 B：AirPlay 1 vs AirPlay 2 镜像对照

| | AirPlay 1（legacy） | AirPlay 2（HAP） |
|---|---|---|
| 认证 | FairPlay SAP（`/fp-setup`） | HomeKit pair-setup / pair-verify |
| 控制通道 | **明文** | HAP 加密（ChaCha20-Poly1305） |
| 视频密钥 | FairPlay ekey（72B `FPLY`） | HKDF(pair-verify secret, `DataStream-Salt<id>`) |
| 视频加密 | **AES-CTR** | HAP 帧加密 |
| 镜像端点 | 端口 7100 `/stream.xml` + `POST /stream` | RTSP `SETUP` stream type 110 |
| 时钟 | NTP | PTP（或 NTP） |
| 本项目支持 | ❌ | ✅（已验证） |
| 需要 FairPlay | **✅ 是** | ❌ 否 |
