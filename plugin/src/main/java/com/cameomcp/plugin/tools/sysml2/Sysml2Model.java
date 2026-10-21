package com.cameomcp.plugin.tools.sysml2;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective access to SysML v2 (KerML) model elements. The KerML API lives in the SysML v2
 * plugin of Cameo 2026x, which this plugin cannot require (2024x has no such plugin), and its
 * implementation classes are not public, so every call goes through the public KerML interfaces.
 */
final class Sysml2Model {

    /**
     * Fully qualified name of the KerML Element interface in Cameo 2026x's SysML v2 implementation. Matched
     * by name because the interface is not on the compile class path.
     */
    static final String KERML_ELEMENT = "com.dassault_systemes.modeler.kerml.model.kerml.Element";

    /** Upper bound on elements visited by one tree walk. */
    static final int WALK_LIMIT = 200_000;

    // Reflective method cache keyed by "class#name,paramTypes"; MISSING marks a lookup that found nothing,
    // so absent methods are not searched for again on every element.
    /** Cache of reflective method lookups, keyed by "class#name,paramTypes". */
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    /**
     * Marker value in {@link #METHODS} for a lookup that found nothing, so absent methods are not searched
     * again.
     */
    private static final Method MISSING;

    static {
        try {
            MISSING = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Not instantiable; all members are static. */
    private Sysml2Model() {
    }

    // ------------------------------------------------------------------ reflection

    /**
     * Whether {@code o} implements the KerML Element interface.
     *
     * @param o any object, may be null
     * @return true when its class or a superclass implements {@link #KERML_ELEMENT}
     */
    static boolean isElement(Object o) {
        // Walk superclasses because the KerML interface may be implemented by a base class of the runtime type.
        for (Class<?> c = o == null ? null : o.getClass(); c != null; c = c.getSuperclass()) {
            if (implementsNamed(c, KERML_ELEMENT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursive check through the super-interfaces, by name, since the interface class is not on the compile
     * class path.
     *
     * @param c the class or interface to check
     * @param name the fully qualified interface name
     * @return true when c implements it directly or through a super-interface
     */
    private static boolean implementsNamed(Class<?> c, String name) {
        for (Class<?> i : c.getInterfaces()) {
            if (name.equals(i.getName()) || implementsNamed(i, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calls a public no-arg method; null when the method does not exist.
     *
     * @param target the object to call on, may be null
     * @param name the method name
     * @return the result, or null when the target is null or the method is missing
     * @throws ToolException TOOL_FAILED when the method throws a checked exception or cannot be accessed
     */
    static Object call(Object target, String name) {
        return callWith(target, name, new Class<?>[0]);
    }

    /**
     * Invokes a public method with explicit parameter types. Runtime exceptions from the call propagate
     * unchanged; checked ones become TOOL_FAILED.
     *
     * @param target the object to call on, may be null
     * @param name the method name
     * @param types the parameter types
     * @param args the arguments
     * @return the result, or null when the target is null or the method is missing
     * @throws ToolException TOOL_FAILED when the method throws a checked exception or cannot be accessed
     */
    static Object callWith(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        Method m = method(target.getClass(), name, types);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            throw new ToolException("TOOL_FAILED", name + " failed: " + c);
        } catch (IllegalAccessException e) {
            throw new ToolException("TOOL_FAILED", "Cannot call " + name + ": " + e.getMessage());
        }
    }

    /**
     * Calls a method and swallows failures (for optional, informational properties).
     *
     * @param target the object to call on, may be null
     * @param name the zero-argument method name
     * @return the result, or null when the method is missing or the call fails
     */
    static Object quiet(Object target, String name) {
        try {
            return call(target, name);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The method as declared by a public class or interface, so it can be invoked although the
     * runtime class is package-private.
     *
     * <p>Results, including misses, are cached in {@link #METHODS}.
     *
     * @param type the runtime class
     * @param name the method name
     * @param types the parameter types
     * @return an invocable method, or null when there is none
     */
    static Method method(Class<?> type, String name, Class<?>... types) {
        StringBuilder key = new StringBuilder(type.getName()).append('#').append(name);
        for (Class<?> t : types) {
            key.append(',').append(t.getName());
        }
        Method cached = METHODS.get(key.toString());
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        Method found = publicMethod(type, name, types);
        METHODS.put(key.toString(), found == null ? MISSING : found);
        return found;
    }

    /**
     * Finds an invocable declaration of a method. Resolution order: the method itself if declared by a public
     * class; else the same method on a public superclass or interface (callable through the non-public
     * implementation); else setAccessible as a last resort, which can fail under module or security
     * restrictions, in which case the method is treated as missing.
     *
     * @param type the runtime class
     * @param name the method name
     * @param types the parameter types
     * @return an invocable method, or null
     */
    private static Method publicMethod(Class<?> type, String name, Class<?>[] types) {
        Method m;
        try {
            m = type.getMethod(name, types);
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            return m;
        }
        Method viaInterface = fromInterfaces(type, name, types);
        if (viaInterface != null) {
            return viaInterface;
        }
        try {
            m.setAccessible(true);
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Searches public superclasses and every interface of the type hierarchy for an invocable declaration.
     *
     * @param type the runtime class
     * @param name the method name
     * @param types the parameter types
     * @return the declaration found first, or null
     */
    private static Method fromInterfaces(Class<?> type, String name, Class<?>[] types) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c != type && Modifier.isPublic(c.getModifiers())) {
                try {
                    return c.getMethod(name, types);
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                Method m = fromInterface(i, name, types);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Searches depth-first through an interface and its super-interfaces. Only public ones yield a usable
     * Method.
     *
     * @param i the interface
     * @param name the method name
     * @param types the parameter types
     * @return the declaration found first, or null
     */
    private static Method fromInterface(Class<?> i, String name, Class<?>[] types) {
        if (Modifier.isPublic(i.getModifiers())) {
            try {
                return i.getMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                // try super-interfaces
            }
        }
        for (Class<?> s : i.getInterfaces()) {
            Method m = fromInterface(s, name, types);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ element properties

    /**
     * Calls an optional property getter and returns its text.
     *
     * @param target the object to call on, may be null
     * @param name the zero-argument getter name
     * @return the result's toString, or null when the getter is missing, fails or returns null
     */
    static String str(Object target, String name) {
        Object v = quiet(target, name);
        return v == null ? null : v.toString();
    }

    /**
     * The id Cameo resolves with Project.getElementByID, falling back to the KerML elementId.
     *
     * @param element a KerML element
     * @return the id, or null when neither getter gives one
     */
    static String id(Object element) {
        String id = str(element, "getID");
        return id != null && !id.isEmpty() ? id : str(element, "getElementId");
    }

    /**
     * Effective name, falling back to the declared name.
     *
     * @param element a KerML element
     * @return the name; "" when there is none, never null
     */
    static String name(Object element) {
        String n = str(element, "getName");
        if (n == null) {
            n = str(element, "getDeclaredName");
        }
        return n == null ? "" : n;
    }

    /**
     * SysML v2 metaclass name, such as PartDefinition.
     *
     * @param element a KerML element
     * @return the EMF metaclass name, or the runtime class's simple name without an "Impl" suffix
     */
    static String type(Object element) {
        // The implementations are EMF objects: eClass().getName() is the metaclass; otherwise strip "Impl" from the class name.
        Object eClass = quiet(element, "eClass");
        String n = eClass == null ? null : str(eClass, "getName");
        if (n != null && !n.isEmpty()) {
            return n;
        }
        String simple = element.getClass().getSimpleName();
        return simple.endsWith("Impl") ? simple.substring(0, simple.length() - 4) : simple;
    }

    /**
     * Whether the element belongs to a standard library (such as the KerML and SysML libraries), which is
     * read-only.
     *
     * @param element a KerML element
     * @return true only when isLibraryElement returns true
     */
    static boolean isLibrary(Object element) {
        return Boolean.TRUE.equals(quiet(element, "isLibraryElement"));
    }

    /**
     * Owned elements (members of a namespace, features of a type, ...).
     *
     * @param element a KerML element
     * @return the non-null owned elements in model order; empty when the getter is missing
     */
    static List<Object> children(Object element) {
        Object owned = quiet(element, "getOwnedElement");
        if (!(owned instanceof Collection)) {
            return Collections.emptyList();
        }
        List<Object> out = new ArrayList<>();
        for (Object o : (Collection<?>) owned) {
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * The owner, or the owning namespace when getOwner is absent or returns null.
     *
     * @param element a KerML element
     * @return the owner, or null for a root
     */
    static Object owner(Object element) {
        Object o = quiet(element, "getOwner");
        return o != null ? o : quiet(element, "getOwningNamespace");
    }

    /**
     * Compact JSON used in every listing.
     *
     * @param element a KerML element
     * @return {@code id}, {@code elementId}, {@code name}, {@code type} and the optional short name and flags
     */
    static JsonObject compact(Object element) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id(element));
        String elementId = str(element, "getElementId");
        if (elementId != null) {
            json.addProperty("elementId", elementId);
        }
        json.addProperty("name", name(element));
        json.addProperty("type", type(element));
        String shortName = str(element, "getShortName");
        if (shortName != null && !shortName.isEmpty()) {
            json.addProperty("shortName", shortName);
        }
        String qn = str(element, "getQualifiedName");
        if (qn != null && !qn.isEmpty()) {
            json.addProperty("qualifiedName", qn);
        }
        json.addProperty("childCount", children(element).size());
        if (isLibrary(element)) {
            json.addProperty("isLibraryElement", true);
        }
        return json;
    }

    /**
     * Full detail for cameo_sysml2_get_element.
     *
     * @param element a KerML element
     * @param membersLimit the most owned elements to list
     * @return the compact JSON plus declared names, humanType, flags, {@code owner}, {@code documentation}
     *     bodies and
     * up to membersLimit {@code ownedElements}, with {@code ownedElementsTruncated} when cut
     */
    static JsonObject detail(Object element, int membersLimit) {
        JsonObject json = compact(element);
        putIfPresent(json, "declaredName", str(element, "getDeclaredName"));
        putIfPresent(json, "declaredShortName", str(element, "getDeclaredShortName"));
        putIfPresent(json, "humanType", str(element, "getHumanType"));
        json.addProperty("isLibraryElement", isLibrary(element));
        Object editable = quiet(element, "isEditable");
        if (editable instanceof Boolean) {
            json.addProperty("isEditable", (Boolean) editable);
        }
        Object owner = owner(element);
        if (owner != null && isElement(owner)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id(owner));
            o.addProperty("name", name(owner));
            o.addProperty("type", type(owner));
            json.add("owner", o);
        }
        JsonArray docs = new JsonArray();
        Object documentation = quiet(element, "getDocumentation");
        if (documentation instanceof Collection) {
            for (Object d : (Collection<?>) documentation) {
                String body = str(d, "getBody");
                if (body != null) {
                    docs.add(body);
                }
            }
        }
        json.add("documentation", docs);
        List<Object> kids = children(element);
        JsonArray members = new JsonArray();
        for (int i = 0; i < kids.size() && i < membersLimit; i++) {
            members.add(compact(kids.get(i)));
        }
        json.add("ownedElements", members);
        if (kids.size() > membersLimit) {
            json.addProperty("ownedElementsTruncated", true);
        }
        return json;
    }

    /**
     * Adds a string field when the value is non-empty.
     *
     * @param json the object to add to
     * @param key the field name
     * @param value the value, may be null
     */
    private static void putIfPresent(JsonObject json, String key, String value) {
        if (value != null && !value.isEmpty()) {
            json.addProperty(key, value);
        }
    }

    /**
     * The part of {@code text} between two generator indexes, widened to the closing ';' or '}' when
     * the end index is inclusive, with the indentation of nested lines removed.
     *
     * @param text the generated textual notation, may be null
     * @param start the start index
     * @param end the end index (exclusive)
     * @return the slice, trimmed; "" when the indexes are outside the text or the range is empty
     */
    static String slice(String text, int start, int end) {
        if (text == null || start < 0 || start >= text.length() || end <= start) {
            return "";
        }
        int stop = Math.min(end, text.length());
        String body = text.substring(start, stop);
        // The generator's end index can stop just before the closing brace or semicolon; include it when the slice
        // would otherwise end unbalanced (open brace) or without its terminating ';'.
        if (stop < text.length()) {
            char next = text.charAt(stop);
            String trimmed = body.trim();
            boolean unclosed = count(body, '{') > count(body, '}');
            if ((next == '}' && unclosed)
                    || (next == ';' && !unclosed && !trimmed.endsWith(";") && !trimmed.endsWith("}"))) {
                body = text.substring(start, stop + 1);
            }
        }
        // Indentation of the first line in the source; that much leading whitespace is removed from following lines.
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int indent = 0;
        while (lineStart + indent < start && Character.isWhitespace(text.charAt(lineStart + indent))) {
            indent++;
        }
        if (indent == 0) {
            return body.trim();
        }
        StringBuilder out = new StringBuilder();
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                int cut = 0;
                while (cut < indent && cut < line.length() && Character.isWhitespace(line.charAt(cut))) {
                    cut++;
                }
                line = line.substring(cut);
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString().trim();
    }

    /**
     * Counts occurrences of a character.
     *
     * @param s the text
     * @param c the character
     * @return how many times c occurs in s
     */
    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ traversal

    /**
     * Element with this id or KerML elementId under the given roots, or null.
     *
     * @param roots the elements to search from
     * @param id the Cameo id or KerML elementId to find
     * @return the element, or null when not found within {@link #WALK_LIMIT} visits
     */
    static Object findUnder(Collection<?> roots, String id) {
        Deque<Object> stack = new ArrayDeque<>(roots);
        int visited = 0;
        while (!stack.isEmpty() && visited++ < WALK_LIMIT) {
            Object e = stack.pop();
            if (id.equals(str(e, "getElementId")) || id.equals(str(e, "getID"))) {
                return e;
            }
            List<Object> kids = children(e);
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(kids.get(i));
            }
        }
        return null;
    }

    /**
     * Depth-first search by name substring and metaclass, paged.
     *
     * <p>Library elements are skipped with their whole subtree unless includeLibraries is set.
     *
     * @param roots the elements to search from
     * @param name name substring, ignoring case; null or blank matches any name
     * @param type exact metaclass name, ignoring case; null or blank matches any type
     * @param includeLibraries true to search standard-library elements too
     * @param limit the page size
     * @param offset matches to skip
     * @return {@code count}, {@code offset}, {@code hasMore}, {@code nextOffset} when there is more, {@code
     *     truncated}
     * with a {@code hint} when the walk limit was hit, and the {@code elements} page
     */
    static JsonObject query(Collection<?> roots, String name, String type, boolean includeLibraries, int limit,
                            int offset) {
        // Name match is a case-insensitive substring; type match is an exact case-insensitive metaclass name.
        String needle = name == null || name.trim().isEmpty() ? null : name.trim().toLowerCase(Locale.ROOT);
        String wanted = type == null || type.trim().isEmpty() ? null : type.trim();
        JsonArray matches = new JsonArray();
        int matched = 0;
        int visited = 0;
        boolean hasMore = false;
        boolean truncated = false;
        Deque<Object> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            if (visited++ >= WALK_LIMIT) {
                truncated = true;
                break;
            }
            Object e = stack.pop();
            // Library elements are skipped together with their whole subtree.
            if (!includeLibraries && isLibrary(e)) {
                continue;
            }
            boolean ok = (wanted == null || wanted.equalsIgnoreCase(type(e)))
                    && (needle == null || name(e).toLowerCase(Locale.ROOT).contains(needle));
            if (ok) {
                // Matches before offset are counted but not returned; one match past the page sets hasMore and stops the walk.
                if (matched >= offset && matches.size() < limit) {
                    matches.add(compact(e));
                } else if (matches.size() >= limit) {
                    hasMore = true;
                    break;
                }
                matched++;
            }
            List<Object> kids = children(e);
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(kids.get(i));
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", matches.size());
        out.addProperty("offset", offset);
        out.addProperty("hasMore", hasMore);
        if (hasMore) {
            out.addProperty("nextOffset", offset + matches.size());
        }
        if (truncated) {
            out.addProperty("truncated", true);
            out.addProperty("hint", "Search stopped after " + WALK_LIMIT + " elements; narrow it with scope_id.");
        }
        out.add("elements", matches);
        return out;
    }

    /**
     * Number of elements in the subtree, the root included (bounded by {@link #WALK_LIMIT}).
     *
     * @param root the subtree root
     * @return the element count, at most {@link #WALK_LIMIT}
     */
    static int count(Object root) {
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        int n = 0;
        while (!stack.isEmpty() && n < WALK_LIMIT) {
            Object e = stack.pop();
            n++;
            for (Object k : children(e)) {
                stack.push(k);
            }
        }
        return n;
    }
}
