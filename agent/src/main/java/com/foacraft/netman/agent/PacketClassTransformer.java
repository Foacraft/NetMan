package com.foacraft.netman.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * ASM ClassFileTransformer that injects a call to {@link PacketAttachmentStore#capture(Object)}
 * at the end of every constructor in identified NMS packet classes.
 *
 * Injection strategy:
 *   - At each RETURN opcode in a constructor (<init>), insert ALOAD_0 + INVOKESTATIC capture.
 *   - Uses AdviceAdapter for safe insertion (handles try-catch and multiple returns).
 *
 * Target class detection:
 *   - Package prefix: net/minecraft/network/  (Mojang-mapped Paper 1.18+)
 *   - Class name contains PacketPlay           (CraftBukkit/Spigot remapped names on 1.20.1)
 *   This intentionally over-instruments (some non-packet classes may be hit) because the cost
 *   is low (store.put on objects we never query), and under-instrumentation means missed attribution.
 */
public class PacketClassTransformer implements ClassFileTransformer {

    private static final String STORE_OWNER =
            "com/foacraft/netman/agent/PacketAttachmentStore";
    private static final String CAPTURE_DESC = "(Ljava/lang/Object;)V";

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (className == null || !isPacketClass(className)) return null;

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr);
            cr.accept(new PacketClassVisitor(cw, className), ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Throwable t) {
            // Never crash the JVM — silently skip on any ASM error
            return null;
        }
    }

    private boolean isPacketClass(String className) {
        return className.startsWith("net/minecraft/network/")
                || className.contains("PacketPlay");
    }

    /**
     * ClassWriter with COMPUTE_FRAMES that never triggers class loading.
     * Returns "java/lang/Object" for all type merges — safe because our
     * injection is trivial (ALOAD_0 + INVOKESTATIC at method exit) and
     * doesn't introduce new branch merges that need precise types.
     * This avoids classloader deadlocks that occur when Class.forName is
     * called inside a ClassFileTransformer (which holds the classloader lock).
     */
    private static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader cr) {
            super(cr, ClassWriter.COMPUTE_FRAMES);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    // ── Class visitor ──────────────────────────────────────────────────────────

    private static class PacketClassVisitor extends ClassVisitor {
        private final String className;

        PacketClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<init>".equals(name)) {
                return new ConstructorInjector(mv, access, name, descriptor, className);
            }
            return mv;
        }
    }

    // ── Method visitor ─────────────────────────────────────────────────────────

    private static class ConstructorInjector extends AdviceAdapter {
        private final String owner;

        ConstructorInjector(MethodVisitor mv, int access, String name, String desc, String owner) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.owner = owner;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                // Push `this` (arg 0) and call PacketAttachmentStore.capture(Object)
                loadThis();
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        STORE_OWNER,
                        "capture",
                        CAPTURE_DESC,
                        false);
            }
        }
    }
}
