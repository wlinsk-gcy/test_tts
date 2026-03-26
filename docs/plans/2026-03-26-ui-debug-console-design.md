# UI Debug Console Design

## Goal

在当前仓库新增一个独立运行的 `ui/` 调试前端，用于联调当前后端的 AI 老师链路，并可直接观察：

- 文章列表加载与会话创建
- WebSocket 连接状态
- LLM 文本流首包时间和增量到达节奏
- TTS 音频 chunk 首包时间和连续到达情况
- 老师音频的真实播放效果
- 学生文本提交后下一轮是否继续生成

该前端当前作为独立开发服务器运行，不与 Spring Boot 打包集成。

## Confirmed Constraints

- 技术栈：React + TypeScript + Vite
- 前端目录直接放在仓库根目录下的 `ui/`
- 当前目标是联调和观察时序，不是正式产品页
- 必须能真实播放老师音频，不能只展示 chunk 到达日志
- 必须能展示关键流式时延指标
- 当前后端接口固定为：
  - `GET /api/articles`
  - `POST /api/sessions`
  - `POST /api/sessions/{sessionId}/turns`
  - `GET /api/sessions/{sessionId}`
  - `WS /ws/sessions/{sessionId}`

## Options

### Option A: Spring Boot static 调试页

不推荐。

优点：
- 最快可用

问题：
- 后续很快要重做成正式前端
- 音频播放队列、事件面板和联调逻辑会堆成一次性脚本
- 不适合持续扩展

### Option B: `ui/` 独立 Vite 调试前端

推荐。

优点：
- 结构清晰，可直接继续演进
- 便于独立启动和快速迭代
- 适合实现音频队列、时延面板和事件日志

### Option C: 直接完整产品前端

当前不推荐。

问题：
- 会把联调和产品实现绑死
- 当前更需要先看清楚流式链路质量，而不是先做完整交互系统

## Recommended Architecture

采用独立 `ui/` Vite 前端，页面只做一个高可观察性的联调控制台。

### Layout

页面分为四个区域：

1. 控制区
- 加载文章列表
- 点击文章创建 session
- 连接和断开 WebSocket
- 提交学生文本
- 清空日志

2. 会话状态区
- 当前 `sessionId`
- 当前轮次
- 当前状态
- WebSocket 连接状态
- 最近一次错误信息

3. 老师流式区
- assistant 文本增量显示
- 当前完整老师文本
- `TTFT`：创建 session 到首个文本 delta
- 文本完成时间

4. 音频与事件区
- 音频播放状态
- `TTFA`：创建 session 到首个音频 chunk
- 音频 chunk 数量与累计字节数
- 完整事件时间线

## Audio Playback Strategy

不使用 `<audio>` 标签直接拼接流。

采用最小 PCM 播放器：

- WebSocket 收到 `assistant.audio.chunk`
- base64 解码为 `Int16Array`
- 转为 `Float32Array`
- 使用 `AudioContext` 顺序调度播放
- 记录每个 chunk 的接收时间和排队时间

原因：

- 当前后端下发的是 `pcm` 分片，而不是完整可播文件
- `<audio>` 不适合逐 chunk 低延迟拼接
- 需要可观测播放延迟，必须前端自己维护队列

## Data Flow

1. 页面加载后请求 `GET /api/articles`
2. 用户点击文章按钮
3. 前端调用 `POST /api/sessions`
4. 前端保存 `sessionId` 并连接 WS
5. 前端收到：
   - `assistant.text.delta`
   - `assistant.text.done`
   - `assistant.audio.chunk`
   - `assistant.audio.done`
   - `assistant.turn.done`
   - `assistant.error`
6. 音频 chunk 进入 PCM 播放器队列
7. 用户在输入框中输入“模拟 ASR 最终文本”
8. 前端调用 `POST /api/sessions/{sessionId}/turns`
9. 前端继续观察下一轮流式事件

## Metrics to Surface

页面必须显示这些时间点：

- `sessionCreateStartedAt`
- `sessionCreatedAt`
- `wsConnectedAt`
- `firstTextDeltaAt`
- `textDoneAt`
- `firstAudioChunkAt`
- `audioDoneAt`
- `turnDoneAt`

以及两个核心差值：

- `TTFT = firstTextDeltaAt - sessionCreateStartedAt`
- `TTFA = firstAudioChunkAt - sessionCreateStartedAt`

## Error Handling

调试页需要明确展示这些异常：

- HTTP 请求失败
- WS 连接失败或断开
- 音频上下文初始化失败
- 音频 chunk 解码失败
- 后端推送的 `assistant.error`

当前阶段只做显式展示，不做自动重试策略。

## Implementation Boundary

本轮只实现：

- `ui/` 工程骨架
- 单页调试控制台
- API/WS 接入
- PCM 音频播放器
- 基础时延与事件日志显示

本轮不实现：

- 正式路由系统
- UI 设计系统
- 用户身份体系
- 浏览器端录音和 ASR
- 和 Spring Boot 的静态资源集成

## Validation

联调完成后，前端应能人工验证：

- 文章可加载
- session 可创建
- WS 可连接
- 文本流可增量展示
- 音频流可真实播放
- `TTFT` 和 `TTFA` 可见
- 提交学生文本后可继续下一轮