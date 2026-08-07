# pi-agent-core 底层原理与复现指南

> 目标：把 `@earendil-works/pi-agent-core`（`packages/agent`）的核心功能拆解为可独立复现的原理。读完本文，可以在任意语言/运行时里重新实现一个等价的"通用 LLM Agent 运行时"。
>
> 阅读顺序建议：先看 §1–§3 建立全局观，再精读 §4（核心循环）和 §6（工具系统），这两节是整个库的灵魂。

---

## 目录

1. [它是什么 / 解决什么问题](#1-它是什么--解决什么问题)
2. [架构分层](#2-架构分层)
3. [核心设计哲学（6 条关键决策）](#3-核心设计哲学6-条关键决策)
4. [核心：Agent Loop（最关键的一节）](#4-核心agent-loop最关键的一节)
5. [消息模型：AgentMessage vs LLM Message](#5-消息模型agentmessage-vs-llm-message)
6. [工具系统](#6-工具系统)
7. [状态管理与生命周期（Agent 类）](#7-状态管理与生命周期agent-类)
8. [传输抽象：StreamFn 与 Proxy 模式](#8-传输抽象streamfn-与-proxy-模式)
9. [上下文窗口管理 / Compaction](#9-上下文窗口管理--compaction)
10. [执行环境抽象：FileSystem / Shell / ExecutionEnv](#10-执行环境抽象filesystem--shell--executionenv)
11. [上层 Harness：Session / Lane / Hook 契约](#11-上层-harnesssession--lane--hook-契约)
12. [最小复现清单与骨架伪代码](#12-最小复现清单与骨架伪代码)
13. [关键不变量与陷阱](#13-关键不变量与陷阱)

---

## 1. 它是什么 / 解决什么问题

`pi-agent-core` 是一个**通用 LLM Agent 运行时**。它不绑定任何具体模型厂商，不假定具体的传输方式（直连 / 代理 / 服务端），也不假定宿主环境（Node / 浏览器 / 沙箱）。它只做一件事：

> **把"模型流式输出 → 解析工具调用 → 执行工具 → 把结果塞回上下文 → 再次请求模型"这个循环，抽象成一个稳定、可观测、可中断、可扩展的状态机。**

一句话定位（来自 `package.json`）：

> *General-purpose agent with transport abstraction, state management, and attachment support.*

它建立在 `@earendil-works/pi-ai`（模型/传输/流协议的抽象）和 `@earendil-works/pi-telemetry`（可观测性）之上。本文只聚焦 agent 自身的原理，把这两个上游依赖当成"提供 `streamFn` 和 `telemetry`"的黑盒。

---

## 2. 架构分层

整个包自下而上分四层。复现时可以只实现下面几层，按需叠加。

```
┌─────────────────────────────────────────────────────────────┐
│ ④ Harness 层  AgentHarness / Session / Lane                  │
│   持久化会话树、多 lane、compaction、navigation、hook、resume  │
│   （src/harness/*，目前多为接口契约骨架）                       │
├─────────────────────────────────────────────────────────────┤
│ ③ 工具与环境层  AgentTool / FileSystem / Shell / ExecutionEnv │
│   工具定义、读写/编辑/bash 工具、执行环境抽象                    │
│   （src/harness/tools/*, src/harness/env/*）                  │
├─────────────────────────────────────────────────────────────┤
│ ② Agent 状态层  Agent 类                                       │
│   持有 transcript、事件订阅、steering/followUp 队列、abort      │
│   （src/agent.ts）                                            │
├─────────────────────────────────────────────────────────────┤
│ ① 核心循环层  runAgentLoop / runLoop / 工具执行                │
│   无状态、原地改写的循环驱动（输入上下文+配置 → 输出事件流）       │
│   （src/agent-loop.ts）                                       │
└─────────────────────────────────────────────────────────────┘
          │ 依赖
          ▼
   StreamFn 抽象（src/stream-fn.ts, src/proxy.ts）
   + pi-ai 的 Message / AssistantMessage / EventStream 协议
```

**最重要的分层原则**：第 ① 层（`runLoop`）**不在调用之间持有任何状态**——所有跨回合状态由调用方（第 ② 层 `Agent`）持有，并通过 `emit` 回调接收事件。注意它并非"纯函数"：运行中会**原地修改**传入的 `context.messages`（push prompt / partial / toolResult）。这意味着你可以直接拿 `runAgentLoop` 嵌入任何宿主，而不必使用 `Agent` 类。

---

## 3. 核心设计哲学（6 条关键决策）

复现时这 6 条决策比代码细节更重要——它们定义了"为什么这样设计"。

1. **AgentMessage 与 LLM Message 分离。** 内部全程使用 `AgentMessage`（可扩展自定义类型），只在**调用 LLM 的边界**才用 `convertToLlm` 转成模型能理解的 `Message[]`。这让 UI 通知、artifact、自定义结构都能进 transcript 而不污染模型上下文。

2. **错误绝不抛异常，编码进事件流。** `StreamFn` 的契约明确要求：模型/网络/运行时失败**不得 throw**，必须以 `stopReason: "error" | "aborted"` + `errorMessage` 的终态 `AssistantMessage` 收尾。工具 `execute` 内部可以 throw，但循环会捕获并转成 `isError` 的 tool result。结果是：**循环本身几乎不会因外部错误中断**，UI 永远能收到完整的 `agent_end`。

3. **流式 partial 是"同一对象原地更新"。** 模型流式输出时，`EventStream` 的每个事件都带一个累计的 `partial: AssistantMessage`。循环把这个 partial 直接 push 进 `context.messages`，后续每个 delta 事件**替换数组最后一个元素**为新的 partial。这样 transcript 始终是"当前已知最新状态"，下一轮 `transformContext` 能看到正在生成的消息。

4. **工具执行有两种模式，且可被单个工具降级。** 默认 `parallel`（先串行 preflight，再并发执行），但只要批次里有一个工具声明 `executionMode: "sequential"`，整批回退为串行。并行模式下，被并发执行的工具的 `tool_execution_end` 按**完成顺序**发（immediate 结果仍按源序先发），而 toolResult 消息恒按**模型给出的源顺序**发——这两个顺序解耦。

5. **steering 与 followUp 是两条独立队列。** `steer()` 在"当前助手回合结束后、下一回合开始前"插入消息（运行中插话）；`followUp()` 在"agent 本来要停下时"插入消息（接续任务）。两者都有 `"all" | "one-at-a-time"` 排水模式。

6. **运行时单实例串行。** 同一个 `Agent` 同一时刻只能有一个 `activeRun`。`prompt()`/`continue()` 在已有运行时会抛错；要插话必须走队列。这是为了避免 transcript 竞态。

---

## 4. 核心：Agent Loop（最关键的一节）

入口 `src/agent-loop.ts`。两个公开函数：

- `runAgentLoop(prompts, context, config, emit, signal, streamFn)` — 带新消息启动。
- `runAgentLoopContinue(context, config, emit, signal, streamFn)` — 不加新消息，从现有 transcript 续跑（用于重试）。

两者都校验后调用私有的 **`runLoop`**。这是整个库的心脏。

### 4.1 数据结构

```ts
interface AgentContext {
  systemPrompt: string;
  messages: AgentMessage[];   // 完整 transcript（运行中会被原地修改/追加）
  tools?: AgentTool[];
}

// emit 回调签名：循环所有对外输出都走这里
type AgentEventSink = (event: AgentEvent) => Promise<void> | void;
```

> **关键**：`context.messages` 在循环中被**可变地**追加（prompt、partial assistant、toolResult 都 push 进去）。`runLoop` 还维护一个 `newMessages: AgentMessage[]`——本次调用新增的消息，作为 `agent_end` 的 payload 返回。

### 4.2 runLoop 算法（双层 while）

```text
function runLoop(initialContext, newMessages, config, signal, emit, streamFn):
    currentContext = initialContext
    firstTurn = true
    pendingMessages = await config.getSteeringMessages?()     // 启动时先捞一次（用户等待时可能已输入）

    while true:                                                # ── 外层：followUp 驱动
        hasMoreToolCalls = true

        while hasMoreToolCalls OR pendingMessages非空:         # ── 内层：工具 + steering 驱动
            if not firstTurn: emit(turn_start)
            else: firstTurn = false

            # 1) 注入 pending steering 消息（首批 prompt 由外层 runAgentLoop 在 runLoop 之前已注入 context，不走此队列）
            for msg in pendingMessages:
                emit(message_start, msg); emit(message_end, msg)
                currentContext.messages.push(msg)
                newMessages.push(msg)
            pendingMessages = []

            # 2) 流式拿一个 assistant 回合
            message = streamAssistantResponse(currentContext, config, signal, emit, streamFn)
            newMessages.push(message)

            # 3) 错误/中止 → 直接收尾
            if message.stopReason in ("error","aborted"):
                emit(turn_end, message, toolResults=[])
                emit(agent_end, newMessages)
                return

            # 4) 执行工具
            toolCalls = message.content.filter(type=="toolCall")
            toolResults = []
            hasMoreToolCalls = false
            if toolCalls非空:
                if message.stopReason == "length":            # 输出被截断 → 参数可能残缺，全部判错
                    batch = failToolCallsFromTruncatedMessage(toolCalls, emit)
                else:
                    batch = executeToolCalls(currentContext, message, config, signal, emit)
                toolResults = batch.messages
                hasMoreToolCalls = not batch.terminate        # 整批都 terminate 才停
                for r in toolResults:
                    currentContext.messages.push(r); newMessages.push(r)

            emit(turn_end, message, toolResults)

            # 5) 下一回合配置覆盖（可换 model / context / thinking）
            upd = await config.prepareNextTurn?({message, toolResults, context, newMessages})
            if upd: currentContext = upd.context ?? currentContext
                     config.model = upd.model ?? config.model
                     config.reasoning = upd.thinkingLevel ...

            # 6) 优雅停止判定
            if await config.shouldStopAfterTurn?({...}):
                emit(agent_end, newMessages); return

            # 7) 再捞一次 steering（用户可能在回合进行中插话）
            pendingMessages = await config.getSteeringMessages?()

        # 内层结束 = agent 本来要停了
        followUps = await config.getFollowUpMessages?()
        if followUps非空:
            pendingMessages = followUps
            continue                                            # 回到外层 while，继续跑
        break                                                   # 真的没事干了

    emit(agent_end, newMessages)
```

**循环终止条件**（任一即停）：
- assistant `stopReason` 为 `error` / `aborted`；
- 整批工具结果都置 `terminate: true`（`hasMoreToolCalls = false` 且无 pending）；
- `shouldStopAfterTurn` 返回 `true`；
- 内层结束后 `getFollowUpMessages` 为空。

> **step 5 细节**：`prepareNextTurn` 只能覆盖 `context` / `model` / `reasoning`（thinking）三项，其中 `thinkingLevel: "off"` 会被映射成 `reasoning: undefined`（即不开启推理）；`convertToLlm`、`transformContext`、`toolExecution`、各 hook 均**不能**按回合切换。

### 4.3 streamAssistantResponse（模型调用边界）

这是 **AgentMessage → Message 的唯一转换点**：

```text
function streamAssistantResponse(context, config, signal, emit, streamFn):
    messages = context.messages
    if config.transformContext:                                 # ① AgentMessage → AgentMessage（裁剪/注入）
        messages = await config.transformContext(messages, signal)
    llmMessages = await config.convertToLlm(messages)          # ② AgentMessage → Message（过滤UI消息/转自定义）
    llmContext = { systemPrompt, messages: llmMessages, tools }

    apiKey = (await config.getApiKey?(model.provider)) ?? config.apiKey   # 动态 key（短时 OAuth）
    response = await streamFn(model, llmContext, {...config, apiKey, signal})

    partial = null; addedPartial = false
    for event in response:
        switch event.type:
          case "start":
            partial = event.partial
            context.messages.push(partial)                     # partial 进 transcript
            addedPartial = true
            emit(message_start, {...partial})
          case text_*/thinking_*/toolcall_*:
            partial = event.partial
            context.messages[last] = partial                   # 原地替换为最新累计
            emit(message_update, {assistantMessageEvent: event, message: {...partial}})
          case "done" | "error":
            final = await response.result()
            if addedPartial: context.messages[last] = final
            else: context.messages.push(final); emit(message_start, final)
            emit(message_end, final)
            return final
    # 兜底（流未发 done/error）
    final = await response.result()
    ... push/replace, emit message_start(若无)/message_end
    return final
```

**注意三点**：
1. `transformContext` 改的是**拷贝**（`messages` 局部变量），不会改 transcript；但 `convertToLlm` 的输出也只用于本次请求。**真正写回 transcript 的是 partial/final assistant 消息**。
2. partial 在流式过程中就已 push 进 `context.messages`，所以即便用户在生成中途 abort，transcript 里也保留半截消息（最终被 `done/error` 的 final 替换）。
3. `getApiKey` 每次请求都调一次——为了应对长工具执行阶段里短时 token 过期。

---

## 5. 消息模型：AgentMessage vs LLM Message

```ts
type AgentMessage = Message | CustomAgentMessages[keyof CustomAgentMessages];
```

`Message`（来自 pi-ai）是模型能理解的：`user` / `assistant` / `toolResult`。

`CustomAgentMessages` 默认空接口，应用通过 **declaration merging** 扩展：

```ts
declare module "@earendil-works/pi-agent-core" {
  interface CustomAgentMessages {
    notification: NotificationMessage;   // UI-only
    artifact: ArtifactMessage;
  }
}
```

### 两个转换函数

| 函数 | 时机 | 输入 → 输出 | 用途 |
|---|---|---|---|
| `transformContext` | 每次请求前，**可选** | `AgentMessage[] → AgentMessage[]` | 裁剪老消息（上下文管理）、注入外部上下文 |
| `convertToLlm` | 每次请求前，**必需** | `AgentMessage[] → Message[]` | 过滤 UI-only 消息、把自定义类型转成 user/assistant/toolResult |

默认 `convertToLlm` 只保留 `user`/`assistant`/`toolResult`：

```ts
function defaultConvertToLlm(messages) {
  return messages.filter(m => m.role === "user" || m.role === "assistant" || m.role === "toolResult");
}
```

**数据流速记**：

```
AgentMessage[] ──transformContext──▶ AgentMessage[] ──convertToLlm──▶ Message[] ──▶ LLM
```

---

## 6. 工具系统

### 6.1 工具定义

```ts
interface AgentTool<TParams, TDetails> extends Tool<TParams> {
  name: string;
  label: string;                       // UI 显示
  description: string;
  parameters: TParams;                 // typebox schema，用于校验
  prepareArguments?(args): TParams;    // 校验前的兼容垫片（修模型给出的脏参数）
  executionMode?: "sequential" | "parallel";  // 单工具级覆盖
  execute(
    toolCallId: string,
    params: TParams,
    signal?: AbortSignal,
    onUpdate?: (partialResult: AgentToolResult<TDetails>) => void,  // 流式进度
  ): Promise<AgentToolResult<TDetails>>;
}

interface AgentToolResult<T> {
  content: (TextContent | ImageContent)[];  // 回给模型的内容
  details: T;                                // 给日志/UI 的结构化数据（不进模型）
  usage?: Usage;                             // 工具自身的用量（不计入主上下文）
  addedToolNames?: string[];                 // 动态新增工具，从此点起可用
  terminate?: boolean;                       // 批次早停提示
}
```

**约定**：`execute` 用 throw 表达失败，循环会捕获并转成 `isError=true` 的 tool result（内容为错误文案）。不要自己把错误塞进 `content`。

### 6.2 工具执行流水线（单次工具调用）

```
emit(tool_execution_start)
   │
   ▼ prepareToolCall
   ├─ 找不到工具          → immediate error result
   ├─ prepareArguments    → validateToolArguments（schema 校验失败 → immediate error）
   ├─ beforeToolCall 钩子  → block? → immediate error（可带 terminate）
   ├─ abort?              → immediate error
   └─ 通过                → "prepared"
   │
   ▼ executePreparedToolCall
   └─ tool.execute(...)     onUpdate → emit(tool_execution_update)（排队，return 前 flush）
       throw → error result
   │
   ▼ finalizeExecutedToolCall
   ├─ afterToolCall 钩子   → 逐字段覆盖 content/details/usage/isError/terminate（无深合并）
   └─ 钩子 throw          → error result
   │
   ▼ emit(tool_execution_end, {result, isError})
   ▼ 组装 ToolResultMessage → emit(message_start/message_end)
```

### 6.3 批次执行模式选择

```text
hasSequential = 批次中任一 toolCall 对应工具声明 executionMode === "sequential"
if config.toolExecution === "sequential" OR hasSequential:
    executeToolCallsSequential   # 一个一个来
else:
    executeToolCallsParallel     # 串行 preflight + 并发执行
```

**并行模式的关键顺序解耦**（容易踩坑）：

```
preflight（串行）：对每个 toolCall 做 prepare；
       immediate（找不到工具/校验失败/blocked/aborted）→ 当场 emit(tool_execution_end)，按【源顺序】先发完；
       prepared → 塞进一个 thunk 数组（保持源顺序）
执行：Promise.all(thunks)            # 并发；每个 thunk 内部 execute→finalize→emit(tool_execution_end)
       → 这部分 tool_execution_end 按【完成顺序】触发，且全部排在 immediate 的 end 之后
收尾（串行）：按 thunk 数组的【源顺序】遍历，组装 ToolResultMessage 并 emit(message_start/end)
```

所以：**immediate 工具的 `tool_execution_end` 按【源序】最先发；被并发执行的工具的 `tool_execution_end` 按【完成序】发；`turn_end.toolResults` 与 transcript 里的 toolResult 消息恒按【源序】发**。UI 如果按事件顺序显示工具结果，可能和喂给模型的顺序不同——这是有意为之，让用户尽快看到先完成的工具。

### 6.4 批次早停语义

```ts
shouldTerminateToolBatch(calls) = calls.length > 0 && calls.every(c => c.result.terminate === true);
```

**必须整批每一个都 `terminate=true` 才停**。这是为了防止并行批次里个别工具想停、但其他工具的结果模型还需要时被误杀。`beforeToolCall` 返回 `{block:true, terminate:true}` 或 `afterToolCall` 返回 `{terminate:true}` 都参与这个判定。

### 6.5 截断保护

当 `message.stopReason === "length"`（输出超 token 上限被截断），**不执行任何工具**，而是 `failToolCallsFromTruncatedMessage`：因为流式 JSON 会被"尽力抢救"解析，参数可能校验通过但实际残缺，执行它们有风险。每个工具调用返回错误结果，提示模型重新发起。

---

## 7. 状态管理与生命周期（Agent 类）

`src/agent.ts` 的 `Agent` 是 `runLoop` 的**有状态包装器**，持有：

```ts
interface AgentState {
  systemPrompt: string;
  model: Model;
  thinkingLevel: ThinkingLevel;        // "off"|"minimal"|"low"|"medium"|"high"|"xhigh"|"max"
  tools: AgentTool[];                  // setter 会拷贝顶层数组（防御性）
  messages: AgentMessage[];            // setter 同上
  readonly isStreaming: boolean;
  readonly streamingMessage?: AgentMessage;
  readonly pendingToolCalls: ReadonlySet<string>;
  readonly errorMessage?: string;
}
```

### 事件类型（AgentEvent）

```
agent_start | agent_end{messages}
turn_start  | turn_end{message, toolResults}
message_start{message} | message_update{message, assistantMessageEvent} | message_end{message}
tool_execution_start{id,name,args} | tool_execution_update{id,name,args,partialResult}
tool_execution_end{id,name,result,isError}
```

`agent_end` 是一次运行的最后一个事件，但 `Agent` 直到所有 `agent_end` 的 **awaited 监听器**都 settle 才算 idle（`waitForIdle()` 才 resolve）。

### 运行生命周期（runWithLifecycle）

```text
new AbortController + Promise（idle 信号）
state.isStreaming = true; streamingMessage = undefined; errorMessage = undefined
try:
    await executor(signal)              # 实际调 runAgentLoop / runAgentLoopContinue
catch error:                            # 循环真抛了（罕见，违反契约时）
    handleRunFailure: 合成一个 stopReason=error/aborted 的 assistant 消息
                      emit message_start/end → turn_end → agent_end
finally:
    finishRun: isStreaming=false; 清 streamingMessage/pendingToolCalls; resolve idle promise; activeRun=undefined
```

### 事件处理（processEvents）

每收到一个事件：① 先 reduce 内部 state（如 `message_end` 时 push 到 messages、`tool_execution_*` 时增删 pendingToolCalls 集合），② 再按订阅顺序 **逐个 await** 所有 listener。listener 收到当前 run 的 abort signal。

### 队列 API

| 方法 | 何时注入 | 排水点 |
|---|---|---|
| `steer(msg)` | 当前 assistant 回合结束后、下回合前 | 内层循环开头 + `getSteeringMessages` 轮询 |
| `followUp(msg)` | agent 本要停下时 | 内层结束后的 `getFollowUpMessages` |
| `clearAllQueues()` / `hasQueuedMessages()` | — | — |

`QueueMode`：`"all"`（一次排空全部）或 `"one-at-a-time"`（只取最老一条，默认）。

### 中断

- `abort()` → 触发 `activeRun.abortController.abort()`，signal 传给 streamFn 和所有工具。
- `waitForIdle()` → 返回 `activeRun.promise`，在 `agent_end` 监听器 settle 后 resolve。
- `reset()` → 清 transcript/运行态/队列；运行中调用会抛错。
- `prompt()` / `continue()` 在 `activeRun` 存在时直接抛错（用 steer/followUp 替代）。

---

## 8. 传输抽象：StreamFn 与 Proxy 模式

### 8.1 StreamFn 契约

```ts
type StreamFn = (
  model: Model,
  context: Context,
  options?: SimpleStreamOptions,
) => AssistantMessageEventStream | Promise<AssistantMessageEventStream>;
```

**契约（必须遵守）**：
- 不得 throw / reject；
- 失败必须编码进返回的 stream：通过 `error` 事件 + 一个 `stopReason: "error"|"aborted"`、带 `errorMessage` 的终态 `AssistantMessage`；
- `stream.result()` 在 `done`/`error` 后返回最终消息。

`EventStream<TEvent, TResult>` 的事件序列（`AssistantMessageEvent`）：
```
start → (text_start → text_delta* → text_end)*
      → (thinking_start → thinking_delta* → thinking_end)*
      → (toolcall_start → toolcall_delta* → toolcall_end)*
      → done | error
```
每个事件都带累计的 `partial: AssistantMessage`。

### 8.2 默认 streamFn 注入

`pi-agent-core` 故意**不依赖任何 provider 目录**。`setDefaultStreamFn(fn)` / `getDefaultStreamFn()` 提供全局兜底，宿主（如 pi-ai 的 `models.streamSimple`）在启动时注入。`Agent` 构造时未传 `streamFn` 就用兜底。

### 8.3 Proxy 模式（`src/proxy.ts`）

为"LLM 调用走服务端代理"的场景设计。服务端做鉴权 + 转发，并**剥掉 delta 事件里的 `partial` 字段以省带宽**；客户端用 `streamProxy` 在本地重建 partial。

协议（SSE over `fetch`，每行 `data: {json}`）：
```ts
type ProxyAssistantMessageEvent =
  | { type: "start" }
  | { type: "text_start"; contentIndex } | { type:"text_delta"; contentIndex; delta } | { type:"text_end"; contentIndex; contentSignature? }
  | { type: "thinking_start"; contentIndex } | { ..._delta } | { ..._end; contentSignature? }
  | { type: "toolcall_start"; contentIndex; id; toolName } | { type:"toolcall_delta"; contentIndex; delta } | { type:"toolcall_end"; contentIndex }
  | { type:"done"; reason; usage } | { type:"error"; reason; errorMessage?; usage };
```

`processProxyEvent` 用 `contentIndex` 定位 content 数组里的块，逐步累加文本/thinking，工具调用用 `parseStreamingJson` 边收边解析 `arguments`，最终 emit 等价于直连的 `AssistantMessageEvent`。abort 通过 `reader.cancel()` + signal 监听实现。

**复现启示**：只要实现一个返回上述 EventStream 的函数，agent 就能跑——不管是直连 OpenAI/Anthropic、走代理、还是接本地模型。

---

## 9. 上下文窗口管理 / Compaction

`src/harness/compaction/compaction.ts`。当 transcript 接近模型上下文窗口时，把老消息压成一条摘要。

### 9.1 token 估算

```ts
estimateTokens(msg) ≈ chars / 4        // 文本/工具调用/thinking 都按字符数 ÷ 4
                                         // 单张图片固定 ≈ 4800 字符 → 1200 token
```

`estimateContextTokens` 优先用**最近一条有效 assistant 消息的 provider usage**（`totalTokens || input+output+cacheRead+cacheWrite`），再加上其后所有消息的估算值。这样比纯字符估算准。

### 9.2 触发与切割

```ts
shouldCompact(contextTokens, contextWindow, settings) =
  settings.enabled && contextTokens > contextWindow - settings.reserveTokens;

DEFAULT: { enabled:true, reserveTokens:16384, keepRecentTokens:20000 }
```

`findCutPoint` / `findTurnStartIndex`：只在**合法切割点**切（turn 边界、特定 role 边界），避免把一个 assistant+toolResult 对拆散。`prepareCompaction` + `generateSummary` 把切掉的部分喂给模型生成 `compactionSummary` 消息，保留最近 `keepRecentTokens` 的原始消息。

### 9.3 与主循环的关系

Compaction **不是** `runLoop` 内置的——它通过 `transformContext` 钩子或 harness 层调用。复现时可以在 `transformContext` 里调用 `shouldCompact` + `compact`，把压缩后的 messages 返回，循环自然就用压缩后的上下文发下一回合。

---

## 10. 执行环境抽象：FileSystem / Shell / ExecutionEnv

`src/harness/types.ts`。为了让工具（read/write/edit/bash）能跑在不同后端（真实 Node fs、内存 mock、远程沙箱），定义了能力接口：

```ts
interface FileSystem {
  cwd: string;
  absolutePath / joinPath / readTextFile / readTextLines / readBinaryFile /
  writeFile / appendFile / renameFile / fileInfo / listDir / canonicalPath /
  exists / createDir / remove / createTempDir / createTempFile;
  cleanup(): Promise<void>;
}

interface Shell {
  exec(command, options?: { cwd?, env?, inheritEnv?, timeout?, abortSignal?, onStdout?, onStderr? })
    : Promise<Result<{stdout, stderr, exitCode}, ExecutionError>>;
  cleanup(): Promise<void>;
}

interface ExecutionEnv extends FileSystem, Shell {}
```

**铁律**：所有方法**绝不 throw/reject**，失败编码进返回的 `Result<T, FileError | ExecutionError>`（`{ ok: true, value } | { ok: false, error }`，即 `src/harness/result.ts` 的 `ok/err`）。这让工具代码可以用 `getOrThrow` 显式选择"失败即抛"或优雅处理。

`NodeExecutionEnv`（`src/harness/env/nodejs.ts`，通过 `node.ts` 子入口导出）是 Node.js 实现。复现时实现这两个接口即可复用全部内置工具。

---

## 11. 上层 Harness：Session / Lane / Hook 契约

`src/harness/agent-harness.ts` + `src/harness/session/*`。这是在 ①②③ 层之上的**完整产品级 orchestrator**，目前以**接口契约骨架**为主（多数方法返回 `HarnessNotImplemented`，描述目标 API）。

核心概念：

- **Session / SessionTree**：持久化的会话树（每个节点是一条 `Entry`：message / record / branchSummary / compactionSummary / provisioned 等）。支持 JSONL 存储后端（`session/jsonl/*`）和内存后端。
- **Lane（车道）**：一条独立的对话流，有 `prompt / skill / promptFromTemplate / compact / navigateTree / resume / abort / steer / followUp / nextRun` 等方法。`AgentHarness` 本身实现 `AgentLane`（名为 `main`），并可 `createLane` 派生子车道。
- **Run outcome**（`RunResult = Result<{runId} & RunOutcome, RunRejected>`）：`completed / aborted / failed / suspended`。`suspended` 配合 `DeferredHandle` 支持"运行中崩溃/挂起后 resume"。
- **TaggedError 模式**：`LaneBusy / InvalidMessage / UnknownSkill / Closed ...` 每类错误是独立的带 tag 的 class，便于 `result.ts` 的 `Result` 精确分派。
- **Hooks**（`before_run / before_resume / transform_context / before_request / before_payload / after_response / before_tool / after_tool / before_compaction / before_navigation`）：比第②层更细粒度的可观测/干预点。
- **telemetry**：`HARNESS_TELEMETRY_SCHEMA` / `AI_TELEMETRY_SCHEMA` 定义结构化 span（见 `docs/telemetry-schema.md`）。

**复现策略**：如果你只想要"能跑工具的 agent"，实现到第 ③ 层即可。第 ④ 层（持久会话树、多 lane、resume）只在需要"可中断/可恢复/可分支"的重量级产品时才值得复现。

---

## 12. 最小复现清单与骨架伪代码

要在他处复现一个等价运行时，按此清单实现（语言无关）。

### 必须实现
1. **AgentMessage 模型** + `convertToLlm`（默认过滤非 user/assistant/toolResult）。
2. **StreamFn 抽象** + 一个具体实现（直连或代理）。契约：不抛错，失败编码进 `error` 事件 + 终态 assistant 消息。
3. **runLoop**（§4.2 的双层 while）。
4. **streamAssistantResponse**（§4.3）：transformContext → convertToLlm → streamFn → 边流式边把 partial 写回 transcript。
5. **工具执行**（§6）：prepare（schema 校验 + beforeToolCall）→ execute（onUpdate 流式）→ finalize（afterToolCall 覆盖）；sequential / parallel 两模式 + 批次 terminate 语义 + length 截断保护。
6. **事件 sink** + §7 的事件类型。
7. **abort 语义**：AbortSignal 贯穿 streamFn 与每个工具。

### 推荐实现
8. **Agent 状态包装**：transcript 持有、单运行串行、steering/followUp 队列、listener 串行 await。
9. **transformContext 钩子**（挂 compaction）。
10. **Result<T,E> + 绝不抛**的执行环境接口（若要内置文件/命令工具）。

### 最小骨架（伪代码）

```ts
// 注意：runLoop 自身不发 agent_start，也不发 turn 0 的 turn_start，也不注入首批 prompt
// （这些都由外层包装 runAgentLoop 在调用前发出 / 注入；prompt 用例下 newMsgs 需以 prompts 预填）。
// 下方保留与源码一致的 firstTurn 守卫。
async function runLoop(ctx, newMsgs, config, emit, signal, streamFn) {
  let pending = (await config.getSteeringMessages?.()) ?? [];
  let firstTurn = true;
  outer: while (true) {
    let more = true;
    while (more || pending.length) {
      if (!firstTurn) emit({ type: "turn_start" });
      else firstTurn = false;
      for (const m of pending) { emit({type:"message_start",message:m}); emit({type:"message_end",message:m}); ctx.messages.push(m); newMsgs.push(m); }
      pending = [];
      const msg = await streamAssistantResponse(ctx, config, signal, emit, streamFn); // 会 push 进 ctx.messages
      newMsgs.push(msg);
      if (msg.stopReason === "error" || msg.stopReason === "aborted") {
        emit({type:"turn_end", message:msg, toolResults:[]});
        emit({type:"agent_end", messages:newMsgs}); return newMsgs;
      }
      const calls = msg.content.filter(c => c.type === "toolCall");
      let toolResults = []; more = false;
      if (calls.length) {
        const batch = msg.stopReason === "length"
          ? await failTruncated(calls, emit)
          : await executeToolCalls(ctx, msg, calls, config, signal, emit);
        toolResults = batch.messages; more = !batch.terminate;
        for (const r of toolResults) { ctx.messages.push(r); newMsgs.push(r); }
      }
      emit({type:"turn_end", message:msg, toolResults});
      const upd = await config.prepareNextTurn?.({message:msg, toolResults, context:ctx, newMessages:newMsgs});
      if (upd) { if (upd.context) ctx = upd.context; if (upd.model) config.model = upd.model; }
      if (await config.shouldStopAfterTurn?.({message:msg, toolResults,context:ctx,newMessages:newMsgs})) {
        emit({type:"agent_end", messages:newMsgs}); return newMsgs;
      }
      pending = (await config.getSteeringMessages?.()) ?? [];
    }
    const fu = (await config.getFollowUpMessages?.()) ?? [];
    if (fu.length) { pending = fu; continue outer; }
    break;
  }
  emit({type:"agent_end", messages:newMsgs});
  return newMsgs;
}
```

工具执行骨架（并行模式）：

```ts
async function executeToolCalls(ctx, asstMsg, calls, config, signal, emit) {
  const finalized = [];
  const thunks = [];
  for (const tc of calls) {
    emit({type:"tool_execution_start", toolCallId:tc.id, toolName:tc.name, args:tc.arguments});
    const pre = await prepareToolCall(ctx, asstMsg, tc, config, signal);  // 校验 + beforeToolCall
    if (pre.kind === "immediate") {
      const f = {toolCall:tc, result:pre.result, isError:pre.isError};
      await emit({type:"tool_execution_end", ...}); finalized.push(f);    // 立即 end
      continue;
    }
    thunks.push(async () => {                                            // 推迟执行
      const ex = await executePrepared(pre, signal, emit);               // onUpdate → tool_execution_update
      const f = await finalize(ctx, asstMsg, pre, ex, config, signal);   // afterToolCall 覆盖
      await emit({type:"tool_execution_end", ...});
      return f;
    });
  }
  const done = await Promise.all(thunks.map(t => t()));                  // 并发；end 按完成序
  const messages = [];
  for (const f of done) {                                                // 源序组装结果消息
    const m = toToolResultMessage(f);
    emit({type:"message_start",message:m}); emit({type:"message_end",message:m});
    messages.push(m);
  }
  return { messages, terminate: done.length>0 && done.every(f => f.result.terminate === true) };
}
```

---

## 13. 关键不变量与陷阱

复现时容易踩的坑，全部来自源码的真实行为：

1. **partial 必须写回 transcript。** 不是只 emit 出去就完事——`context.messages` 要在 `start` 时 push partial、每个 delta 时替换末尾、`done/error` 时替换为 final。否则下一轮 `transformContext` 看不到正在生成的消息，重试/续跑会丢消息。

2. **StreamFn 绝不抛。** 一旦抛错，`runLoop` 不发 `agent_end`，UI 会卡死。`Agent.runWithLifecycle` 的 `catch` 是最后兜底（合成错误 assistant 消息 + 完整事件序列），但 `runAgentLoop` 的直接调用方没有这层保护。

3. **截断（stopReason==="length"）必须跳过执行。** 流式 JSON 抢救解析会让残缺参数通过校验，直接执行等于喂给系统半截参数。

4. **批次 terminate 是"全部同意"才停。** 并行批次里只要有一个工具 `terminate !== true`，就继续。别写成"任一为 true 就停"。

5. **并行模式有两个独立顺序。** `toolResult` 消息（进 transcript 与 `turn_end.toolResults`）恒按模型源序；`tool_execution_end` 里，immediate 结果按源序先发、并发执行的工具按完成序后发。复现时若把 toolResult 也按完成序写回 transcript，会和模型预期不符。

6. **`convertToLlm` 与 `transformContext` 都不能抛。** 契约要求返回安全兜底值；抛错会中断底层循环且不发正常事件序列。

7. **运行时单例。** 同一 Agent 同时只允许一个 `activeRun`。第二个 `prompt` 直接抛错，别想着排队——排队是 steer/followUp 的职责。

8. **`agent_end` 之后才算 idle。** `waitForIdle()` 要等所有 `agent_end` 的 awaited listener settle。listener 里别再同步触发新的 prompt（会抛"already processing"）。

9. **`getApiKey` 每请求一调。** 别缓存——长工具执行阶段短时 OAuth token 会过期。

10. **执行环境方法永不抛。** 全部返回 `Result`。工具里用 `getOrThrow` 显式转抛，由循环捕获转 `isError`。

---

## 附：源码索引（按本文章节）

| 章节 | 关键文件 | 关键符号 |
|---|---|---|
| §4 循环 | `src/agent-loop.ts` | `runAgentLoop` / `runAgentLoopContinue` / `runLoop` / `streamAssistantResponse` |
| §5 消息 | `src/types.ts` | `AgentMessage` / `AgentLoopConfig.convertToLlm` / `transformContext` |
| §6 工具 | `src/agent-loop.ts`, `src/harness/tools/*` | `executeToolCalls` / `prepareToolCall` / `executeToolCallsParallel` / `AgentTool` |
| §7 状态 | `src/agent.ts` | `Agent` / `runWithLifecycle` / `processEvents` / `PendingMessageQueue` |
| §8 传输 | `src/stream-fn.ts`, `src/proxy.ts` | `StreamFn` / `streamProxy` / `setDefaultStreamFn` |
| §9 压缩 | `src/harness/compaction/compaction.ts` | `shouldCompact` / `estimateContextTokens` / `findCutPoint` / `compact` |
| §10 环境 | `src/harness/types.ts`, `src/harness/env/nodejs.ts` | `FileSystem` / `Shell` / `ExecutionEnv` / `NodeExecutionEnv` |
| §11 Harness | `src/harness/agent-harness.ts`, `src/harness/session/*` | `AgentHarness` / `AgentLane` / `Session` / `RunResult` |
| 事件 | `src/types.ts` | `AgentEvent` |
| 错误模型 | `src/harness/result.ts` | `Result` / `ok` / `err` / `TaggedError` |

---

*本文基于 `@earendil-works/pi-agent-core` v0.84.1 的源码分析。所有算法描述与代码片段均可与 `src/` 下的实现一一对照。*
