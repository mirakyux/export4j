// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.TreeMap;

/** Per-binding property plans; no process-wide references to application classes or object instances. */
final class TemplateProperties {
    private TemplateProperties() { }

    record Property(String name, Method accessor) {
        Object read(Object owner) {
            ExecutionContext.checkpoint();
            try {
                // Public accessors on local/package-private DTO types are useful without reading private fields.
                if (!accessor.canAccess(owner) && !accessor.trySetAccessible()) { throw typeMismatch(); }
                Object value = accessor.invoke(owner);
                ExecutionContext.checkpoint();
                return value;
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof ExecutionException execution) { throw execution; }
                if (cause instanceof Error error) { throw error; }
                ExecutionContext.checkpoint();
                // Application exception messages/causes can contain document data and must not escape.
                throw new DocumentTemplate.BindingException(DocumentTemplate.BindingException.Code.INVALID_VALUE, "data." + name);
            } catch (IllegalAccessException | InaccessibleObjectException | SecurityException inaccessible) {
                throw typeMismatch();
            }
        }
    }

    static void requireObjectType(Class<?> type) {
        String module = type.getModule().getName();
        if (module != null && (module.startsWith("java.") || module.startsWith("jdk."))
                || type.isArray() || Number.class.isAssignableFrom(type) || Enum.class.isAssignableFrom(type)
                || Iterable.class.isAssignableFrom(type) || java.util.Date.class.isAssignableFrom(type)
                || java.util.Calendar.class.isAssignableFrom(type)
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)
                || java.time.temporal.TemporalAmount.class.isAssignableFrom(type)
                || ClassLoader.class.isAssignableFrom(type) || Thread.class.isAssignableFrom(type)
                || java.io.InputStream.class.isAssignableFrom(type) || java.io.Reader.class.isAssignableFrom(type)) {
            throw typeMismatch();
        }
    }

    static List<Property> describe(Class<?> type) {
        requireObjectType(type);
        var properties = new TreeMap<String, Property>();
        try {
            if (type.isRecord()) {
                for (var component : type.getRecordComponents()) {
                    properties.put(component.getName(), new Property(component.getName(), component.getAccessor()));
                }
            } else {
                for (var method : type.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()
                            || method.getParameterCount() != 0 || method.getReturnType() == void.class
                            || method.getName().equals("getClass")) { continue; }
                    String suffix;
                    if (method.getName().startsWith("is") && method.getName().length() > 2
                            && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                        suffix = method.getName().substring(2);
                    } else if (method.getName().startsWith("get") && method.getName().length() > 3) {
                        suffix = method.getName().substring(3);
                    } else { continue; }
                    String name = decapitalize(suffix);
                    var property = new Property(name, method);
                    properties.merge(name, property, TemplateProperties::prefer);
                }
                if (properties.isEmpty()) { throw typeMismatch(); }
            }
        } catch (SecurityException inaccessible) {
            throw typeMismatch();
        }
        return List.copyOf(properties.values());
    }

    private static Property prefer(Property first, Property second) {
        boolean firstBoolean = first.accessor().getName().startsWith("is");
        boolean secondBoolean = second.accessor().getName().startsWith("is");
        if (firstBoolean != secondBoolean) { return firstBoolean ? first : second; }
        // Reflection enumeration order is unspecified: reject colliding spellings such as getX/getx.
        if (!first.accessor().getName().equals(second.accessor().getName())) { throw typeMismatch(); }
        Class<?> firstOwner = first.accessor().getDeclaringClass();
        Class<?> secondOwner = second.accessor().getDeclaringClass();
        if (firstOwner.isAssignableFrom(secondOwner)) { return second; }
        if (secondOwner.isAssignableFrom(firstOwner)) { return first; }
        if (first.accessor().getReturnType().isAssignableFrom(second.accessor().getReturnType())) { return second; }
        if (second.accessor().getReturnType().isAssignableFrom(first.accessor().getReturnType())) { return first; }
        throw typeMismatch();
    }

    private static String decapitalize(String value) {
        if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static DocumentTemplate.BindingException typeMismatch() {
        return new DocumentTemplate.BindingException(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, "data");
    }
}
