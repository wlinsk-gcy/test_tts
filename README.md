# TTS Session 说明

- 教师对话会话和 HTTP 文章朗读会话，分别维护各自独立的可复用上游 TTS Session。
- `POST /api/sessions` 和 `POST /api/sessions/{sessionId}/turns` 会在同一个对话 `sessionId` 内跨 Turn 复用一条 TTS Session。
- `POST /api/tts/sessions/stream` 和 `POST /api/tts/sessions/close` 会在同一个朗读 `sessionId` 内跨句子复用另一条 TTS Session。
- `POST /api/cosyvoice/tts/stream` 是 CosyVoice v3 Flash 的单次流式合成接口；它对前端返回的 SSE 事件结构与 `POST /api/tts/sessions/stream` 对齐，`sessionId` 只用于前端事件关联和日志追踪，不参与 CosyVoice WebSocket 连接复用。
- DashScope realtime TTS 统一使用 `rd.ai.tts.mode=server_commit`。
- 每个教师对话 Turn 或每次 HTTP 句子请求结束时，后端仍会发送一次 `input_text_buffer.commit`，用于 flush 当前缓冲文本，但不会关闭上游 TTS Session。
- 文章朗读链路会输出 `stream.start`、`text.chunked`、`tts.session.ready`、`tts.first.audio`、`stream.completed`、`stream.timeout`、`stream.error` 等结构化日志。
- `stream.error` 仍然保留结构化字段；同时错误路径会额外输出类内 `error` 日志并附带完整异常堆栈，便于直接查看抛错代码位置。
- 如需排查上游 realtime 事件，可开启 `rd.ai.tts.debug-log-upstream-events=true`，此时会记录 `session.updated`、`response.created`、`response.audio.done`、`response.done`，以及出站的 `session.update`、`input_text_buffer.append`、`input_text_buffer.commit`、`session.finish` 等 `tts.upstream` 日志。

# rd_machine 接口文档

本文档基于当前后端实现和现有调试前端代码整理，目标是让前后端联调时直接按代码中的真实协议对接。

## 协议概览

- HTTP 基础地址：`http://<host>:8080`
- WebSocket 地址：`ws://<host>:8080/ws/sessions/{sessionId}`，HTTPS 场景对应 `wss://`
- HTTP 接口统一返回 `Result<T>` 包装
- WebSocket 推送当前不使用 `Result<T>`，直接推送 `AssistantEvent`
- 创建会话后，后端会立刻启动教师端流式生成；客户端应尽快建立 WS 连接，否则可能错过首批流式事件

当前实现里，后端在启动助手回合时只会等待最多约 `800ms` 的首个 WS 连接，相关代码见：

- `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`
- `src/main/java/com/wlinsk/rd_machine/transport/ws/SessionConnectionRegistry.java`

## HTTP 协议

### 通用返回包

所有 HTTP 接口的业务数据都放在 `data` 字段中，最外层结构如下：

```json
{
  "rspCd": "00000",
  "rspInf": "success",
  "data": {},
  "responseTm": "2026-03-30T11:11:11.111",
  "rspType": 0,
  "v": "1"
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rspCd` | `string` | 返回码，`00000` 表示成功 |
| `rspInf` | `string` | 返回信息 |
| `data` | `T` | 具体业务数据 |
| `responseTm` | `string` | 服务端响应时间，`LocalDateTime.toString()` 格式 |
| `rspType` | `number` | 当前固定为 `0` |
| `v` | `string` | 当前固定为 `"1"` |

当前代码里可见的通用错误码：

| `rspCd` | 含义 |
| --- | --- |
| `00000` | 成功 |
| `9000` | 参数校验错误 |
| `9999` | 系统错误 |

前端如果要处理这个包裹层，直接看 `ui/src/api.ts` 中的 `request()` 和 `unwrapApiResult()`。

### 1. 创建会话

- 方法：`POST`
- 路径：`/api/sessions`

说明：

- 当前文章内容由前端维护，调试前端直接使用 `ui/src/articles.ts` 里写死的 3 篇文章
- 后端已不再提供文章列表接口，创建会话时需要直接传入文章元数据和全文

请求体：

```json
{
  "title": "草原",
  "author": "老舍",
  "language": "zh-HK",
  "content": "天，終於亮起來了。"
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `title` | `string` | 是 | 文章标题 |
| `author` | `string` | 是 | 作者 |
| `language` | `string` | 是 | 文章语言标识 |
| `content` | `string` | 是 | 文章全文内容 |

业务出参：`CreateSessionResponse`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `string` | 会话 ID，后续所有会话接口和 WS 路径都要用 |
| `status` | `string` | 当前状态。创建后会立即进入 `GENERATING` |
| `currentRoundNo` | `number` | 当前轮次，初始为 `1` |
| `currentTurnNo` | `number` | 当前教师回合号，初始为 `1` |
| `wsUrl` | `string` | 当前实现返回相对路径，例如 `/ws/sessions/{sessionId}` |

业务出参示例：

```json
{
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "status": "GENERATING",
  "currentRoundNo": 1,
  "currentTurnNo": 1,
  "wsUrl": "/ws/sessions/0c5b6de6-7f77-4d76-9303-31c0324d5a65"
}
```

联调注意：

- 该接口返回后，后端已经开始生成教师回复，因此前端要立刻建立 WS 连接，不要等用户再点别的按钮
- 当前调试前端直接从 `ui/src/articles.ts` 选中本地文章，并把 `title`、`author`、`language`、`content` 直接传给后端
- 当前调试前端没有直接使用返回的 `wsUrl`，而是自己用 `sessionId + apiBaseUrl` 拼出了 WS 地址
- 如果后端以后修改了 WS 路径、域名或返回完整 URL，前端工程师需要同步检查：
  - `ui/src/App.tsx` 里的 `handleSelectArticle()`、`connectSocket()`
  - `ui/src/ws.ts` 里的 `openSessionSocket()`

### 2. 提交学生回答

- 方法：`POST`
- 路径：`/api/sessions/{sessionId}/turns`

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `string` | 创建会话时返回的会话 ID |

请求体：

```json
{
  "clientSeq": 1743047322485,
  "text": "学生端 ASR 最终文本"
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `clientSeq` | `number` | 否 | 客户端幂等序号。同一会话内重复提交相同值会返回 `accepted=false` |
| `text` | `string` | 是 | 学生作答文本，建议传 ASR 最终结果 |

业务出参：`SubmitTurnResponse`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `accepted` | `boolean` | 是否真正接受这次提交 |
| `sessionId` | `string` | 会话 ID |
| `status` | `string` | 提交后的会话状态。被接受后会进入 `GENERATING` |
| `currentRoundNo` | `number` | 当前轮次 |
| `currentTurnNo` | `number` | 当前教师回合号 |

业务出参示例：

```json
{
  "accepted": true,
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "status": "GENERATING",
  "currentRoundNo": 2,
  "currentTurnNo": 2
}
```

行为说明：

- 只有会话处于 `WAITING_STUDENT` 时，后端才接受该接口
- `accepted=true` 时，后端会立刻启动下一轮教师端流式生成
- `accepted=false` 只表示命中了重复 `clientSeq` 去重，不代表系统异常
- 当前调试前端提交逻辑在：
  - `ui/src/App.tsx` 的 `handleSubmitStudentTurn()`
  - `ui/src/components/StudentInputPanel.tsx`

### 3. 主动关闭会话

- 方法：`POST`
- 路径：`/api/sessions/{sessionId}/close`
- 请求体：无

业务出参：`SessionSnapshotResponse`

该接口会把会话状态设置为 `CLOSED`，并返回当前快照。客户端收到成功响应后应主动关闭当前 WS 连接，并清理本地播放器/状态。

当前调试前端对应逻辑在：

- `ui/src/App.tsx` 的 `handleCloseSession()`

### 4. 获取会话快照

- 方法：`GET`
- 路径：`/api/sessions/{sessionId}`
- 入参：仅路径参数 `sessionId`

业务出参：`SessionSnapshotResponse`

顶层字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `string` | 会话 ID |
| `title` | `string` | 创建会话时传入的文章标题 |
| `author` | `string` | 创建会话时传入的作者 |
| `language` | `string` | 创建会话时传入的语言 |
| `status` | `string` | 会话状态 |
| `currentRoundNo` | `number` | 当前轮次 |
| `currentTurnNo` | `number` | 当前教师回合号 |
| `awaitingStudentAnswer` | `boolean` | 是否正在等待学生回答 |
| `lastAssistantMessageText` | `string \| null` | 最近一次教师端完整文本 |
| `createdAt` | `string` | 创建时间，`Instant` 序列化结果 |
| `updatedAt` | `string` | 更新时间，`Instant` 序列化结果 |
| `turns` | `TurnSnapshot[]` | 历史轮次快照 |

`TurnSnapshot` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `turnNo` | `number` | 教师回合号 |
| `roundNo` | `number` | 阅读轮次 |
| `teacherReplyFinal` | `string \| null` | 本轮教师完整回复 |
| `studentAnswerRaw` | `string \| null` | 学生原始回答 |
| `studentAnswerNormalized` | `string \| null` | 学生标准化后的回答，规则是 `trim + 压缩连续空白` |
| `decision` | `string` | 决策结果。当前实现只会写入 `NEXT_ROUND`，其他枚举值为预留 |
| `startedAt` | `string` | 本轮开始时间 |
| `completedAt` | `string` | 本轮结束时间 |

业务出参示例：

```json
{
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "title": "草原",
  "author": "老舍",
  "language": "zh-HK",
  "status": "WAITING_STUDENT",
  "currentRoundNo": 1,
  "currentTurnNo": 1,
  "awaitingStudentAnswer": true,
  "lastAssistantMessageText": "老师刚刚说完的一整段话",
  "createdAt": "2026-03-30T03:11:11.111Z",
  "updatedAt": "2026-03-30T03:11:18.222Z",
  "turns": []
}
```

联调注意：

- 当前调试前端在收到 `assistant.turn.done` 后，会主动再调一次该接口刷新 `status` 和 `currentRoundNo`
- 如果未来后端想把完整快照直接放进 WS 事件里，前端工程师需要同步检查 `ui/src/App.tsx` 的 `handleAssistantEvent()` 中 `assistant.turn.done` 分支

### 会话状态说明

| 状态 | 说明 |
| --- | --- |
| `CREATED` | 刚创建对象时的内部初始态，接口层通常很快就会进入 `GENERATING` |
| `GENERATING` | 教师端正在生成文本/音频，前端不应允许学生提交 |
| `WAITING_STUDENT` | 等待学生作答，可以调用提交接口 |
| `FAILED` | 教师端流式生成失败 |
| `CLOSED` | 会话已被主动关闭 |
| `COMPLETED` | 枚举中已定义，但当前代码路径尚未使用 |

## WebSocket 协议

### 连接地址

- 路径：`/ws/sessions/{sessionId}`
- 建议时机：创建会话成功后立即连接
- 当前后端允许的前端 Origin 仅配置了 `http://localhost:5173`

如果前端页面部署地址不是这个 Origin，需同步修改：

- `src/main/java/com/wlinsk/rd_machine/transport/ws/WsConfig.java`

### 客户端发给服务端的消息

当前 WS 没有定义业务入站协议，客户端原则上只需要接收服务端推送。

现有行为只有两种：

1. 发送纯文本 `ping`

服务端返回纯文本：

```text
pong
```

2. 发送其他任意文本

服务端返回：

```json
{
  "type": "noop",
  "data": {}
}
```

注意：

- 上面的 `noop` 不是标准 `AssistantEvent` 包格式，因为它没有 `sessionId`、`turnNo`、`roundNo`
- 当前调试前端里的 `ui/src/ws.ts` 只会解析标准 `AssistantEvent`，所以它会忽略这类 `noop`

### 服务端推送事件通用格式

服务端推送统一使用 `AssistantEvent` 结构：

```json
{
  "type": "assistant.text.delta",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `string` | 事件类型 |
| `sessionId` | `string` | 会话 ID |
| `turnNo` | `number` | 当前教师回合号 |
| `roundNo` | `number` | 当前阅读轮次 |
| `data` | `object` | 具体事件数据 |

当前调试前端的事件消费入口在：

- `ui/src/ws.ts` 的 `parseAssistantEventMessage()`
- `ui/src/App.tsx` 的 `handleAssistantEvent()`

### 推送事件清单

| 事件类型 | `data` 字段 | 说明 |
| --- | --- | --- |
| `assistant.debug.timing` | `phase`、`serverTimestampMs`、`elapsedMs` | 调试时序事件 |
| `assistant.text.delta` | `delta` | LLM 增量文本 |
| `assistant.text.done` | `text` | 教师端本回合完整文本 |
| `assistant.audio.chunk` | `segmentSeq`、`audioFormat`、`sampleRate`、`chunkBase64` | TTS 音频分片 |
| `assistant.audio.done` | 无 | 音频流结束 |
| `assistant.turn.done` | `awaitingStudentAnswer` | 本轮教师端处理完成 |
| `assistant.error` | `code`、`message` | 本轮流式生成失败 |

下面按事件分别给出数据包格式。

#### 1. `assistant.debug.timing`

`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `phase` | `string` | 阶段名 |
| `serverTimestampMs` | `number` | 服务端时间戳，毫秒 |
| `elapsedMs` | `number` | 相对于本回合开始时刻的耗时，毫秒 |

当前代码里可能出现的 `phase`：

- `turn.start`
- `tts.open.start`
- `tts.session.ready`
- `tts.open.returned`
- `llm.request.start`
- `llm.first.delta`
- `llm.stream.completed`
- `tts.first.audio`
- `tts.stream.completed`
- `turn.completed`

数据包示例：

```json
{
  "type": "assistant.debug.timing",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "phase": "llm.first.delta",
    "serverTimestampMs": 1743304278123,
    "elapsedMs": 128
  }
}
```

前端调试时序展示可参考：

- `ui/src/App.tsx` 中 `assistant.debug.timing` 分支
- `ui/src/components/EventTimeline.tsx`
- `ui/src/components/SessionPanel.tsx`

#### 2. `assistant.text.delta`

`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `delta` | `string` | 本次新增的文本片段 |

数据包示例：

```json
{
  "type": "assistant.text.delta",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "delta": "今天我们先读第一段。"
  }
}
```

前端应将多个 `delta` 追加成完整文本，参考：

- `ui/src/App.tsx` 中 `assistant.text.delta` 分支
- `ui/src/components/AssistantStreamPanel.tsx`

#### 3. `assistant.text.done`

`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | `string` | 本回合完整教师回复 |

数据包示例：

```json
{
  "type": "assistant.text.done",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "text": "今天我们先读第一段。请你说说这段主要写了什么。"
  }
}
```

前端当前会把它写入“最终文本”区域，参考：

- `ui/src/App.tsx` 中 `assistant.text.done` 分支
- `ui/src/components/AssistantStreamPanel.tsx`

#### 4. `assistant.audio.chunk`

`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `segmentSeq` | `number` | 音频分片序号 |
| `audioFormat` | `string` | 音频格式。当前配置是 `pcm` |
| `sampleRate` | `number` | 采样率。当前配置默认 `24000` |
| `chunkBase64` | `string` | Base64 编码后的音频字节数据 |

数据包示例：

```json
{
  "type": "assistant.audio.chunk",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "segmentSeq": 3,
    "audioFormat": "pcm",
    "sampleRate": 24000,
    "chunkBase64": "AAABAAIAAwAEAAUA..."
  }
}
```

联调注意：

- 当前前端按“`Base64 -> Int16 PCM -> Float32 -> Web Audio`”的方式播放
- 如果你要接别的前端播放器，先看：
  - `ui/src/App.tsx` 中 `assistant.audio.chunk` 分支
  - `ui/src/audio/pcmPlayer.ts` 中 `decodeBase64ToInt16()`、`enqueueBase64Pcm()`、`ensureReady()`
- `sampleRate` 不能在客户端写死，应该以事件里的值为准

#### 5. `assistant.audio.done`

`data`：空对象

数据包示例：

```json
{
  "type": "assistant.audio.done",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {}
}
```

联调注意：

- 即使当前没有开启 TTS，后端也会在文本完成后补发一个 `assistant.audio.done`
- 所以前端不能假设“收到 `assistant.audio.done` 之前一定出现过 `assistant.audio.chunk`”

#### 6. `assistant.turn.done`

`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `awaitingStudentAnswer` | `boolean` | 服务端是否已进入等待学生作答状态 |

数据包示例：

```json
{
  "type": "assistant.turn.done",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "awaitingStudentAnswer": true
  }
}
```

联调注意：

- 当前这个事件只告诉你“本轮结束了”，不会把完整快照一起带回来
- 现有前端在收到它后，会立刻调用 `GET /api/sessions/{sessionId}` 拉快照
- 前端工程师请重点看：
  - `ui/src/App.tsx` 中 `assistant.turn.done` 分支
  - `ui/src/api.ts` 中 `fetchSessionSnapshot()`

#### 7. `assistant.error`

`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | `string` | 错误码 |
| `message` | `string` | 错误信息 |

数据包示例：

```json
{
  "type": "assistant.error",
  "sessionId": "0c5b6de6-7f77-4d76-9303-31c0324d5a65",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "code": "ASSISTANT_STREAM_FAILED",
    "message": "upstream timeout"
  }
}
```

联调注意：

- 当前实现里，LLM/TTS 流式失败时会推送该事件
- 某些失败场景下，会话状态也会被置为 `FAILED`
- 当前前端错误处理入口在 `ui/src/App.tsx` 中 `assistant.error` 分支

## 前端工程师需要重点看的代码段

如果只是照协议对接，优先看下面几段：

| 场景 | 代码位置 | 为什么要看 |
| --- | --- | --- |
| HTTP 包装层解析 | `ui/src/api.ts` 的 `request()`、`unwrapApiResult()` | 这里统一处理 `Result<T>` 外层包 |
| 创建会话后立即连 WS | `ui/src/App.tsx` 的 `handleSelectArticle()`、`connectSocket()` | 这里决定会不会错过第一批流式事件 |
| WS 地址拼装 | `ui/src/ws.ts` 的 `openSessionSocket()` | 当前前端自己拼 WS URL，没有直接使用后端返回的 `wsUrl` |
| WS 事件解析 | `ui/src/ws.ts` 的 `parseAssistantEventMessage()` | 这里只接受标准 `AssistantEvent` |
| 所有 WS 事件消费 | `ui/src/App.tsx` 的 `handleAssistantEvent()` | 每种 `assistant.*` 事件的客户端行为都在这里 |
| 学生回答提交 | `ui/src/App.tsx` 的 `handleSubmitStudentTurn()` | 这里定义了 `clientSeq`、`text` 的实际提交方式 |
| 音频解码与播放 | `ui/src/audio/pcmPlayer.ts` | 这里定义了 `assistant.audio.chunk` 的客户端消费格式 |
| 关闭会话清理 | `ui/src/App.tsx` 的 `handleCloseSession()` | 这里处理 socket 关闭、播放器 reset 和状态清理 |

## 当前协议里的几个实现性提醒

- `CreateSessionResponse.wsUrl` 当前是相对路径，不是完整 URL
- `SubmitTurnRequest.clientSeq` 是幂等去重键，客户端应保证每次真实提交都不重复
- `assistant.turn.done` 不是完整快照，仍需配合 HTTP 快照接口使用
- `assistant.audio.chunk` 当前按 PCM 数据播放，客户端如果不用现成调试前端，需要自行实现解码/播放

# 可复用 TTS Session 接口

当前仓库支持两类可复用 TTS Session，并额外提供 CosyVoice 流式合成接口：

- AI 教师对话链路会在同一个阅读 `sessionId` 内跨多个 Turn 复用一条上游 realtime TTS Session。
- 文章朗读链路会在同一个朗读 `sessionId` 内跨多个句子请求复用另一条上游 realtime TTS Session。
- CosyVoice 链路不提供按 `sessionId` 关闭或复用的 TTS Session；它复用的是连接池里的 CosyVoice WebSocket 连接，复用条件是 `language` 归一化后的语种和 `ssml` 是否开启。

## AI 教师对话链路

- 同一个阅读 `sessionId` 会跨 Turn 复用一条上游 realtime TTS Session。
- 每个教师 Turn 都会在这条可复用 TTS Session 上打开一个新的 utterance。
- 关闭阅读会话时，也会同步关闭绑定的上游 TTS Session。
- 如果当前教师 Turn 被取消，后端会关闭当前上游 TTS Session，后续 Turn 会重新创建干净的新 Session。

## HTTP TTS Session 接口

### 1. `POST /api/tts/sessions/stream`

- Content-Type: `application/json`
- Response Content-Type: `text/event-stream`
- Controller return type: `SseEmitter`

Request body:

```json
{
  "sessionId": null,
  "language": "zh-CN",
  "sentence": "这是前端准备朗读的一句话。"
}
```

行为说明：

- `sessionId` 为空时，后端会创建一个新的可复用 TTS Session，并且在后续每一条流式事件里都返回这个 `sessionId`。
- `sessionId` 不为空时，后端会复用这个已有的 TTS Session。
- 前端传入的虽然是一整句，后端仍可能再次切分后再送给 realtime TTS。

事件结构：

```json
{
  "type": "audio.chunk",
  "sessionId": "tts-session-id",
  "data": {
    "segmentSeq": 1,
    "audioFormat": "pcm",
    "sampleRate": 24000,
    "chunkBase64": "AAABAAIA..."
  }
}
```

支持的事件类型：

- `audio.chunk`
- `audio.done`
- `audio.error`

事件含义：

- `audio.chunk`
  当前句子返回了一段可立即播放的 PCM 音频。前端应立即把 `data.chunkBase64` 送给播放器，并缓存事件顶层的 `sessionId`。
- `audio.done`
  当前句子的音频流已经结束。这是同一个 `sessionId` 上“可以发送下一句”的推荐判断信号。
- `audio.error`
  当前句子失败。本次请求也已经结束，前端可以选择重试当前句、跳过当前句，或直接关闭该 `sessionId`，但不要和当前句并发发送下一句。

前端接入要点：

- 因为这是 `POST` 接口，前端应使用 `fetch` + `ReadableStream` 读取 `text/event-stream`，不要用浏览器原生 `EventSource`。
- `segmentSeq`、`audioFormat`、`sampleRate`、`chunkBase64` 与 `ui/src/audio/pcmPlayer.ts` 的现有 PCM 播放契约对齐，可以直接复用播放器。
- 第一句请求时传 `sessionId: null`；拿到任意一条事件后，把返回的 `sessionId` 缓存起来，后续句子继续复用。
- 同一个 `sessionId` 上，句子请求必须串行。不要在收到某个 `audio.chunk` 后就立刻发送下一句，因为这只代表“当前句已经有音频返回”，不代表“当前句已经结束”。
- 前端只有在当前这次 `/stream` 请求收到终态事件后，才可以发送下一句。终态事件有两个：
  - `audio.done`：正常结束，推荐以它作为“发送下一句”的判断条件。
  - `audio.error`：异常结束，本次请求也算结束，但需要前端自行决定是重试、跳过还是关闭 Session。
- 如果同一个 `sessionId` 上前一句还没结束就继续发下一句，后端可能返回错误码 `9986`，即 `TTS session is busy`。

前端接入时可直接参考现成调试前端代码：

- `ui/src/api.ts`
  这里封装了 `streamTtsSentence()` 和 `closeTtsSession()`，包含 `fetch` 读取 SSE、SSE 帧解析、以及 `POST /api/tts/sessions/stream` / `POST /api/tts/sessions/close` 的实际请求写法。
- `ui/src/App.tsx`
  这里包含完整的句子朗读状态控制逻辑，重点看 `handleStartSentenceTts()`、`handleTtsSentenceEvent()`、`handleCloseSentenceTtsSession()`。
- `ui/src/components/TtsSentenceLab.tsx`
  这里是调试入口的表单和按钮交互，可以直接作为前端接入参考。

其中“什么时候可以发送下一句”的现成判断逻辑也在 `ui/src/App.tsx`：

- 收到 `audio.chunk` 时，只播放当前音频，不发送下一句。
- 收到 `audio.done` 时，把当前流状态切回 `idle`，此时才允许发送下一句。
- 收到 `audio.error` 时，当前句也算结束，但前端应先决定重试、跳过还是关闭 Session，再决定是否发送下一句。

### 2. `POST /api/cosyvoice/tts/stream`

- Content-Type: `application/json`
- Response Content-Type: `text/event-stream`
- Controller return type: `SseEmitter`
- 前端调试入口：`TTS Sentence Lab` 里选择 `CosyVoice v3 Flash`

Request body:

```json
{
  "sessionId": null,
  "language": "zh-CN",
  "sentence": "这是前端准备通过 CosyVoice 朗读的一句话。",
  "ssml": false
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | `string \| null` | 否 | 前端事件关联 ID。为空时后端生成一个，并在后续 SSE 事件顶层 `sessionId` 返回。它不是 CosyVoice 连接复用 key。 |
| `language` | `string` | 否 | 后端用它选择音色和连接池分组。当前 `en*` 归为英文，其它归为中文。 |
| `sentence` | `string` | 是 | 要合成的文本。为空会返回 `audio.error`。 |
| `ssml` | `boolean` | 否 | 是否按 SSML 发送给 CosyVoice。默认 `false`。开启后前端应保证 `sentence` 是合法 SSML 文本。 |

行为说明：

- 该接口直接返回 SSE，不走 `Result<T>` 包装。
- 每次 HTTP 请求对应一次内部 CosyVoice `taskId`。`taskId` 只保留在后端日志和上游请求链路里，不会返回给前端 SSE。
- 对前端返回的事件类型和 `data` 字段与 `POST /api/tts/sessions/stream` 保持一致：`audio.chunk`、`audio.done`、`audio.error`。
- `sessionId` 用于让前端把当前请求的 `audio.chunk`、`audio.done`、`audio.error` 关联到同一次 UI 流；它不表示一条可关闭的后端 TTS Session。
- 如果前端不需要跨请求展示同一个 UI 关联 ID，可以每次都传 `sessionId: null`；如果传入上次返回的 `sessionId`，后端只会原样用于事件返回和日志，不会因此绑定或固定某条 CosyVoice 连接。
- CosyVoice WebSocket 连接由 `CosyVoiceConnectionPool` 复用，连接池 key 是 `CosyVoiceConnectionKey(languageType, ssml)`，其中 `languageType` 只有 `en` / `zh` 两类。
- 正常完成后，连接会 `releaseReusable` 回连接池；SSE 超时、前端取消、emitter error 或合成失败时，当前连接会被 `discard`。
- CosyVoice 没有对应的 `/close` 接口。前端“关闭”时应取消当前 `fetch` 流、清空本地 `sessionId` 和播放状态。

SSE 帧示例：

```text
event: audio.chunk
data: {"type":"audio.chunk","sessionId":"cosy-ui-session-id","data":{"segmentSeq":1,"audioFormat":"pcm","sampleRate":22050,"chunkBase64":"AAABAAIA..."}}
```

支持的事件类型：

| 事件类型 | `data` 字段 | 前端处理 |
| --- | --- | --- |
| `audio.chunk` | `segmentSeq`、`audioFormat`、`sampleRate`、`chunkBase64` | 播放音频分片。字段结构与 TtsSession 接口一致。 |
| `audio.done` | 空对象 | 当前 CosyVoice 任务正常结束。这是允许发送下一句的推荐信号。 |
| `audio.error` | `code`、`message` | 当前任务失败。本次请求结束，前端应展示错误并决定重试、跳过或取消。 |

前端接入要点：

- 由于这是 `POST` + SSE，前端应使用 `fetch` + `ReadableStream` 读取响应，不要使用浏览器原生 `EventSource`。
- 现有封装是 `ui/src/api.ts` 的 `streamTtsSentence(payload, onEvent, "cosyvoice")`，实际请求路径是 `/api/cosyvoice/tts/stream`。
- CosyVoice 请求会把 `ssml` 字段传给后端；切回 legacy realtime TTS 时，现有调试前端会把 `ssml` 重置为 `false`。
- 前端收到任意事件后，如果顶层 `event.sessionId` 有值，可以缓存到 UI 状态里用于展示和后续请求关联；但不要调用 `/api/tts/sessions/close` 去关闭它。
- `audio.chunk` 到达时，把 `data.chunkBase64` 按 `data.sampleRate` 送给 PCM 播放器。不要写死采样率，当前 CosyVoice 默认是 `22050`，legacy realtime TTS 默认是 `24000`。
- 前端不需要处理 CosyVoice 专属的 `cosyvoice.event` 或 `taskId`；上游阶段信息只保留在后端日志中。
- `audio.done` 或 `audio.error` 是终态事件。只有收到终态事件，或当前 `fetch` 被主动取消后，才应允许开始下一次 CosyVoice 请求。
- 当前调试前端的处理入口在 `ui/src/App.tsx` 的 `handleTtsSentenceEvent()`；按钮和端点切换在 `ui/src/components/TtsSentenceLab.tsx`。

如果前端通过 Nginx 反向代理访问这些 SSE 接口，需要给 SSE 接口单独加代理配置，不要只复用普通的 `/llk-ai/` 反代。原因是 `POST /api/tts/sessions/stream` 和 `POST /api/cosyvoice/tts/stream` 返回的都是 `text/event-stream`，如果 Nginx 开着响应缓冲，前端可能会出现“请求已成功但迟迟收不到事件”或者“事件攒到一句结束后才一起返回”。

推荐配置示例：

```nginx
location = /llk-ai/api/cosyvoice/tts/stream {
    proxy_pass http://127.0.0.1:8080/api/cosyvoice/tts/stream;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";

    proxy_buffering off;
    proxy_cache off;
    gzip off;

    proxy_connect_timeout 60s;
    proxy_send_timeout 300s;
    proxy_read_timeout 300s;

    add_header X-Accel-Buffering no always;
}

location = /llk-ai/api/tts/sessions/stream {
    proxy_pass http://127.0.0.1:8080/api/tts/sessions/stream;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";

    proxy_buffering off;
    proxy_cache off;
    gzip off;

    proxy_connect_timeout 60s;
    proxy_send_timeout 300s;
    proxy_read_timeout 300s;

    add_header X-Accel-Buffering no always;
}

location /llk-ai/ws/ {
    proxy_pass http://127.0.0.1:8080/ws/;

    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";

    proxy_connect_timeout 60s;
    proxy_send_timeout 300s;
    proxy_read_timeout 300s;
}

location /llk-ai/ {
    proxy_pass http://127.0.0.1:8080/;
}
```

Nginx 配置注意事项：

- `/llk-ai/api/cosyvoice/tts/stream` 和 `/llk-ai/api/tts/sessions/stream` 这两个精确匹配的 `location` 要放在通用 `/llk-ai/` 之前。
- SSE 这条链路的关键配置是 `proxy_buffering off;`、`gzip off;`、`add_header X-Accel-Buffering no always;`。
- `POST /api/tts/sessions/close` 不需要特殊 SSE 配置，继续走普通 `/llk-ai/` 即可。

### 3. `POST /api/tts/sessions/close`

- Content-Type: `application/json`
- Response: 普通 `Result<Void>`

Request body:

```json
{
  "sessionId": "tts-session-id"
}
```

行为说明：

- 关闭可复用的上游 realtime TTS Session。
- 从后端 Session Registry 中移除该 `sessionId`。
- 用户退出朗读模式、整篇文章播放完成、或前端不再需要复用这个 `sessionId` 时，应主动调用该接口释放资源。
