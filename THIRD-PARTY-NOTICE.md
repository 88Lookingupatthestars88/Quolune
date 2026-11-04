# 第三方代码

## voice/Stamp.kt

`voice/Stamp.kt` 是 Edge 那一路握手令牌的生成器，算法移植自 rany2/edge-tts 的 `src/edge_tts/drm.py`。

- 上游：https://github.com/rany2/edge-tts
- 版本：revision `b2ffbdd9f43f28f03cc939b5de0f3964aa62d60e`（2026-10-13）
- 许可：LGPL-3.0-only
- 版权：Copyright (c) rany and edge-tts contributors
- 本项目的改动：从 Python 移植成 Kotlin，整数运算显式按 32 位回绕，输出拆成 CS64 与 SHA-256 两半。

移植后的文件仍按 LGPL-3.0-only 传递，没有换过许可。

LGPL-3.0 与 GPL-3.0 全文在 app/src/main/assets/licenses/ 下，随 apk 一起分发；仓库的 licenses/ 目录里另有一份。

## voice/Edge.kt

`voice/Edge.kt` 里 WebSocket 握手的那几条消息（`speech.config`、`Path:ssml`、`X-RequestId`、`X-Timestamp`）
是这条通道的协议格式，属于接口事实，不是从上游抄的代码 —— 换任何语言写都必须长这样，否则服务端不认。
它依赖 `Stamp.kt` 生成的令牌才能连上。

## 这套算法是什么

`Sec-MS-GEC` 是微软给 Edge 浏览器「大声朗读」用的握手令牌，不是公开 API 的鉴权方式。算法由社区抓包反推，不是微软公布的，也没有任何官方文档。其中的固定串是 Edge 客户端里写死的常量，不是谁的个人密钥，公开它不涉及泄露。

换句话说：代码是开源的（rany2/edge-tts，LGPL-3.0），接口不是。

## 微软那一侧

- 走的是 `speech.platform.bing.com`，浏览器朗读用的那条通道。没有 API key，没有配额，没有服务承诺。
- 微软随时可以改协议、加校验、按 IP 限流，或者整条关掉。改了之后要跟上游一起改，改不动就只能关掉这一路。
- 微软的服务条款没允许第三方程序直接调这条通道。个人开源项目里用得很普遍，微软至今没有对任何 edge-tts 移植发过函，但这是事实，不是许可。
- 别把它当商业服务的地基。要稳定、要能签合同，去开 Azure 的语音服务，那边有 key、有配额、有 SLA。

## 关掉它

设置 → 声源 → Edge 那一路的开关关掉就停了，其余嗓子照常。默认是开的，因为不用填钥匙，装上就能念。
