package com.foacraft.netman.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point for NetMan.
 *
 * Loaded via: java -javaagent:netman-agent.jar -jar paper.jar
 *
 * The agent instruments all NMS packet class constructors to capture the call-site
 * stack trace at construction time. This stack is later retrieved by the plugin's
 * PacketSourceHandler to attribute each outbound packet to its originating plugin.
 *
 * This solves the fundamental attribution problem: by the time a Netty handler fires
 * (on the IO thread), the plugin's stack frame is already gone. Capturing at construction
 * time (which occurs on the game thread where the plugin code runs) preserves the stack.
 */
public class NetManAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new PacketClassTransformer(), true);
    }
}
