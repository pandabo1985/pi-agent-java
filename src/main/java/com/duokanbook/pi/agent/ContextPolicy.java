package com.duokanbook.pi.agent;

/** Basic context growth protection for the first runtime version. */
public class ContextPolicy {

    private final int maxMessages;
    private final int maxToolResultChars;

    public ContextPolicy() {
        this(500, 20000);
    }

    public ContextPolicy(int maxMessages, int maxToolResultChars) {
        this.maxMessages = maxMessages;
        this.maxToolResultChars = maxToolResultChars;
    }

    public int maxMessages() {
        return maxMessages;
    }

    public int maxToolResultChars() {
        return maxToolResultChars;
    }
}
