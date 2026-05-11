package io.github.linyilei.x2c.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;

final class X2COptions {

    static final String OPTION_GENERATED_PACKAGE = "x2c.generatedPackage";
    static final String OPTION_R_PACKAGE = "x2c.rPackage";
    static final String OPTION_APPLICATION_MODULE = "x2c.applicationModule";
    static final String OPTION_RES_DIRS = "x2c.resDirs";
    static final String OPTION_GROUP_SIZE = "x2c.groupSize";
    static final String OPTION_EXCLUDES = "x2c.excludes";
    static final int DEFAULT_GROUP_SIZE = 64;

    final String generatedPackage;
    final String rPackage;
    final boolean applicationModule;
    final List<File> resDirs;
    final int groupSize;
    final Set<String> excludes;

    private X2COptions(String generatedPackage, String rPackage, boolean applicationModule,
                       List<File> resDirs, int groupSize, Set<String> excludes) {
        this.generatedPackage = generatedPackage;
        this.rPackage = rPackage;
        this.applicationModule = applicationModule;
        this.resDirs = resDirs;
        this.groupSize = groupSize;
        this.excludes = excludes;
    }

    static X2COptions from(ProcessingEnvironment processingEnv) {
        Map<String, String> options = processingEnv.getOptions();
        String generatedPackage = trimToEmpty(options.get(OPTION_GENERATED_PACKAGE));
        if (generatedPackage.isEmpty()) {
            throw new IllegalStateException("X2C requires processor option " + OPTION_GENERATED_PACKAGE + ".");
        }
        String rPackage = trimToEmpty(options.get(OPTION_R_PACKAGE));
        if (rPackage.isEmpty()) {
            rPackage = generatedPackage.endsWith(".x2c")
                    ? generatedPackage.substring(0, generatedPackage.length() - ".x2c".length())
                    : generatedPackage;
        }
        boolean applicationModule = Boolean.parseBoolean(trimToEmpty(options.get(OPTION_APPLICATION_MODULE)));
        List<File> resDirs = parseFiles(options.get(OPTION_RES_DIRS));
        int groupSize = parsePositiveInt(options.get(OPTION_GROUP_SIZE), DEFAULT_GROUP_SIZE);
        Set<String> excludes = parseLayoutNames(options.get(OPTION_EXCLUDES));
        return new X2COptions(generatedPackage, rPackage, applicationModule, resDirs, groupSize, excludes);
    }

    private static List<File> parseFiles(String rawValue) {
        List<File> result = new ArrayList<>();
        String value = trimToEmpty(rawValue);
        if (value.isEmpty()) {
            return result;
        }
        String[] parts = value.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String part : parts) {
            String path = trimToEmpty(part);
            if (!path.isEmpty()) {
                File file = new File(path);
                if (file.exists()) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    private static int parsePositiveInt(String rawValue, int defaultValue) {
        String value = trimToEmpty(rawValue);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Set<String> parseLayoutNames(String rawValue) {
        Set<String> result = new LinkedHashSet<>();
        String value = trimToEmpty(rawValue);
        if (value.isEmpty()) {
            return result;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            String normalized = normalizeLayoutName(part);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    static String normalizeLayoutName(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("@layout/")) {
            value = value.substring("@layout/".length());
        } else if (value.startsWith("@+layout/")) {
            value = value.substring("@+layout/".length());
        }
        if (value.endsWith(".xml")) {
            value = value.substring(0, value.length() - ".xml".length());
        }
        return value.isEmpty() ? null : value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
