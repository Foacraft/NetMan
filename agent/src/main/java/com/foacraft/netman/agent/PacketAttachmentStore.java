package com.foacraft.netman.agent;

import java.util.Collections;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * Central store mapping live packet objects → their construction-time stack traces.
 *
 * Uses a synchronized WeakHashMap so entries are automatically removed when the packet
 * object is garbage collected (no memory leak from dropped/unprocessed packets).
 *
 * Called from bytecode injected into every instrumented packet constructor.
 * Queried by the plugin's PacketSourceHandler in the Netty pipeline.
 *
 * Because the agent is loaded by the app classloader (via -javaagent) and Paper's
 * PluginClassLoader delegates to the app classloader, plugin code can reference this
 * class directly at compile time (compileOnly(project(":agent"))) and at runtime both
 * point to the same loaded class.
 */
public class PacketAttachmentStore {

    private static final Map<Object, StackTraceElement[]> STORE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Called from the injected constructor hook. Captures current thread's stack.
     * The first few frames (getStackTrace, this method, the constructor itself) are
     * stripped in the plugin's resolver when walking.
     */
    public static void capture(Object packet) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        STORE.put(packet, stack);
    }

    /**
     * Retrieve the captured stack for the given packet, or null if not instrumented.
     */
    public static StackTraceElement[] get(Object packet) {
        return STORE.get(packet);
    }

    /**
     * Explicitly remove after attribution to free memory eagerly.
     */
    public static void remove(Object packet) {
        STORE.remove(packet);
    }
}
