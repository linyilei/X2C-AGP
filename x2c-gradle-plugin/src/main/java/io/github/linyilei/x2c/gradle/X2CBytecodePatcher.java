package io.github.linyilei.x2c.gradle;

import org.gradle.api.GradleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class X2CBytecodePatcher {

    static final String X2C_CLASS_NAME = "io.github.linyilei.x2c.runtime.X2C";

    private static final String X2C_INTERNAL_NAME = "io/github/linyilei/x2c/runtime/X2C";
    private static final String X2C_ROOT_INDEX_INTERNAL_NAME = "io/github/linyilei/x2c/runtime/X2CRootIndex";
    private static final String ROOT_INDEX_INTERNAL_NAME = "io/github/linyilei/x2c/runtime/X2C$RootIndex";
    private static final String ROOT_INDEX_LOADER_METHOD = "tryLoadGeneratedRootIndex";
    private static final String ROOT_INDEX_LOADER_DESC =
            "(Landroid/content/Context;)Lio/github/linyilei/x2c/runtime/X2C$RootIndex;";
    private static final String LOAD_INTO_DESC = "(Landroid/util/SparseIntArray;Landroid/util/SparseArray;)V";

    private X2CBytecodePatcher() {
    }

    static byte[] patchRootIndexLoader(byte[] originalBytes, String generatedRootInternalName) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] patched = new boolean[]{false};
        reader.accept(createRootIndexLoaderVisitor(writer, generatedRootInternalName, patched, false), 0);
        if (!patched[0]) {
            throw missingRootLoaderMethod();
        }
        return writer.toByteArray();
    }

    static ClassVisitor rootIndexLoaderVisitor(ClassVisitor nextClassVisitor, String generatedRootInternalName,
                                               boolean failIfMissing) {
        return createRootIndexLoaderVisitor(nextClassVisitor, generatedRootInternalName,
                new boolean[]{false}, failIfMissing);
    }

    private static ClassVisitor createRootIndexLoaderVisitor(ClassVisitor nextClassVisitor,
                                                             String generatedRootInternalName,
                                                             boolean[] patched,
                                                             boolean failIfMissing) {
        String rootIndexLogMessage = "Loaded generated root index: "
                + generatedRootInternalName.replace('/', '.');
        return new ClassVisitor(Opcodes.ASM7, nextClassVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (ROOT_INDEX_LOADER_METHOD.equals(name) && ROOT_INDEX_LOADER_DESC.equals(descriptor)) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    mv.visitCode();
                    mv.visitTypeInsn(Opcodes.NEW, ROOT_INDEX_INTERNAL_NAME);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ROOT_INDEX_INTERNAL_NAME, "<init>", "()V", false);
                    mv.visitVarInsn(Opcodes.ASTORE, 1);
                    mv.visitTypeInsn(Opcodes.NEW, generatedRootInternalName);
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, generatedRootInternalName, "<init>", "()V", false);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.GETFIELD, ROOT_INDEX_INTERNAL_NAME,
                            "layoutToGroup", "Landroid/util/SparseIntArray;");
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.GETFIELD, ROOT_INDEX_INTERNAL_NAME,
                            "groups", "Landroid/util/SparseArray;");
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, X2C_ROOT_INDEX_INTERNAL_NAME,
                            "loadInto", LOAD_INTO_DESC, true);
                    mv.visitLdcInsn(rootIndexLogMessage);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, X2C_INTERNAL_NAME,
                            "log", "(Ljava/lang/String;)V", false);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    patched[0] = true;
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                super.visitEnd();
                if (failIfMissing && !patched[0]) {
                    throw missingRootLoaderMethod();
                }
            }
        };
    }

    private static GradleException missingRootLoaderMethod() {
        return new GradleException("X2C ASM could not find " + ROOT_INDEX_LOADER_METHOD
                + " in " + X2C_INTERNAL_NAME + ".");
    }
}
