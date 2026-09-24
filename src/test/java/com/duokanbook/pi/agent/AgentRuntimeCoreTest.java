package com.duokanbook.pi.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Core runtime acceptance placeholders.
 *
 * The runtime is validated around the Think -> Action -> Observation contract:
 * tool registration, tool call correlation, loop protection and cancellation.
 */
public class AgentRuntimeCoreTest {

    @Test
    public void loopPolicyShouldProtectRuntime() {
        LoopPolicy policy = new LoopPolicy();
        assertEquals(20, policy.maxTurns());
    }

    @Test
    public void contextPolicyShouldHaveSafeDefaults() {
        ContextPolicy policy = new ContextPolicy();
        assertEquals(500, policy.maxMessages());
        assertEquals(20000, policy.maxToolResultChars());
    }
}
