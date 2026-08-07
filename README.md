# pi-agent-java

A standalone, dependency-free **Java 17** port of the **core runtime** of the TypeScript package
`@earendil-works/pi-agent-core` — the general-purpose LLM agent with transport abstraction,
state management, and tool execution.

This is a faithful reimplementation of the original package's layers ①–③ (the agent loop, the
stateful `Agent`, and the tool/stream abstraction). It is the executable counterpart of the design
document [`docs/agent-core-internals.md`](docs/agent-core-internals.md) — read that for the
underlying principles; this README covers the Java specifics.

## Scope

**Translated (runs end-to-end):**
- The low-level agent loop (`AgentLoop`) — the double-`while` kernel, streaming one assistant
  response, sequential/parallel tool execution, before/after hooks, batch `terminate` semantics,
  truncation (`length`) protection, steering/follow-up queues.
- The stateful `Agent` wrapper — transcript ownership, lifecycle events, queues, abort,
  `waitForIdle`, synthesized error messages on failure.
- The full type model — `AgentMessage`, `Content`, `AgentEvent`, `AssistantMessageEvent`,
  `Usage`, `Model`, `StopReason`, etc. (Java `sealed` interfaces + `record`s).
- The stream abstraction — `StreamFn`, `EventStream`, `AbortSignal`, `DefaultStreamFn`.
- `ProxyStreamFn` — SSE proxy client (bandwidth-optimized events → reconstructed partial),
  using `java.net.http.HttpClient`.
- `Json` — a dependency-free JSON parser/serializer + streaming-JSON salvage.
- A demo (`Main`) exercising the loop with an in-memory fake stream function and a sample tool.

**Not translated (documented in the principles doc, intentionally out of scope):**
- The harness layer — `AgentHarness`, `Session`/`SessionTree`, lanes, hooks, resume
  (principles doc §11).
- Filesystem/shell tools (`read`/`write`/`edit`/`bash`) and the `ExecutionEnv` abstraction (§10).
- Context-window compaction (§9) and branch summarization.
- Telemetry spans.

These are additive on top of the core and are environment-specific; the core here is the
reusable heart you can build either set of features against.

## Build & run

Requires JDK 17+ and Maven 3.6+.

```bash
cd pi-agent-java
mvn compile                 # compile
mvn exec:java               # run the demo (in-memory fake model + echo tool)
```

Expected demo output (abridged):

```
### prompt: "echo hello"
  > message_start user: echo hello
  > message_end   user: echo hello
  > message_start assistant:
  > message_end   assistant: toolCall=echo({text=echo hello})
  > tool_execution_start echo({text=echo hello})
  > tool_execution_end   echo -> ECHO: ECHO HELLO
  > message_start toolResult(echo): ECHO: ECHO HELLO
  > message_end   toolResult(echo): ECHO: ECHO HELLO
== turn_end ==
  > message_start assistant:
  > message_end   assistant: text="Tool replied: ECHO: ECHO HELLO"
== turn_end ==
== agent_end (4 new messages) ==
```

## Quick start

```java
import com.duokanbook.pi.agent.*;
import java.util.List;

Model model = new Model("claude-sonnet-5", "Claude Sonnet 5", "anthropic", "anthropic",
        "https://api.anthropic.com", true, 200_000, 64_000, new Usage.Cost(0,0,0,0,0));

Agent.AgentOptions opts = new Agent.AgentOptions();
opts.model = model;
opts.systemPrompt = "You are a helpful assistant.";
opts.tools = List.of(new MyTool());
opts.streamFn = new ProxyStreamFn("https://your-proxy.example.com", () -> authToken); // or your own StreamFn
Agent agent = new Agent(opts);

agent.subscribe((event, signal) -> {
    if (event instanceof AgentEvent.MessageUpdate u
            && u.assistantMessageEvent() instanceof AssistantMessageEvent.TextDelta d) {
        System.out.print(d.delta());          // stream text deltas
    }
});

agent.prompt("Hello!").join();   // blocks until the run settles
```

A `StreamFn` is the only provider-specific piece. Implement one against any provider
(OpenAI, Anthropic, a local server, …) returning an `EventStream<AssistantMessageEvent,
AgentMessage.AssistantMessage>`. The `StreamFn` contract: **never throw** — encode failures as a
terminal `AssistantMessageEvent.ErrorEvent` whose message has `stopReason = ABORTED|ERROR`.

## TypeScript → Java mapping

| TypeScript (`packages/agent/src`)            | Java (`com.duokanbook.pi.agent`)                   |
|----------------------------------------------|----------------------------------------------------|
| `agent-loop.ts` `runLoop`/`streamAssistantResponse` | `AgentLoop`                                  |
| `agent.ts` `Agent`                           | `Agent`                                            |
| `types.ts` `AgentMessage`/`AgentEvent`/`AgentLoopConfig` | `AgentMessage`/`AgentEvent`/`AgentLoopConfig` (sealed + records) |
| `types.ts` `StreamFn`                        | `StreamFn`                                         |
| `stream-fn.ts`                               | `DefaultStreamFn`                                  |
| `proxy.ts` `streamProxy`                     | `ProxyStreamFn`                                    |
| `pi-ai` `EventStream` / `AbortSignal`        | `EventStream` / `AbortSignal`                      |
| `pi-ai` `AssistantMessageEvent`              | `AssistantMessageEvent`                            |

## Design notes (deliberate choices for the Java port)

- **Java 17 LTS, zero external dependencies.** `sealed` interfaces + `record`s model the
  message/event unions with exhaustive `instanceof`/discriminator switching. Concurrency uses
  `ExecutorService` + `CompletableFuture` (no virtual threads); upgrading to Java 21 virtual
  threads is a mechanical change in `Agent`/`AgentLoop`.
- **Async = background thread + `CompletableFuture`.** TS `async/await` maps to a run thread that
  `.join()`s hook futures. `Agent.prompt` returns a `CompletableFuture<Void>` resolving after
  `agent_end` listeners settle.
- **Event processing is synchronized** in `Agent.processEvents`, so the concurrent
  `tool_execution_end` emissions of a parallel tool batch serialize (mirroring JS's
  single-threaded event ordering).
- **`AgentMessage` collapses the TS `Message | AgentMessage` split** into one sealed hierarchy
  (`User/Assistant/ToolResult/Custom`). `convertToLlm` filters out `Custom`. To add a fully typed
  custom message, add a new permitted record to `AgentMessage` (the only edit required).
- **Immutable snapshots per stream event.** Each `AssistantMessageEvent` carries a fresh
  `AgentMessage.AssistantMessage` snapshot (records are immutable), so the proxy and direct
  producers never mutate shared state.
- **`Json` is hand-rolled** (~250 lines) so the core stays provider- and library-free, matching
  the TS package's stance of not depending on a provider catalog.

## Project layout

```
pi-agent-java/
├── pom.xml
├── LICENSE
├── docs/agent-core-internals.md  # underlying-principles design doc
└── src/main/java/com/duokanbook/pi/agent/
    ├── Agent.java                 # stateful wrapper (agent.ts)
    ├── AgentLoop.java             # the loop + tool execution (agent-loop.ts)
    ├── AgentLoopConfig.java       # config + all hook contracts + context/result records
    ├── AgentMessage.java          # sealed: User/Assistant/ToolResult/Custom
    ├── AgentEvent.java            # sealed: lifecycle event union
    ├── AssistantMessageEvent.java # sealed: stream-protocol event union
    ├── Content.java               # sealed: Text/Image/Thinking/ToolCall
    ├── EventStream.java           # async event stream + terminal result
    ├── AbortSignal.java           # cooperative cancellation
    ├── StreamFn.java              # the stream-function contract
    ├── ProxyStreamFn.java         # SSE proxy client
    ├── MessageJson.java           # message → JSON-able values
    ├── Json.java                  # JSON parse/stringify/streaming salvage
    ├── Model.java, Usage.java, StopReason.java, ThinkingLevel.java, ToolExecutionMode.java, QueueMode.java
    ├── AgentContext.java, LlmContext.java, SimpleStreamOptions.java
    ├── AgentTool.java, AgentToolResult.java, DefaultStreamFn.java
    └── Main.java                  # runnable demo
```

## License

MIT, matching the upstream package.
