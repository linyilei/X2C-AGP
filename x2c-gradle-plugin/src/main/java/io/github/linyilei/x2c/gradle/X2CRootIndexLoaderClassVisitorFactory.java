package io.github.linyilei.x2c.gradle;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import org.objectweb.asm.ClassVisitor;

public abstract class X2CRootIndexLoaderClassVisitorFactory
        implements AsmClassVisitorFactory<X2CRootIndexInstrumentationParameters> {

    private static final long serialVersionUID = 1L;

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        String generatedRootInternalName = getParameters().get().getGeneratedRootInternalName().get();
        return X2CBytecodePatcher.rootIndexLoaderVisitor(nextClassVisitor, generatedRootInternalName, true);
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return X2CBytecodePatcher.X2C_CLASS_NAME.equals(classData.getClassName());
    }
}
