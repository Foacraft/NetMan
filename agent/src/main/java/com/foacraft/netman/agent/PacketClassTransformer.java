package com.foacraft.netman.agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * ASM ClassFileTransformer that injects a call to {@link PacketAttachmentStore#capture(Object)}
 * at the end of every constructor in identified NMS packet classes.
 *
 * Only transforms classes that directly implement/extend the Packet interface
 * (detected via bytecode scanning, no class loading). This prevents transforming
 * unrelated classes in the network package (Connection, chat components, inner
 * classes like PacketPlayOutBoss$g, etc.) which caused VerifyErrors and deadlocks.
 */
public class PacketClassTransformer implements ClassFileTransformer {

    private static final String STORE_OWNER =
            "com/foacraft/netman/agent/PacketAttachmentStore";
    private static final String CAPTURE_DESC = "(Ljava/lang/Object;)V";

    /** Known Packet interface names (internal format) across server versions. */
    private static final Set<String> PACKET_INTERFACE_NAMES = Set.of(
            // Mojang-mapped (Paper 1.17+)
            "net/minecraft/network/protocol/Packet",
            // CraftBukkit/Spigot obfuscated (1.12–1.20.x)
            "net/minecraft/server/v1_20_R1/Packet",
            "net/minecraft/server/v1_19_R3/Packet",
            "net/minecraft/server/v1_19_R2/Packet",
            "net/minecraft/server/v1_19_R1/Packet",
            "net/minecraft/server/v1_18_R2/Packet",
            "net/minecraft/server/v1_18_R1/Packet",
            "net/minecraft/server/v1_17_R1/Packet"
    );

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (className == null) return null;

        // Quick prefix check to avoid scanning every class
        if (!className.startsWith("net/minecraft/network/")
                && !className.contains("PacketPlay")) {
            return null;
        }

        try {
            // Scan bytecode to check if this class implements Packet interface
            ClassReader scanner = new ClassReader(classfileBuffer);
            if (!isPacketClass(scanner)) return null;

            ClassWriter cw = new ClassWriter(scanner, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };
            scanner.accept(new PacketClassVisitor(cw), 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Checks if a class implements the Packet interface by reading its
     * superName and interfaces from bytecode — no class loading involved.
     */
    private boolean isPacketClass(ClassReader cr) {
        String superName = cr.getSuperName();
        if (superName != null && PACKET_INTERFACE_NAMES.contains(superName)) {
            return true;
        }
        String[] interfaces = cr.getInterfaces();
        if (interfaces != null) {
            for (String iface : interfaces) {
                if (PACKET_INTERFACE_NAMES.contains(iface)) {
                    return true;
                }
            }
        }
        return false;
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
