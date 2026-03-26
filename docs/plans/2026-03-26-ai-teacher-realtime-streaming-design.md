# AI Teacher Realtime Streaming Design

## 1. Goal

为当前阅读陪练系统补充一条可实时工作的老师侧语音链路：

- 学生进入 AI 老师页面后选择文章
- 后端立即开始首轮老师问题生成
- 老师文本以 Stream 形式实时输出到前端
- 老师文本在后端被分段送入阿里云实时 TTS
- 音频以 Stream 形式实时推送到前端播放
- 学生每轮通过语音回答，但先在前端完成 ASR，再把最终文本提交给后端开启下一轮推理

当前阶段不依赖 Spring AI。

## 2. Confirmed Constraints

- 沿用 [docs/design.md](/D:/develop/reading_machine/rd_machine/docs/design.md) 的总体方向：
  - 后端状态机控流程
  - LLM 只负责当前轮老师话术
  - 非 ReAct Agent
- 前端负责录音和 ASR
- 前端提交给后端的是最终识别文本，不是原始音频流
- 老师侧必须支持实时文本展示与实时音频播放
- 当前仅讨论老师输出链路，不引入 RAG，不引入多工具 Agent

## 3. Architecture Decision

### 3.1 Rejected Options

#### Option A: 全量依赖 Spring AI

不采用。

原因：

- 当前需求核心是流式编排，不是统一模型抽象
- 需要同时接两段协议：
  - 百炼 OpenAI 兼容 LLM 流
  - 阿里云实时 TTS WebSocket
- Spring AI 会增加抽象层，但不会减少关键流控复杂度
- 当前项目体量很小，先保持依赖最薄更稳

#### Option B: 单一双向 WebSocket 同时承担上行和下行

当前阶段不采用。

原因：

- 学生上行不是持续媒体流，而是 ASR 完成后的业务提交
- 用单一 WS 处理提交、重试、幂等、恢复，复杂度高于收益
- 当前更适合把业务动作和流式结果拆开

#### Option C: LLM 输出 token 后原样逐 token 转给 TTS

不采用。

原因：

- 中文断句差
- 音频抖动明显
- TTS 收尾容易不自然
- token 过碎会增加链路负担

### 3.2 Recommended Option

采用：

`HTTP 上行 + WS 下行 + 后端流式桥接`

具体为：

- 前端通过 HTTP 创建 session 和提交学生文本
- 前端通过一个 session 级 WS 接收老师文本流和音频流
- 后端使用 `openai-java` 调百炼兼容接口获取 LLM Stream
- 后端将 LLM 文本增量即时推给前端
- 后端同时做短句缓冲，将可播报片段送入阿里云实时 TTS
- 后端把 TTS 产生的音频 chunk 继续通过同一个 WS 推给前端

## 4. Interaction Model

### 4.1 Session Lifecycle

1. 学生进入 AI 老师页面
2. 前端请求文章列表
3. 学生点击文章
4. 前端调用 `POST /api/sessions`
5. 后端创建 session，并立即触发首轮老师生成
6. 前端连接 `/ws/sessions/{sessionId}`
7. 前端接收老师文字增量与音频分片
8. 老师本轮结束后，前端等待学生说话
9. 学生语音在前端完成 ASR
10. 前端展示最终识别文本
11. 前端调用 `POST /api/sessions/{sessionId}/turns`
12. 后端推进状态机并启动下一轮生成

### 4.2 Why Not a Long-Running Agent

实现上不维护“常驻 Agent 线程”。

正确模型是：

- 创建一个受控 `session`
- 每一轮需要老师发言时，触发一次生成管线
- 本轮完成后进入等待学生回答状态
- 学生提交文本后再触发下一轮

这样更利于：

- 会话恢复
- 超时清理
- 幂等控制
- 状态追踪
- 资源占用控制

## 5. Transport Design

### 5.1 HTTP Responsibilities

HTTP 负责明确业务动作：

- 获取文章列表
- 创建阅读会话
- 提交学生文本
- 查询会话快照

建议接口：

- `GET /api/articles`
- `POST /api/sessions`
- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}`

### 5.2 WebSocket Responsibilities

WebSocket 只负责老师侧实时下行。

建议连接：

- `/ws/sessions/{sessionId}`

建议承载事件：

- `assistant.text.delta`
- `assistant.text.done`
- `assistant.audio.chunk`
- `assistant.audio.done`
- `assistant.turn.done`
- `assistant.error`

结论：

- 上行：HTTP
- 下行：单一 session 级 WS

## 6. Session and Turn Model

### 6.1 Session

`Session` 表示整场阅读陪练会话。

建议字段：

- `sessionId`
- `articleId`
- `articleTitle`
- `articleAuthor`
- `articleLanguage`
- `articleContent`
- `status`
- `currentRoundNo`
- `currentTurnNo`
- `awaitingStudentAnswer`
- `lastAssistantMessageText`
- `summaryContext`
- `createdAt`
- `updatedAt`

说明：

- 创建 session 时保存 article 快照，而不是只保留 `articleId`
- `summaryContext` 用于控制 prompt 长度
- 流式音频 chunk 不落库

### 6.2 Turn

`Turn` 表示一次老师输出与一次学生提交构成的交互记录。

建议字段：

- `turnId`
- `sessionId`
- `turnNo`
- `roundNo`
- `teacherReplyFinal`
- `studentAnswerRaw`
- `studentAnswerNormalized`
- `asrMeta`
- `decision`
- `status`
- `startedAt`
- `completedAt`

说明：

- `studentAnswerRaw` 保存前端 ASR 最终文本
- `studentAnswerNormalized` 保存后端轻度归一化后的文本
- `decision` 表示本轮处理结果：
  - `FOLLOW_UP`
  - `NEXT_ROUND`
  - `FINISH`

### 6.3 State Split

不要把“运行状态”和“教学轮次”混成一个大枚举。

建议拆分为：

运行状态：

- `CREATED`
- `GENERATING`
- `WAITING_STUDENT`
- `COMPLETED`
- `FAILED`
- `CLOSED`

教学轮次：

- `ROUND_1`
- `ROUND_2`
- `ROUND_3`
- `ROUND_4`
- `ROUND_5`

这样更容易维护追问、恢复和异常分支。

## 7. Realtime Streaming Pipeline

### 7.1 End-to-End Flow

每一轮 assistant 输出时，后端内部执行如下管线：

1. 读取 `Session` 和当前轮上下文
2. 构造本轮 prompt
3. 使用 `openai-java` 调百炼兼容接口获取 `LLM Stream`
4. 每收到一个文本 delta：
   - 立即推送 `assistant.text.delta` 给前端
   - 同时写入本地分段缓冲区
5. 当缓冲区满足分段条件时，将片段送入阿里云实时 TTS WebSocket
6. TTS 返回音频分片后，立即推送 `assistant.audio.chunk` 给前端
7. LLM 结束后，flush 剩余文本片段
8. 向 TTS 发出结束信号，等待尾部音频完成
9. 推送 `assistant.text.done`、`assistant.audio.done`、`assistant.turn.done`
10. 会话状态切换为 `WAITING_STUDENT`

### 7.2 Segmenting Strategy

TTS 输入不能直接复用 LLM token chunk。

推荐分段规则：

- 遇到句号、问号、叹号、逗号、分号时优先切段
- 达到最小文本长度阈值时允许切段
- 超过最大等待时间时强制切段

推荐初始阈值：

- 最小长度：8 到 20 个汉字
- 最大等待：150 到 300 毫秒

这是实时性和播报自然度之间的折中。

### 7.3 Backpressure and Ordering

必须显式处理以下问题：

- LLM 速度快于 TTS 时的堆积
- 文本和音频的顺序对齐
- 本轮结束后的尾包收敛

建议：

- 为待合成文本片段设置有界队列
- 每个片段分配 `segmentSeq`
- 所有事件带上 `sessionId`、`turnNo`、`roundNo`
- 音频事件额外带 `segmentSeq`

## 8. API Sketch

### 8.1 Create Session

请求：

```json
{
  "articleId": "shaonian-runtu"
}
```

返回：

```json
{
  "sessionId": "sess_123",
  "status": "GENERATING",
  "currentRoundNo": 1,
  "currentTurnNo": 1,
  "wsUrl": "/ws/sessions/sess_123"
}
```

### 8.2 Submit Student Turn

请求：

```json
{
  "turnNo": 1,
  "text": "我覺得閏土是一個很活潑的孩子",
  "clientSeq": 1,
  "asrMeta": {
    "final": true,
    "confidence": 0.93
  }
}
```

返回：

```json
{
  "accepted": true,
  "sessionId": "sess_123",
  "status": "GENERATING",
  "currentRoundNo": 2,
  "currentTurnNo": 2
}
```

## 9. WebSocket Event Sketch

统一事件包络：

```json
{
  "type": "assistant.text.delta",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {}
}
```

### 9.1 assistant.text.delta

```json
{
  "type": "assistant.text.delta",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "delta": "你覺得閏土最吸引你的地方是什麼？"
  }
}
```

### 9.2 assistant.text.done

```json
{
  "type": "assistant.text.done",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "text": "你覺得閏土最吸引你的地方是什麼？請用一兩句話回答。"
  }
}
```

### 9.3 assistant.audio.chunk

```json
{
  "type": "assistant.audio.chunk",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "segmentSeq": 1,
    "audioFormat": "pcm",
    "sampleRate": 24000,
    "chunkBase64": "..."
  }
}
```

实现时优先使用 WebSocket 二进制帧，而不是长期依赖 base64 JSON。

### 9.4 assistant.audio.done

```json
{
  "type": "assistant.audio.done",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {}
}
```

### 9.5 assistant.turn.done

```json
{
  "type": "assistant.turn.done",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "awaitingStudentAnswer": true
  }
}
```

### 9.6 assistant.error

```json
{
  "type": "assistant.error",
  "sessionId": "sess_123",
  "turnNo": 1,
  "roundNo": 1,
  "data": {
    "code": "TTS_STREAM_FAILED",
    "message": "tts stream interrupted"
  }
}
```

## 10. Backend Module Boundaries

当前阶段不依赖 Spring AI，建议按职责拆分：

- `article`：文章列表与文章快照读取
- `session`：会话创建、查询、状态推进
- `prompt`：本轮 prompt 构造
- `llm`：基于 `openai-java` 的百炼兼容客户端封装
- `tts`：阿里云实时 TTS WebSocket 客户端封装
- `streaming`：文本分段、队列、桥接和事件分发
- `transport`：HTTP Controller 与 WS Handler

关键边界：

- `llm` 只负责文本流
- `tts` 只负责文本转音频流
- `streaming` 负责把两者串起来
- `session` 不直接关心第三方协议细节

## 11. Configuration Proposal

`application.properties` 不建议只保留一组通用参数。

建议拆分：

```properties
rd.ai.llm.api-key=
rd.ai.llm.base-url=
rd.ai.llm.model=

rd.ai.tts.api-key=
rd.ai.tts.ws-url=
rd.ai.tts.model=
rd.ai.tts.voice=
rd.ai.tts.sample-rate=24000
```

说明：

- LLM 和 TTS 是两条不同链路
- 它们的地址、模型、鉴权和运行参数不应混用

## 12. Risks and Failure Modes

### 12.1 Main Risks

- LLM 兼容接口与 `openai-java` 某些高级特性不完全一致
- TTS WebSocket 收尾协议处理不当导致尾音丢失
- 文本分段策略不合适导致延迟高或播报不自然
- 前端未按顺序消费音频 chunk 导致播放抖动
- 会话断线恢复时重复触发同一轮生成

### 12.2 Mitigations

- 首版仅使用百炼兼容 `chat/completions` 流式能力
- 明确区分 `text.done`、`audio.done`、`turn.done`
- 片段有序编号，服务端强制单轮单流
- `POST /turns` 使用 `clientSeq` 做幂等控制
- `GET /sessions/{sessionId}` 用于页面刷新后的快照恢复

## 13. Validation Approach

当前阶段优先做轻量验证，不以自动化测试为前置条件。

建议验证项：

- 创建 session 后是否能立即触发首轮生成
- 老师文字是否能以增量方式到达前端
- 文本分段后是否能持续产生音频 chunk
- 一轮结束后是否只进入 `WAITING_STUDENT`
- 学生提交文本后是否只触发一轮新的生成
- 异常时是否能推送 `assistant.error`

## 14. Implementation Recommendation

推荐按以下顺序落地：

1. 先打通会话状态机和 HTTP/WS 外壳
2. 接入 LLM 文本流，只推文字
3. 加入分段缓冲
4. 接入阿里云实时 TTS
5. 打通文字和音频双流下发
6. 最后再做恢复、幂等和异常收敛

这是当前最安全的增量路径。

## 15. References

- OpenAI Java SDK:
  - https://github.com/openai/openai-java
- 阿里云百炼 OpenAI 兼容接口:
  - https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope
- 阿里云实时语音合成:
  - https://help.aliyun.com/zh/model-studio/qwen-tts-realtime
