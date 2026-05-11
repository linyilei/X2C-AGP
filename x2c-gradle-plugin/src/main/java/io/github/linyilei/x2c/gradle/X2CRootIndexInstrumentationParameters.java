package io.github.linyilei.x2c.gradle;

import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

public interface X2CRootIndexInstrumentationParameters extends InstrumentationParameters {
    @Input
    Property<String> getGeneratedRootInternalName();
}
