package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.plugin.cameo.ElementJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.nomagic.magicdraw.properties.Property;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Best-effort JSON for Cameo property managers and arbitrary reflected values. */
public final class PropertyJson {

    private static final int MAX_ITEMS = 250;

    private PropertyJson() {
    }

    public static JsonObject serializeManager(PropertyManager manager, boolean includeRaw, boolean summaryOnly) {
        JsonObject json = new JsonObject();
        JsonArray properties = new JsonArray();
        JsonArray warnings = new JsonArray();
        if (manager == null) {
            json.addProperty("propertyCount", 0);
            json.add("properties", properties);
            warnings.add("PropertyManager was null");
            json.add("warnings", warnings);
            return json;
        }
        json.addProperty("className", manager.getClass().getName());
        try {
            List<Property> list = manager.getProperties();
            if (list != null) {
                for (Property p : list) {
                    properties.add(serializeProperty(p, includeRaw, summaryOnly));
                }
            }
        } catch (Exception e) {
            warnings.add("Could not read properties: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        json.addProperty("propertyCount", properties.size());
        json.add("properties", properties);
        if (warnings.size() > 0) {
            json.add("warnings", warnings);
        }
        return json;
    }

    public static JsonObject serializeProperty(Property property, boolean includeRaw, boolean summaryOnly) {
        JsonObject json = new JsonObject();
        JsonArray warnings = new JsonArray();
        if (property == null) {
            json.add("value", JsonNull.INSTANCE);
            warnings.add("Property was null");
            json.add("warnings", warnings);
            return json;
        }
        safeAdd(json, "name", property::getName, warnings);
        safeAdd(json, "id", property::getID, warnings);
        safeAdd(json, "classType", property::getClassType, warnings);
        json.addProperty("propertyClassName", property.getClass().getName());
        Object value = null;
        try {
            value = property.getValue();
            json.add("value", serializeValue(value, includeRaw, summaryOnly));
            json.addProperty("valueType", value != null ? value.getClass().getName() : "null");
            json.addProperty("valueString", value != null ? String.valueOf(value) : null);
        } catch (Exception e) {
            warnings.add("Could not read value: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            json.add("value", JsonNull.INSTANCE);
        }
        if (includeRaw && value != null) {
            JsonObject raw = new JsonObject();
            raw.addProperty("className", value.getClass().getName());
            raw.addProperty("string", String.valueOf(value));
            json.add("raw", raw);
        }
        if (warnings.size() > 0) {
            json.add("warnings", warnings);
        }
        return json;
    }

    public static JsonElement serializeValue(Object value, boolean includeRaw, boolean summaryOnly) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Character) {
            return new JsonPrimitive((Character) value);
        }
        if (value instanceof Enum) {
            return new JsonPrimitive(((Enum<?>) value).name());
        }
        if (value instanceof Element) {
            try {
                return ElementJson.toJsonCompact((Element) value);
            } catch (RuntimeException e) {
                JsonObject o = new JsonObject();
                o.addProperty("className", value.getClass().getName());
                o.addProperty("warning", "Could not serialize element: " + e.getMessage());
                return o;
            }
        }
        if (value instanceof Collection<?>) {
            JsonArray array = new JsonArray();
            if (!summaryOnly) {
                int count = 0;
                for (Object item : (Collection<?>) value) {
                    if (count++ >= MAX_ITEMS) {
                        break;
                    }
                    array.add(serializeValue(item, includeRaw, true));
                }
            }
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("collectionType", value.getClass().getName());
            wrapper.addProperty("count", ((Collection<?>) value).size());
            wrapper.add("items", array);
            return wrapper;
        }
        if (value instanceof Map<?, ?>) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("mapType", value.getClass().getName());
            wrapper.addProperty("count", ((Map<?, ?>) value).size());
            if (!summaryOnly) {
                JsonObject entries = new JsonObject();
                int count = 0;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    if (count++ >= MAX_ITEMS) {
                        break;
                    }
                    entries.add(String.valueOf(e.getKey()), serializeValue(e.getValue(), includeRaw, true));
                }
                wrapper.add("entries", entries);
            }
            return wrapper;
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("className", value.getClass().getName());
        fallback.addProperty("string", String.valueOf(value));
        if (includeRaw) {
            appendGetterValues(fallback, value);
        }
        return fallback;
    }

    /**
     * Values of every public no-arg get/is method of {@code target}; failures go to warnings.
     * Used to dump settings objects whose API is not documented.
     */
    public static JsonObject reflectGetterValues(Object target, boolean includeRaw, boolean summaryOnly,
                                                 JsonArray warnings) {
        JsonObject values = new JsonObject();
        for (Method m : target.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == Void.TYPE || "getClass".equals(m.getName())) {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get") && !name.startsWith("is")) {
                continue;
            }
            try {
                values.add(name, serializeValue(m.invoke(target), includeRaw, summaryOnly));
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                        ? e.getCause() : e;
                warnings.add(name + " failed: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
        }
        return values;
    }

    /** Public methods of a class as JSON (name, returnType, parameterCount, static, parameterTypes). */
    public static JsonArray methods(Class<?> cls) {
        JsonArray array = new JsonArray();
        for (Method m : cls.getMethods()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", m.getName());
            entry.addProperty("returnType", m.getReturnType().getName());
            entry.addProperty("parameterCount", m.getParameterCount());
            entry.addProperty("static", java.lang.reflect.Modifier.isStatic(m.getModifiers()));
            JsonArray params = new JsonArray();
            for (Class<?> p : m.getParameterTypes()) {
                params.add(p.getName());
            }
            entry.add("parameterTypes", params);
            array.add(entry);
        }
        return array;
    }

    private static void appendGetterValues(JsonObject json, Object value) {
        JsonObject getters = new JsonObject();
        for (Method m : value.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == Void.TYPE) {
                continue;
            }
            String name = m.getName();
            if ((!name.startsWith("get") && !name.startsWith("is")) || "getClass".equals(name)) {
                continue;
            }
            try {
                getters.add(name, serializeValue(m.invoke(value), false, true));
            } catch (Throwable ignored) {
                // diagnostic dump: inaccessible getters are expected
            }
        }
        if (getters.size() > 0) {
            json.add("getterValues", getters);
        }
    }

    private static void safeAdd(JsonObject json, String key, Callable<String> supplier, JsonArray warnings) {
        try {
            String v = supplier.call();
            if (v != null) {
                json.addProperty(key, v);
            }
        } catch (Exception e) {
            warnings.add("Could not read " + key + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
