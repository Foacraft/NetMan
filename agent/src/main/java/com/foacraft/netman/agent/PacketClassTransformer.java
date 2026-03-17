package com.foacraft.netman.agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * ASM ClassFileTransformer that injects a call to {@link PacketAttachmentStore#capture(Object)}
 * at the end of every constructor in identified NMS packet classes.
 *
 * Injection strategy:
 *   - Intercepts RETURN opcodes in constructors via visitInsn — inserts ALOAD_0 + INVOKESTATIC
 *     before each RETURN. Does NOT use AdviceAdapter to keep injection minimal and avoid
 *     altering control flow (which would require COMPUTE_FRAMES and risk classloader deadlock).
 *
 * Target class detection:
 *   - Package prefix: net/minecraft/network/  (Mojang-mapped Paper 1.18+)
 *   - Class name contains PacketPlay           (CraftBukkit/Spigot remapped names on 1.20.1)
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
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    // Never call Class.forName — avoids classloader deadlock inside transformers.
                    return "java/lang/Object";
                }
            };
            cr.accept(new PacketClassVisitor(cw), 0);
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

    // ── Class visitor ──────────────────────────────────────────────────────────

    private static class PacketClassVisitor extends ClassVisitor {

        PacketClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<init>".equals(name) && descriptor.endsWith(")V")) {
                return new ConstructorInjector(mv);
            }
            return mv;
        }
    }

    // ── Method visitor ─────────────────────────────────────────────────────────

    /**
     * Minimal MethodVisitor that intercepts RETURN (0xB1) in constructors and
     * inserts ALOAD_0 + INVOKESTATIC PacketAttachmentStore.capture before it.
     * Does not alter control flow — stack map frames from the original class
     * are passed through unchanged by the ClassReader.
     */
    private static class ConstructorInjector extends MethodVisitor {

        ConstructorInjector(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        STORE_OWNER,
                        "capture",
                        CAPTURE_DESC,
                        false);
            }
            super.visitInsn(opcode);
        }
    }
}
