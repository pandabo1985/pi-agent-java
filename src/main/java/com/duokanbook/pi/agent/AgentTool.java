package com.duokanbook.pi.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Tool definition used by the agent runtime. Mirrors {@code AgentTool} in {@code types.ts}.
 *
 * <p>{@code execute} should throw (complete its future exceptionally) on failure rather than
 * encoding the error in {@code content}; the loop converts a thrown failure into an
 * {@code isError=true} tool result. Use {@code onUpdate} to stream partial execution updates;
 * calls after the future settles are ignored.
 */
public interface AgentTool {

	String name();

	String label();

	String description();

	/** JSON schema describing the parameters, as a {@code Map<String,Object>} (a JSON object). */
	Map<String, Object> parameters();

	/** Per-tool execution-mode override; {@code null} means use the loop default. */
	default ToolExecutionMode executionMode() {
		return null;
	}

	/** Compatibility shim applied to raw arguments before schema validation. Default: passthrough. */
	default Object prepareArguments(Object args) {
		return args;
	}

	/**
	 * Execute the tool call.
	 *
	 * @param toolCallId the id of the requesting {@code Content.ToolCall}
	 * @param args       validated arguments (a {@code Map<String,Object>})
	 * @param signal     abort signal; long-running tools should honor it
	 * @param onUpdate   callback for partial results (streaming progress to the UI)
	 */
	CompletableFuture<AgentToolResult<?>> execute(
			String toolCallId, Object args, AbortSignal signal, Consumer<AgentToolResult<?>> onUpdate);
}
