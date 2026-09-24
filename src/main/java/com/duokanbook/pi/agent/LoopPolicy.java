package com.duokanbook.pi.agent;

/** Runtime limits preventing runaway agent execution. */
public class LoopPolicy {

    private final int maxTurns;

    public LoopPolicy() {
        this(20);
    }

    public LoopPolicy(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int maxTurns() {
        return maxTurns;
    }
}
