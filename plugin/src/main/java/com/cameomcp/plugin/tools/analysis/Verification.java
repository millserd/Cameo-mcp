package com.cameomcp.plugin.tools.analysis;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cameomcp.plugin.tools.analysis.J.check;
import static com.cameomcp.plugin.tools.analysis.J.normalizedKey;
import static com.cameomcp.plugin.tools.analysis.J.normalizedText;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static com.cameomcp.plugin.tools.analysis.J.safeInt;
import static com.cameomcp.plugin.tools.analysis.J.truthy;

/**
 * Pure verification heuristics over JSON payloads returned by the read tools: diagram image and
 * shape layout checks, matrix consistency, activity flow semantics, port boundary consistency,
 * requirement quality and cross-diagram traceability. No Cameo API access.
 */
public final class Verification {

    /** Substrings of a shape's element type that mark it as a relationship (path) rather than a node. */
    static final Set<String> RELATIONSHIP_TYPE_HINTS = new LinkedHashSet<>(Arrays.asList(
            "association", "dependency", "connector", "transition", "item flow", "information flow",
            "control flow", "object flow", "generalization", "include", "extend", "allocate", "satisfy",
            "verify", "derive", "refine", "trace"));

    /**
     * Generic verbs and articles ignored when comparing names, so "Send Order" and "Order" share the token
     * set {order}.
     */
    static final Set<String> TERM_STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "and", "display", "for", "from", "get", "of", "process", "provide", "query", "receive",
            "record", "select", "selection", "send", "show", "submit", "the", "to", "update", "with"));

    /**
     * Directive verb. A requirement counts as measurable only when it has a directive verb and a {@link
     * #MEASUREMENT} cue.
     */
    static final Pattern DIRECTIVE = Pattern.compile("\\b(shall|must|will)\\b", Pattern.CASE_INSENSITIVE);
    /** Measurement cue: a number, a time unit, a percentage or a quantitative phrase such as "at least". */
    static final Pattern MEASUREMENT = Pattern.compile("\\b("
            + "\\d+"
            + "|ms|millisecond|milliseconds|second|seconds|minute|minutes|hour|hours"
            + "|day|days|percent|%|availability|uptime|latency|response time|recover"
            + "|recovery|concurrent|throughput|capacity|max(?:imum)?|minimum|min(?:imum)?"
            + "|less than|greater than|at least|no more than|within"
            + ")\\b", Pattern.CASE_INSENSITIVE);
    /** Lower-case alphanumeric run, one token. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    /** The eight-byte PNG file signature. */
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** Static methods only. */
    private Verification() {
    }

    // ------------------------------------------------------------------ small extractors

    /**
     * The payload's ID, accepting the key variants used by the different read tools.
     *
     * @param payload element or relationship payload
     * @return the ID as a string, or null when none is set
     */
    static String elementId(Map<String, Object> payload) {
        Object v = J.or(payload.get("id"), payload.get("elementId"), payload.get("relationshipId"));
        return v == null ? null : J.str(v);
    }

    /**
     * The payload's relationship ID ("relationshipId", then "id").
     *
     * @param payload relationship payload
     * @return the ID as a string, or null when none is set
     */
    static String relationshipId(Map<String, Object> payload) {
        Object v = J.or(payload.get("relationshipId"), payload.get("id"));
        return v == null ? null : J.str(v);
    }

    /**
     * Normalized lower-case type name ("type", then "humanType", then "elementType").
     *
     * @param payload element payload
     * @return the type key, or "" when none is set
     */
    static String elementTypeName(Map<String, Object> payload) {
        return normalizedKey(J.or(payload.get("type"), payload.get("humanType"), payload.get("elementType")));
    }

    /**
     * Normalized lower-case relationship type name ("type", then "relationshipType", then "humanType").
     *
     * @param payload relationship payload
     * @return the type key, or "" when none is set
     */
    static String relationshipTypeName(Map<String, Object> payload) {
        return normalizedKey(J.or(payload.get("type"), payload.get("relationshipType"), payload.get("humanType")));
    }

    /**
     * ID of an element reference that may be a {@code {id, ...}} map or a bare ID string.
     *
     * @param payload a reference map, a bare ID, or null
     * @return the ID as a string, or null
     */
    static String referenceId(Object payload) {
        if (payload instanceof Map) {
            Map<String, Object> m = J.map(payload);
            Object v = J.or(m.get("id"), m.get("elementId"));
            return v == null ? null : J.str(v);
        }
        if (payload == null) {
            return null;
        }
        return J.str(payload);
    }

    /**
     * Name of an element reference that may be a {@code {name, ...}} map or a bare string.
     *
     * @param payload a reference map or any value
     * @return the whitespace-normalized name, or ""
     */
    static String referenceName(Object payload) {
        if (payload instanceof Map) {
            Map<String, Object> m = J.map(payload);
            return normalizedText(J.or(m.get("name"), m.get("elementName"), ""));
        }
        return normalizedText(payload);
    }

    /**
     * Tagged values from the payload's own "taggedValues" and every applied stereotype, later ones winning.
     *
     * <p>Tag names are used as reported, so lookups are case-sensitive.
     *
     * @param payload element payload
     * @return a new map of tag name to value
     */
    static Map<String, Object> nestedTaggedValues(Map<String, Object> payload) {
        Map<String, Object> tagged = new LinkedHashMap<>();
        Map<String, Object> direct = J.map(payload.get("taggedValues"));
        if (direct != null) {
            tagged.putAll(direct);
        }
        for (Object stereo : J.list(payload.get("appliedStereotypes"))) {
            Map<String, Object> s = J.map(stereo);
            if (s == null) {
                continue;
            }
            Map<String, Object> tags = J.map(s.get("taggedValues"));
            if (tags != null) {
                tagged.putAll(tags);
            }
        }
        return tagged;
    }

    /**
     * Flow property direction. It may be reported at top level (InterfaceFlowReader), in a specification
     * "properties" map, or only as a stereotype tagged value, depending on which tool produced the payload.
     *
     * @param payload flow property payload
     * @return the lower-case direction (in, out or inout), or null when none is set
     */
    static String flowPropertyDirection(Map<String, Object> payload) {
        Object value = payload.get("direction");
        if (value != null) {
            String text = normalizedKey(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        Map<String, Object> properties = J.map(payload.get("properties"));
        if (properties != null) {
            value = properties.get("direction");
            if (value != null) {
                String text = normalizedKey(value);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        value = nestedTaggedValues(payload).get("direction");
        if (value == null) {
            return null;
        }
        String text = normalizedKey(value);
        return text.isEmpty() ? null : text;
    }

    /**
     * Grouping key for direction conflicts: the owning interface's ID, else its name, else "".
     *
     * @param payload flow property payload
     * @return the normalized scope key
     */
    static String flowPropertyScopeKey(Map<String, Object> payload) {
        for (String key : new String[]{"scopeId", "interfaceScopeId", "interfaceBlockId", "ownerId"}) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }
            String text = normalizedKey(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        for (String key : new String[]{"scopeName", "interfaceScopeName", "interfaceBlockName", "ownerName"}) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }
            String text = normalizedText(value);
            if (!text.isEmpty()) {
                return J.lower(text);
            }
        }
        return "";
    }

    /**
     * The value as a string when it is a non-blank string.
     *
     * @param value any value
     * @return value when it is a non-blank string, else null
     */
    private static String nonBlankString(Object value) {
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return (String) value;
        }
        return null;
    }

    /**
     * The SysML requirement Id: "requirementId", then "id" in "properties", then an "id" tagged value.
     *
     * @param payload requirement payload
     * @return the trimmed ID, or "" when none is set
     */
    static String extractRequirementId(Map<String, Object> payload) {
        String v = nonBlankString(payload.get("requirementId"));
        if (v != null) {
            return v.trim();
        }
        Map<String, Object> properties = J.map(payload.get("properties"));
        if (properties != null) {
            v = nonBlankString(properties.get("id"));
            if (v != null) {
                return v.trim();
            }
        }
        v = nonBlankString(nestedTaggedValues(payload).get("id"));
        return v == null ? "" : v.trim();
    }

    /**
     * Requirement text: top-level "text" or "documentation", then the same keys in "properties", then a
     * constraint body, then a "text" tagged value.
     *
     * @param payload requirement payload
     * @return the whitespace-normalized text, or ""
     */
    static String extractRequirementText(Map<String, Object> payload) {
        for (String key : new String[]{"text", "documentation"}) {
            String v = nonBlankString(payload.get(key));
            if (v != null) {
                return normalizedText(v);
            }
        }
        Map<String, Object> properties = J.map(payload.get("properties"));
        if (properties != null) {
            for (String key : new String[]{"text", "documentation"}) {
                String v = nonBlankString(properties.get(key));
                if (v != null) {
                    return normalizedText(v);
                }
            }
        }
        Map<String, Object> constraints = J.map(payload.get("constraints"));
        if (constraints != null) {
            String v = nonBlankString(constraints.get("text"));
            if (v != null) {
                return normalizedText(v);
            }
        }
        String v = nonBlankString(nestedTaggedValues(payload).get("text"));
        return v == null ? "" : normalizedText(v);
    }

    // ------------------------------------------------------------------ term matching

    /**
     * Distinct lower-case alphanumeric tokens of a name, minus the generic {@link #TERM_STOPWORDS}.
     *
     * @param value any value, usually a name
     * @return the tokens, in first-seen order
     */
    static Set<String> termSignature(Object value) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(normalizedKey(value));
        while (m.find()) {
            String t = m.group();
            if (!t.isEmpty() && !TERM_STOPWORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * Loose name match for traceability: equal after normalization, or the smaller token set (two or
     * more tokens) is contained in the larger. E.g. "Order Status" matches "Send Order Status Update".
     *
     * <p>A one-token subset never matches, because generic nouns such as "system" or "port" would match too
     * much.
     *
     * @param left first term
     * @param right second term
     * @return whether the terms match
     */
    static boolean termsMatch(String left, String right) {
        String leftKey = normalizedKey(left);
        String rightKey = normalizedKey(right);
        if (leftKey.isEmpty() || rightKey.isEmpty()) {
            return false;
        }
        if (leftKey.equals(rightKey)) {
            return true;
        }
        Set<String> leftSig = termSignature(left);
        Set<String> rightSig = termSignature(right);
        if (leftSig.isEmpty() || rightSig.isEmpty()) {
            return false;
        }
        Set<String> smaller = leftSig.size() <= rightSig.size() ? leftSig : rightSig;
        Set<String> larger = smaller == leftSig ? rightSig : leftSig;
        if (smaller.size() == 1) {
            // A singleton token subset is too broad for traceability matching and creates
            // false positives for generic nouns like "system" or "port".
            return false;
        }
        return larger.containsAll(smaller);
    }

    /**
     * Returns [missing (List), matches (Map term -> List)].
     *
     * @param expectedTerms terms that should appear
     * @param actualTerms terms that do appear
     * @return {missing terms, map of expected term to matching actual terms}; all terms whitespace-normalized
     */
    static Object[] buildTermMatches(List<String> expectedTerms, List<String> actualTerms) {
        List<String> actual = new ArrayList<>();
        for (String t : actualTerms) {
            String n = normalizedText(t);
            if (!n.isEmpty()) {
                actual.add(n);
            }
        }
        // Each expected term is matched against all actual terms; unmatched expected terms are reported missing.
        List<String> missing = new ArrayList<>();
        Map<String, Object> matches = new LinkedHashMap<>();
        for (String item : expectedTerms) {
            String term = normalizedText(item);
            if (term.isEmpty()) {
                continue;
            }
            List<String> matched = new ArrayList<>();
            for (String candidate : actual) {
                if (termsMatch(term, candidate)) {
                    matched.add(candidate);
                }
            }
            if (!matched.isEmpty()) {
                matches.put(term, matched);
            } else {
                missing.add(term);
            }
        }
        return new Object[]{missing, matches};
    }

    /**
     * Removes repeated relationships by ID; relationships without an ID are all kept, after the identified
     * ones.
     *
     * @param relationships relationship payloads
     * @return new list of copies, first occurrence of each ID kept
     */
    static List<Map<String, Object>> dedupeRelationships(List<Map<String, Object>> relationships) {
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        List<Map<String, Object>> anonymous = new ArrayList<>();
        for (Map<String, Object> relationship : relationships) {
            Map<String, Object> rel = new LinkedHashMap<>(relationship);
            String id = relationshipId(rel);
            if (id == null) {
                anonymous.add(rel);
                continue;
            }
            deduped.putIfAbsent(id, rel);
        }
        List<Map<String, Object>> out = new ArrayList<>(deduped.values());
        out.addAll(anonymous);
        return out;
    }

    /**
     * IDs of a relationship's endpoints listed under key.
     *
     * @param relationship relationship payload
     * @param key "sources", "targets" or "relatedElements"
     * @return the endpoint IDs; unresolvable entries skipped
     */
    static List<String> endpointIds(Map<String, Object> relationship, String key) {
        List<String> out = new ArrayList<>();
        for (Object value : J.list(relationship.get(key))) {
            String id = referenceId(value);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ diagram visuals

    /**
     * Shape bounds as {x, y, width, height}, or null when missing, non-numeric or degenerate.
     *
     * @param shape shape payload
     * @return {x, y, width, height}, or null
     */
    static int[] shapeBounds(Map<String, Object> shape) {
        Map<String, Object> bounds = J.map(shape.get("bounds"));
        if (bounds == null) {
            return null;
        }
        Integer x = safeInt(bounds.get("x"));
        Integer y = safeInt(bounds.get("y"));
        Integer w = safeInt(bounds.get("width"));
        Integer h = safeInt(bounds.get("height"));
        if (x == null || y == null || w == null || h == null) {
            return null;
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[]{x, y, w, h};
    }

    /**
     * Whether a shape draws a relationship. Path elements are relationships by definition; otherwise the
     * element type name is checked against {@link #RELATIONSHIP_TYPE_HINTS}.
     *
     * @param shape shape payload
     * @return true for relationship shapes
     */
    static boolean isRelationshipShape(Map<String, Object> shape) {
        String shapeType = J.lower(String.valueOf(shape.getOrDefault("shapeType", "")));
        if (shapeType.endsWith("pathelement")) {
            return true;
        }
        String elementType = J.lower(String.valueOf(shape.getOrDefault("elementType", "")));
        for (String token : RELATIONSHIP_TYPE_HINTS) {
            if (elementType.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Node shapes considered for overlap checks: bounded, not a label or path, and at least 200 square
     * pixels.
     *
     * @param shape shape payload
     * @return true for shapes that count toward overlap
     */
    static boolean isLayoutShape(Map<String, Object> shape) {
        String shapeType = J.lower(String.valueOf(shape.getOrDefault("shapeType", "")));
        if (shapeType.contains("label") || shapeType.contains("pathelement")) {
            return false;
        }
        int[] b = shapeBounds(shape);
        if (b == null) {
            return false;
        }
        return (long) b[2] * b[3] >= 200;
    }

    /**
     * Lenient base64 decoding like Python's b64decode(validate=False); empty on error.
     *
     * <p>Characters outside the base64 alphabet are dropped first.
     *
     * @param text base64 text, or null
     * @return the bytes; empty for null, empty, truncated or invalid input
     */
    static byte[] decodeBase64(String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                sb.append(c);
            }
        }
        // Non-alphabet characters (whitespace, line breaks) were dropped above; a wrong length means truncated data.
        if (sb.length() % 4 != 0) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(sb.toString());
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    /**
     * Decodes the base64 PNG from cameo_get_diagram_image and measures it: signature, real vs reported
     * size, and the bounding box and coverage ratio of non-background pixels. Image decoding failures
     * leave the zero defaults in place instead of failing the verification.
     *
     * @param diagramImage a cameo_get_diagram_image response
     * @return the image metrics
     */
    public static Map<String, Object> analyzeDiagramImage(Map<String, Object> diagramImage) {
        String imageB64 = J.text(diagramImage.get("image"));
        byte[] payload = decodeBase64(imageB64);
        boolean pngOk = payload.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(payload, PNG_SIGNATURE.length), PNG_SIGNATURE);
        Integer rw = safeInt(diagramImage.get("width"));
        Integer rh = safeInt(diagramImage.get("height"));
        Map<String, Object> result = obj(
                "byteCount", payload.length,
                "pngSignatureOk", pngOk,
                "reportedWidth", rw == null ? 0 : rw,
                "reportedHeight", rh == null ? 0 : rh,
                "imageWidth", 0,
                "imageHeight", 0,
                "contentBoundingBox", null,
                "contentCoverageRatio", 0.0);
        if (payload.length == 0) {
            return result;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(payload));
        // LinkageError covers a headless/AWT environment problem inside ImageIO; treat it as "not decodable".
        } catch (Exception | LinkageError e) {
            image = null;
        }
        if (image == null) {
            return result;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        result.put("imageWidth", w);
        result.put("imageHeight", h);

        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        // The top-left pixel is taken as the background color.
        int bg = rgb.length > 0 ? rgb[0] : 0;
        int br = (bg >> 16) & 0xff;
        int bgG = (bg >> 8) & 0xff;
        int bb = bg & 0xff;
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = -1;
        int bottom = -1;
        long covered = 0;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = rgb[row + x];
                int dr = Math.abs(((p >> 16) & 0xff) - br);
                int dg = Math.abs(((p >> 8) & 0xff) - bgG);
                int db = Math.abs((p & 0xff) - bb);
                if (dr == 0 && dg == 0 && db == 0) {
                    continue;
                }
                if (x < left) {
                    left = x;
                }
                if (x > right) {
                    right = x;
                }
                if (y < top) {
                    top = y;
                }
                if (y > bottom) {
                    bottom = y;
                }
                // Same integer luma conversion as an RGB -> L image conversion.
                int luma = (dr * 19595 + dg * 38470 + db * 7471 + 0x8000) >> 16;
                if (luma > 0) {
                    covered++;
                }
            }
        }
        if (right >= 0) {
            result.put("contentBoundingBox", obj(
                    "x", left,
                    "y", top,
                    "width", Math.max(right + 1 - left, 0),
                    "height", Math.max(bottom + 1 - top, 0)));
            long total = Math.max((long) w * h, 1L);
            result.put("contentCoverageRatio", (double) covered / (double) total);
        }
        return result;
    }

    /**
     * Shape statistics from cameo_list_diagram_shapes: counts, relationship shapes with element IDs,
     * and pairwise overlap between sibling node shapes (same parent presentation element). Overlap is
     * intersection area divided by the smaller shape's area, so full containment counts as 1.0.
     *
     * @param shapes shape payloads
     * @return counts, relationship shapes and overlap metrics
     */
    public static Map<String, Object> analyzeShapeLayout(List<Map<String, Object>> shapes) {
        List<Map<String, Object>> relationshipShapes = new ArrayList<>();
        int bounded = 0;
        List<Map<String, Object>> layoutShapes = new ArrayList<>();
        for (Map<String, Object> shape : shapes) {
            if (isRelationshipShape(shape) && truthy(shape.get("elementId"))) {
                relationshipShapes.add(shape);
            }
            if (shapeBounds(shape) != null) {
                bounded++;
            }
            if (isLayoutShape(shape)) {
                layoutShapes.add(shape);
            }
        }
        int overlapPairs = 0;
        int comparedPairs = 0;
        double maxOverlap = 0.0;
        Map<Object, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> shape : layoutShapes) {
            grouped.computeIfAbsent(shape.get("parentPresentationId"), k -> new ArrayList<>()).add(shape);
        }
        for (List<Map<String, Object>> siblings : grouped.values()) {
            for (int i = 0; i < siblings.size(); i++) {
                for (int j = i + 1; j < siblings.size(); j++) {
                    int[] l = shapeBounds(siblings.get(i));
                    int[] r = shapeBounds(siblings.get(j));
                    if (l == null || r == null) {
                        continue;
                    }
                    comparedPairs++;
                    long xo = Math.max(0, Math.min((long) l[0] + l[2], (long) r[0] + r[2]) - Math.max(l[0], r[0]));
                    long yo = Math.max(0, Math.min((long) l[1] + l[3], (long) r[1] + r[3]) - Math.max(l[1], r[1]));
                    long inter = xo * yo;
                    if (inter <= 0) {
                        continue;
                    }
                    long minArea = Math.min((long) l[2] * l[3], (long) r[2] * r[3]);
                    if (minArea <= 0) {
                        continue;
                    }
                    double ratio = (double) inter / (double) minArea;
                    overlapPairs++;
                    maxOverlap = Math.max(maxOverlap, ratio);
                }
            }
        }
        Set<String> relIds = new TreeSet<>();
        for (Map<String, Object> shape : relationshipShapes) {
            relIds.add(J.str(shape.get("elementId")));
        }
        return obj(
                "shapeCount", shapes.size(),
                "boundedShapeCount", bounded,
                "relationshipShapeCount", relationshipShapes.size(),
                "relationshipElementIds", new ArrayList<>(relIds),
                "overlapPairs", overlapPairs,
                "comparedPairs", comparedPairs,
                "maxOverlapRatio", maxOverlap);
    }

    /** Options for {@link #verifyDiagramVisual}. */
    public static final class VisualOptions {
        /** Elements that must appear on the diagram. */
        public List<String> expectedElementIds = new ArrayList<>();
        /** Relationships that must appear on the diagram. */
        public List<String> expectedRelationshipIds = new ArrayList<>();
        /** Minimum number of shapes. */
        public int minShapeCount = 0;
        /** Minimum number of relationship shapes. */
        public int minRelationshipShapeCount = 0;
        /** Minimum image width in pixels. */
        public int minWidth = 1;
        /** Minimum image height in pixels. */
        public int minHeight = 1;
        /** Minimum decoded image size in bytes. */
        public int minImageBytes = 1;
        /** Minimum share of the image covered by non-background pixels, 0.0 to 1.0. */
        public double minContentCoverageRatio = 0.0;
        /** Maximum allowed overlap ratio between sibling node shapes, 0.0 to 1.0. */
        public double maxOverlapRatio = 1.0;
    }

    /**
     * Visual sanity check of a rendered diagram; each check is independent and all must pass for ok.
     *
     * @param diagramImage a cameo_get_diagram_image response
     * @param diagramShapes a cameo_list_diagram_shapes response
     * @param o thresholds and expectations
     * @return {ok, checks, image, shapes}
     */
    public static Map<String, Object> verifyDiagramVisual(Map<String, Object> diagramImage,
                                                          Map<String, Object> diagramShapes,
                                                          VisualOptions o) {
        List<Map<String, Object>> shapes = J.maps(diagramShapes.get("shapes"));
        Map<String, Object> img = analyzeDiagramImage(diagramImage);
        Map<String, Object> sm = analyzeShapeLayout(shapes);
        Set<String> listed = new HashSet<>();
        for (Map<String, Object> s : shapes) {
            if (truthy(s.get("elementId"))) {
                listed.add(J.str(s.get("elementId")));
            }
        }
        Set<String> relIds = new HashSet<>(J.strings(sm.get("relationshipElementIds")));
        List<String> expectedElements = new ArrayList<>(o.expectedElementIds);
        List<String> expectedRelationships = new ArrayList<>(o.expectedRelationshipIds);
        List<String> missingElements = new ArrayList<>();
        for (String id : expectedElements) {
            if (!listed.contains(id)) {
                missingElements.add(id);
            }
        }
        List<String> missingRelationships = new ArrayList<>();
        for (String id : expectedRelationships) {
            if (!relIds.contains(id)) {
                missingRelationships.add(id);
            }
        }
        int iw = (Integer) img.get("imageWidth");
        int ih = (Integer) img.get("imageHeight");
        List<Object> checks = J.arr(
                check("png-signature", (Boolean) img.get("pngSignatureOk"), img),
                check("image-dimensions", iw >= o.minWidth && ih >= o.minHeight, img),
                check("image-size", (Integer) img.get("byteCount") >= o.minImageBytes, img),
                check("reported-image-dimensions",
                        img.get("reportedWidth").equals(img.get("imageWidth"))
                                && img.get("reportedHeight").equals(img.get("imageHeight")), img),
                check("content-coverage", (Double) img.get("contentCoverageRatio") >= o.minContentCoverageRatio, img),
                check("shape-count", (Integer) sm.get("shapeCount") >= o.minShapeCount, sm),
                check("relationship-shape-count", (Integer) sm.get("relationshipShapeCount") >= o.minRelationshipShapeCount, sm),
                check("expected-elements-present", missingElements.isEmpty(),
                        obj("missing", missingElements, "expected", expectedElements)),
                check("expected-relationships-present", missingRelationships.isEmpty(),
                        obj("missing", missingRelationships, "expected", expectedRelationships)),
                check("shape-overlap", (Double) sm.get("maxOverlapRatio") <= o.maxOverlapRatio, sm));
        return obj("ok", allOk(checks), "checks", checks, "image", img, "shapes", sm);
    }

    /**
     * True when every {@code {name, ok, details}} check passed.
     *
     * @param checks check maps
     * @return whether all passed; true for an empty list
     */
    static boolean allOk(List<Object> checks) {
        for (Object c : checks) {
            if (!truthy(J.get(c, "ok"))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ matrices

    /**
     * Checks a cameo_get_matrix payload: the reported counts agree with the actual lists, the matrix
     * meets density / populated-cell minimums, and the expected rows, columns and dependency names exist.
     *
     * @param matrix a cameo_get_matrix response
     * @param expectedRowIds row element IDs that must exist
     * @param expectedColumnIds column element IDs that must exist
     * @param expectedDependencyNames dependency names that must appear in populated cells
     * @param minPopulatedCellCount minimum number of populated cells
     * @param minDensity minimum populated share of all cells, 0.0 to 1.0
     * @return {ok, checks, metrics}; ok is true only when every check passed
     */
    public static Map<String, Object> verifyMatrixConsistency(Map<String, Object> matrix,
                                                              List<String> expectedRowIds,
                                                              List<String> expectedColumnIds,
                                                              List<String> expectedDependencyNames,
                                                              int minPopulatedCellCount,
                                                              double minDensity) {
        List<Map<String, Object>> rows = J.maps(matrix.get("rows"));
        List<Map<String, Object>> columns = J.maps(matrix.get("columns"));
        List<Map<String, Object>> cells = J.maps(matrix.get("populatedCells"));
        Set<String> rowIds = new HashSet<>();
        for (Map<String, Object> r : rows) {
            if (truthy(r.get("id"))) {
                rowIds.add(J.str(r.get("id")));
            }
        }
        Set<String> columnIds = new HashSet<>();
        for (Map<String, Object> c : columns) {
            if (truthy(c.get("id"))) {
                columnIds.add(J.str(c.get("id")));
            }
        }
        Set<String> dependencyNames = new TreeSet<>();
        for (Map<String, Object> cell : cells) {
            for (Map<String, Object> dep : J.maps(cell.get("dependencies"))) {
                Object name = J.or(dep.get("name"), dep.get("dependencyName"));
                if (truthy(name)) {
                    dependencyNames.add(J.str(name));
                }
            }
        }
        int rowCount = orCount(matrix.get("rowCount"), rows.size());
        int columnCount = orCount(matrix.get("columnCount"), columns.size());
        int populated = orCount(matrix.get("populatedCellCount"), cells.size());
        long total = (long) rowCount * columnCount;
        double density = total > 0 ? (double) populated / (double) total : 0.0;

        List<String> eRows = expectedRowIds == null ? new ArrayList<>() : new ArrayList<>(expectedRowIds);
        List<String> eCols = expectedColumnIds == null ? new ArrayList<>() : new ArrayList<>(expectedColumnIds);
        List<String> eDeps = expectedDependencyNames == null ? new ArrayList<>() : new ArrayList<>(expectedDependencyNames);
        List<String> missingRows = new ArrayList<>();
        for (String id : eRows) {
            if (!rowIds.contains(id)) {
                missingRows.add(id);
            }
        }
        List<String> missingCols = new ArrayList<>();
        for (String id : eCols) {
            if (!columnIds.contains(id)) {
                missingCols.add(id);
            }
        }
        List<String> missingDeps = new ArrayList<>();
        for (String d : eDeps) {
            if (!dependencyNames.contains(d)) {
                missingDeps.add(d);
            }
        }
        Map<String, Object> metrics = obj(
                "rowCount", rowCount,
                "columnCount", columnCount,
                "populatedCellCount", populated,
                "actualRowCount", rows.size(),
                "actualColumnCount", columns.size(),
                "actualPopulatedCellCount", cells.size(),
                "density", density,
                "dependencyNames", new ArrayList<>(dependencyNames));
        List<Object> checks = J.arr(
                check("payload-counts-consistent",
                        rowCount == rows.size() && columnCount == columns.size() && populated == cells.size(), metrics),
                check("populated-cell-count", populated >= minPopulatedCellCount, metrics),
                check("density", density >= minDensity, metrics),
                check("expected-rows-present", missingRows.isEmpty(), obj("missing", missingRows, "expected", eRows)),
                check("expected-columns-present", missingCols.isEmpty(), obj("missing", missingCols, "expected", eCols)),
                check("expected-dependencies-present", missingDeps.isEmpty(), obj("missing", missingDeps, "expected", eDeps)));
        return obj("ok", allOk(checks), "checks", checks, "metrics", metrics);
    }

    /** The tool's reported count, or the actual count when the report is missing or zero. */
    private static int orCount(Object reported, int actual) {
        Integer v = safeInt(reported);
        return v == null || v == 0 ? actual : v;
    }

    // ------------------------------------------------------------------ trace terms

    /**
     * Names of actions, object nodes, pins and parameters plus object/information flow item names on an
     * activity diagram.
     *
     * @param elements element payloads from the diagram
     * @param relationships relationship payloads from the diagram
     * @return the sorted unique terms
     */
    public static List<String> extractActivityTraceTerms(List<Map<String, Object>> elements,
                                                         List<Map<String, Object>> relationships) {
        Set<String> terms = new TreeSet<>();
        for (Map<String, Object> element : elements) {
            String type = elementTypeName(element);
            if (type.contains("action") || type.contains("objectnode") || type.contains("pin") || type.contains("parameter")) {
                String name = normalizedText(J.or(element.get("name"), element.get("elementName"), ""));
                if (!name.isEmpty()) {
                    terms.add(name);
                }
            }
        }
        for (Map<String, Object> rel : relationships) {
            String type = relationshipTypeName(rel);
            if (type.contains("objectflow") || type.contains("informationflow")) {
                addFlowTerms(rel, terms);
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * Names of ports, properties and interface blocks plus connector/item flow names on an internal block
     * diagram.
     *
     * @param elements element payloads from the diagram
     * @param relationships relationship payloads from the diagram
     * @return the sorted unique terms
     */
    public static List<String> extractIbdTraceTerms(List<Map<String, Object>> elements,
                                                    List<Map<String, Object>> relationships) {
        Set<String> terms = new TreeSet<>();
        for (Map<String, Object> element : elements) {
            String type = elementTypeName(element);
            if (type.contains("port") || type.contains("property") || type.contains("interfaceblock") || type.contains("interface block")) {
                String name = normalizedText(J.or(element.get("name"), element.get("elementName"), ""));
                if (!name.isEmpty()) {
                    terms.add(name);
                }
            }
        }
        for (Map<String, Object> rel : relationships) {
            String type = relationshipTypeName(rel);
            if (type.contains("connector") || type.contains("informationflow") || type.contains("itemflow")) {
                addFlowTerms(rel, terms);
            }
        }
        return new ArrayList<>(terms);
    }

    /** Adds a flow's own name, its item property name and the names of conveyed classifiers. */
    private static void addFlowTerms(Map<String, Object> rel, Set<String> terms) {
        String name = normalizedText(J.or(rel.get("name"), ""));
        if (!name.isEmpty()) {
            terms.add(name);
        }
        String item = referenceName(rel.get("itemProperty"));
        if (!item.isEmpty()) {
            terms.add(item);
        }
        for (Object conveyed : J.list(rel.get("conveyed"))) {
            String c = referenceName(conveyed);
            if (!c.isEmpty()) {
                terms.add(c);
            }
        }
    }

    // ------------------------------------------------------------------ activity flow

    /**
     * Graph checks over an activity diagram: initial and final nodes exist, every action is connected,
     * all actions are reachable from an initial node, a final node is reachable, the control/object
     * flows form a single connected component, and swimlane partitions are not nested too deep,
     * labelled like stereotypes or used only as containers of other partitions.
     *
     * @param elements element payloads from the diagram
     * @param relationships relationship payloads
     * @param shapes shape payloads, used for partition nesting
     * @param maxPartitionDepth deepest allowed partition nesting
     * @param allowStereotypePartitionLabels accept partition labels that look like stereotype notation, such
     *     as «allocate»
     * @return {ok, checks, metrics}; ok is true only when every check passed
     */
    public static Map<String, Object> verifyActivityFlowSemantics(List<Map<String, Object>> elements,
                                                                  List<Map<String, Object>> relationships,
                                                                  List<Map<String, Object>> shapes,
                                                                  int maxPartitionDepth,
                                                                  boolean allowStereotypePartitionLabels) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> e : elements) {
            String id = elementId(e);
            if (id != null) {
                byId.put(id, new LinkedHashMap<>(e));
            }
        }
        List<Map<String, Object>> flows = new ArrayList<>();
        for (Map<String, Object> rel : dedupeRelationships(relationships)) {
            String t = relationshipTypeName(rel);
            if (t.contains("controlflow") || t.contains("objectflow")) {
                flows.add(rel);
            }
        }
        // Node kinds are classified by substring on the type name, so e.g. "ActivityFinalNode" and
        // "FlowFinalNode" both count as final and "CallBehaviorAction" counts as an action.
        Set<String> initialIds = new TreeSet<>();
        Set<String> finalIds = new TreeSet<>();
        Set<String> actionIds = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> e : byId.entrySet()) {
            String t = elementTypeName(e.getValue());
            if (t.contains("initial")) {
                initialIds.add(e.getKey());
            }
            if (t.contains("final")) {
                finalIds.add(e.getKey());
            }
            if (t.contains("action")) {
                actionIds.add(e.getKey());
            }
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        Set<String> touched = new LinkedHashSet<>();
        for (Map<String, Object> rel : flows) {
            List<String> sources = endpointIds(rel, "sources");
            List<String> targets = endpointIds(rel, "targets");
            if (sources.isEmpty() || targets.isEmpty()) {
                continue;
            }
            for (String s : sources) {
                for (String t : targets) {
                    adjacency.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(t);
                    reverse.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(s);
                    touched.add(s);
                    touched.add(t);
                }
            }
        }
        List<String> isolated = new ArrayList<>();
        for (String a : actionIds) {
            if (isEmpty(adjacency.get(a)) && isEmpty(reverse.get(a))) {
                isolated.add(a);
            }
        }
        // Breadth-first search over directed flows from all initial nodes at once.
        Set<String> reachable = new HashSet<>();
        if (!initialIds.isEmpty()) {
            Deque<String> queue = new ArrayDeque<>(initialIds);
            reachable.addAll(initialIds);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                for (String n : adjacency.getOrDefault(cur, new LinkedHashSet<>())) {
                    if (reachable.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        List<String> unreachable = new ArrayList<>();
        for (String a : actionIds) {
            if (initialIds.isEmpty() || !reachable.contains(a)) {
                unreachable.add(a);
            }
        }
        List<String> reachableFinals = new ArrayList<>();
        for (String f : finalIds) {
            if (reachable.contains(f)) {
                reachableFinals.add(f);
            }
        }
        Set<String> componentNodes = new LinkedHashSet<>(touched);
        componentNodes.addAll(initialIds);
        componentNodes.addAll(finalIds);
        componentNodes.addAll(actionIds);
        // Connected components ignore direction; a diagram split into several islands usually means a missing flow.
        Map<String, Set<String>> undirected = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : adjacency.entrySet()) {
            for (String t : e.getValue()) {
                undirected.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(t);
                undirected.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        List<Integer> sizes = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(componentNodes);
        while (!remaining.isEmpty()) {
            String seed = remaining.iterator().next();
            remaining.remove(seed);
            Deque<String> queue = new ArrayDeque<>();
            queue.add(seed);
            int size = 1;
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                for (String n : undirected.getOrDefault(cur, new LinkedHashSet<>())) {
                    if (!remaining.contains(n)) {
                        continue;
                    }
                    remaining.remove(n);
                    size++;
                    queue.add(n);
                }
            }
            sizes.add(size);
        }
        sizes.sort((a, b) -> Integer.compare(b, a));

        // Partition checks only run when shapes are supplied (nesting is a presentation property).
        int partitionCount = 0;
        int maxDepth = 0;
        List<String> stereotypeNames = new ArrayList<>();
        List<String> containerOnly = new ArrayList<>();
        boolean partitionOk = true;
        if (shapes != null && !shapes.isEmpty()) {
            List<Map<String, Object>> partitions = new ArrayList<>();
            for (Map<String, Object> s : shapes) {
                if (elementTypeName(s).contains("activitypartition")) {
                    partitions.add(s);
                }
            }
            partitionCount = partitions.size();
            Map<String, Map<String, Object>> byPresentation = new LinkedHashMap<>();
            for (Map<String, Object> s : partitions) {
                if (truthy(s.get("presentationId"))) {
                    byPresentation.put(J.str(s.get("presentationId")), s);
                }
            }
            Map<String, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
            for (Map<String, Object> s : shapes) {
                Object parent = s.get("parentPresentationId");
                String key = parent instanceof String ? (String) parent : null;
                byParent.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
            for (Map<String, Object> partition : partitions) {
                int depth = 0;
                Object parent = partition.get("parentPresentationId");
                // guard bounds the walk in case of a cycle in the reported parent links.
                int guard = byPresentation.size() + 1;
                while (parent instanceof String && byPresentation.containsKey(parent) && guard-- > 0) {
                    depth++;
                    parent = byPresentation.get(parent).get("parentPresentationId");
                }
                maxDepth = Math.max(maxDepth, depth);
                Map<String, Object> el = byId.get(J.str(partition.get("elementId")));
                String label = normalizedText(J.or(partition.get("elementName"),
                        el == null ? null : el.get("name"), ""));
                // A label wrapped in guillemets is the stereotype name displayed instead of a real partition name.
                if (!allowStereotypePartitionLabels && label.startsWith("«") && label.endsWith("»")) {
                    stereotypeNames.add(label);
                }
                Object pid = partition.get("presentationId");
                List<Map<String, Object>> children = byParent.getOrDefault(pid instanceof String ? (String) pid : null,
                        new ArrayList<>());
                // Non-string presentation IDs cannot be keys of byParent; treat such a partition as childless.
                if (pid != null && !(pid instanceof String)) {
                    children = new ArrayList<>();
                }
                if (!children.isEmpty()) {
                    int childPartitions = 0;
                    int childBehaviors = 0;
                    for (Map<String, Object> c : children) {
                        if (elementTypeName(c).contains("activitypartition")) {
                            childPartitions++;
                        } else {
                            childBehaviors++;
                        }
                    }
                    // A partition holding only sub-partitions and no actions adds a nesting level without meaning.
                    if (childPartitions > 0 && childBehaviors == 0) {
                        containerOnly.add(normalizedText(J.or(label, J.text(partition.get("elementId")))));
                    }
                }
            }
            partitionOk = maxDepth <= maxPartitionDepth && stereotypeNames.isEmpty() && containerOnly.isEmpty();
        }
        Map<String, Object> partitionDetails = obj(
                "count", partitionCount,
                "maxDepth", maxDepth,
                "stereotypeStyleNames", stereotypeNames,
                "containerOnlyPartitions", containerOnly);
        Map<String, Object> metrics = obj(
                "elementCount", byId.size(),
                "flowRelationshipCount", flows.size(),
                "initialNodeIds", new ArrayList<>(initialIds),
                "finalNodeIds", new ArrayList<>(finalIds),
                "actionIds", new ArrayList<>(actionIds),
                "isolatedActionIds", isolated,
                "unreachableActionIds", unreachable,
                "reachableFinalIds", reachableFinals,
                "flowComponentCount", sizes.size(),
                "flowComponentSizes", sizes,
                "partitionDetails", partitionDetails);
        List<Object> checks = J.arr(
                check("initial-node-present", !initialIds.isEmpty(), metrics),
                check("final-node-present", !finalIds.isEmpty(), metrics),
                check("actions-connected", isolated.isEmpty(), metrics),
                check("actions-reachable-from-initial", !initialIds.isEmpty() && unreachable.isEmpty(), metrics),
                check("final-reachable", !reachableFinals.isEmpty(), metrics),
                check("single-flow-component", sizes.size() <= 1, metrics),
                check("partition-sanity", partitionOk, metrics));
        return obj("ok", allOk(checks), "checks", checks, "metrics", metrics);
    }

    /**
     * Null-safe emptiness test.
     *
     * @param c a collection, or null
     * @return true for null or empty
     */
    private static boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    // ------------------------------------------------------------------ ports

    /**
     * Checks interface blocks for port compatibility: every flow property is owned by a listed interface,
     * named and has a direction; a property name is not reused across interfaces (unless allowed); and
     * the same name in one interface does not carry conflicting directions.
     *
     * @param interfaceBlocks interface block payloads
     * @param flowProperties flow property payloads
     * @param allowSharedFlowPropertyNames names allowed on more than one interface, or null
     * @return {ok, checks, metrics}; ok is true only when every check passed
     */
    public static Map<String, Object> verifyPortBoundaryConsistency(List<Map<String, Object>> interfaceBlocks,
                                                                    List<Map<String, Object>> flowProperties,
                                                                    List<String> allowSharedFlowPropertyNames) {
        Map<String, String> interfaceNames = new LinkedHashMap<>();
        for (Map<String, Object> i : interfaceBlocks) {
            if (truthy(i.get("id"))) {
                interfaceNames.put(J.text(i.get("id")), normalizedText(J.or(i.get("name"), "")));
            }
        }
        Map<String, String> scopeLabels = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : interfaceNames.entrySet()) {
            String k = normalizedKey(e.getKey());
            if (!k.isEmpty()) {
                scopeLabels.put(k, e.getValue());
            }
        }
        Set<String> allowed = new HashSet<>();
        if (allowSharedFlowPropertyNames != null) {
            for (String n : allowSharedFlowPropertyNames) {
                String k = normalizedKey(n);
                if (!k.isEmpty()) {
                    allowed.add(k);
                }
            }
        }
        Map<String, List<String>> duplicateMap = new LinkedHashMap<>();
        Map<List<String>, Set<String>> directionMap = new LinkedHashMap<>();
        List<String> unnamed = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        List<String> missingDirection = new ArrayList<>();
        for (Map<String, Object> fp : flowProperties) {
            String fpId = elementId(fp);
            if (fpId == null) {
                fpId = "";
            }
            String ownerId = J.text(fp.get("ownerId"));
            if (!interfaceNames.containsKey(ownerId)) {
                orphans.add(fpId);
            }
            String name = normalizedText(J.or(fp.get("name"), ""));
            if (name.isEmpty()) {
                unnamed.add(fpId);
                continue;
            }
            String nk = J.lower(name);
            duplicateMap.computeIfAbsent(nk, k -> new ArrayList<>()).add(ownerId);
            String direction = flowPropertyDirection(fp);
            if (direction != null) {
                directionMap.computeIfAbsent(Arrays.asList(flowPropertyScopeKey(fp), nk), k -> new TreeSet<>()).add(direction);
            } else {
                missingDirection.add(fpId);
            }
        }
        Map<String, Object> duplicates = new LinkedHashMap<>();
        // Same keys as duplicates, but with owner ids: names can repeat across interface blocks.
        Map<String, Object> duplicateOwnerIds = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : duplicateMap.entrySet()) {
            if (new HashSet<>(e.getValue()).size() > 1 && !allowed.contains(e.getKey())) {
                Set<String> owners = new TreeSet<>();
                Set<String> ownerIds = new TreeSet<>();
                for (String owner : e.getValue()) {
                    if (!owner.isEmpty()) {
                        String label = interfaceNames.get(owner);
                        owners.add(label == null || label.isEmpty() ? owner : label);
                        ownerIds.add(owner);
                    }
                }
                duplicates.put(e.getKey(), new ArrayList<>(owners));
                duplicateOwnerIds.put(e.getKey(), new ArrayList<>(ownerIds));
            }
        }
        Map<String, Object> conflicts = new LinkedHashMap<>();
        for (Map.Entry<List<String>, Set<String>> e : directionMap.entrySet()) {
            if (e.getValue().size() <= 1) {
                continue;
            }
            String scopeKey = e.getKey().get(0);
            String label = scopeLabels.get(scopeKey);
            String scopeName = label == null || label.isEmpty() ? scopeKey : label;
            Map<String, Object> inner = J.map(conflicts.get(scopeName));
            if (inner == null) {
                inner = new LinkedHashMap<>();
                conflicts.put(scopeName, inner);
            }
            inner.put(e.getKey().get(1), new ArrayList<>(e.getValue()));
        }
        Map<String, Object> metrics = obj(
                "interfaceCount", interfaceBlocks.size(),
                "flowPropertyCount", flowProperties.size(),
                "duplicateFlowProperties", duplicates,
                "duplicateFlowPropertyOwnerIds", duplicateOwnerIds,
                "directionConflicts", conflicts,
                "unnamedPropertyIds", J.sortedNonEmptyList(unnamed),
                "orphanPropertyIds", J.sortedNonEmptyList(orphans),
                "missingDirectionIds", J.sortedNonEmptyList(missingDirection));
        List<Object> checks = J.arr(
                check("interfaces-present", !interfaceBlocks.isEmpty(), metrics),
                check("flow-properties-present", !flowProperties.isEmpty(), metrics),
                check("owner-consistency", J.list(metrics.get("orphanPropertyIds")).isEmpty(), metrics),
                check("duplicate-flow-properties", duplicates.isEmpty(), metrics),
                check("direction-conflicts", conflicts.isEmpty(), metrics),
                check("named-flow-properties", J.list(metrics.get("unnamedPropertyIds")).isEmpty(), metrics),
                check("flow-property-directions-present", J.list(metrics.get("missingDirectionIds")).isEmpty(), metrics));
        return obj("ok", allOk(checks), "checks", checks, "metrics", metrics);
    }

    // ------------------------------------------------------------------ requirements

    /**
     * Grades requirement text: an ID is present (optional), the text is not blank, and when
     * requireMeasurement is set the text has a shall/must/will directive, a measurement cue and at
     * least minTextLength characters.
     *
     * @param requirements merged requirement payloads
     * @param requireId fail requirements without an ID
     * @param requireMeasurement apply the directive, measurement and length checks
     * @param minTextLength minimum text length when requireMeasurement is set
     * @return {ok, checks, metrics}; ok is true only when every check passed
     */
    public static Map<String, Object> verifyRequirementQuality(List<Map<String, Object>> requirements,
                                                               boolean requireId,
                                                               boolean requireMeasurement,
                                                               int minTextLength) {
        List<String> missingId = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        List<Object> evaluated = new ArrayList<>();
        for (Map<String, Object> req : requirements) {
            String id = elementId(req);
            if (id == null) {
                id = "";
            }
            String reqId = extractRequirementId(req);
            String text = extractRequirementText(req);
            boolean measurable = DIRECTIVE.matcher(text).find() && MEASUREMENT.matcher(text).find();
            boolean longEnough = text.length() >= minTextLength;
            if (requireId && reqId.isEmpty()) {
                missingId.add(id);
            }
            if (text.isEmpty()) {
                blank.add(id);
            } else if (requireMeasurement && !(measurable && longEnough)) {
                weak.add(id);
            }
            evaluated.add(obj(
                    "elementId", id,
                    "name", normalizedText(J.or(req.get("name"), "")),
                    "requirementId", reqId,
                    "text", text,
                    "hasId", !reqId.isEmpty(),
                    "hasText", !text.isEmpty(),
                    "isMeasurable", measurable && longEnough));
        }
        Map<String, Object> metrics = obj(
                "requirementCount", requirements.size(),
                "missingIdIds", J.sortedNonEmptyList(missingId),
                "blankTextIds", J.sortedNonEmptyList(blank),
                "weakTextIds", J.sortedNonEmptyList(weak),
                "evaluatedRequirements", evaluated);
        List<Object> checks = J.arr(
                check("requirements-present", !requirements.isEmpty(), metrics),
                check("requirement-ids-present", J.list(metrics.get("missingIdIds")).isEmpty(), metrics),
                check("requirement-text-present", J.list(metrics.get("blankTextIds")).isEmpty(), metrics),
                check("requirement-text-measurable", J.list(metrics.get("weakTextIds")).isEmpty(), metrics));
        return obj("ok", allOk(checks), "checks", checks, "metrics", metrics);
    }

    // ------------------------------------------------------------------ traceability

    /**
     * Checks that port and IBD terms appear among the activity terms and that each requirement is
     * linked to at least one of the given architecture elements. Only pairings with inputs are checked;
     * with no usable inputs a failing "traceability-inputs-present" check is returned.
     *
     * @param activityTerms terms from the activity diagram, or null
     * @param portTerms flow property names, or null
     * @param ibdTerms terms from the internal block diagram, or null
     * @param requirementLinks requirement ID to the IDs of elements related to it
     * @param requirementIds requirements to check, or null
     * @param architectureElementIds elements each requirement should link to, or null
     * @return {ok, checks, metrics}; ok is true only when every check passed
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> verifyCrossDiagramTraceability(List<String> activityTerms,
                                                                     List<String> portTerms,
                                                                     List<String> ibdTerms,
                                                                     Map<String, Object> requirementLinks,
                                                                     List<String> requirementIds,
                                                                     List<String> architectureElementIds) {
        List<String> activity = normalizedTerms(activityTerms);
        List<String> ports = normalizedTerms(portTerms);
        List<String> ibd = normalizedTerms(ibdTerms);
        List<String> reqIds = requirementIds == null ? new ArrayList<>() : new ArrayList<>(requirementIds);
        Set<String> archIds = architectureElementIds == null ? new HashSet<>() : new HashSet<>(architectureElementIds);
        Map<String, List<String>> links = new LinkedHashMap<>();
        if (requirementLinks != null) {
            for (Map.Entry<String, Object> e : requirementLinks.entrySet()) {
                links.put(e.getKey(), J.strings(e.getValue()));
            }
        }
        Object[] portMatch = buildTermMatches(ports, activity);
        Object[] ibdMatch = buildTermMatches(ibd, activity);
        List<String> missingTraces = new ArrayList<>();
        for (String r : reqIds) {
            if (archIds.isEmpty()) {
                continue;
            }
            boolean linked = false;
            for (String target : links.getOrDefault(r, new ArrayList<>())) {
                if (archIds.contains(target)) {
                    linked = true;
                    break;
                }
            }
            if (!linked) {
                missingTraces.add(r);
            }
        }
        java.util.Collections.sort(missingTraces);
        List<String> missingPorts = (List<String>) portMatch[0];
        List<String> missingIbd = (List<String>) ibdMatch[0];
        Map<String, Object> metrics = obj(
                "activityTermCount", activity.size(),
                "portTermCount", ports.size(),
                "ibdTermCount", ibd.size(),
                "matchedPortTerms", portMatch[1],
                "matchedIbdTerms", ibdMatch[1],
                "missingPortTerms", missingPorts,
                "missingIbdTerms", missingIbd,
                "missingRequirementTraceIds", missingTraces);
        List<Object> checks = new ArrayList<>();
        if (!ports.isEmpty()) {
            checks.add(check("activity-to-port-coverage", missingPorts.isEmpty(), metrics));
        }
        if (!ibd.isEmpty()) {
            checks.add(check("activity-to-ibd-coverage", missingIbd.isEmpty(), metrics));
        }
        if (!reqIds.isEmpty() && !archIds.isEmpty()) {
            checks.add(check("requirements-to-architecture-trace", missingTraces.isEmpty(), metrics));
        }
        if (checks.isEmpty()) {
            checks.add(check("traceability-inputs-present", false,
                    obj("message", "Provide at least one artifact pairing to validate.")));
        }
        return obj("ok", allOk(checks), "checks", checks, "metrics", metrics);
    }

    /**
     * Whitespace-normalized, non-empty terms.
     *
     * @param terms terms, or null
     * @return a new list
     */
    private static List<String> normalizedTerms(List<String> terms) {
        List<String> out = new ArrayList<>();
        if (terms == null) {
            return out;
        }
        for (String t : terms) {
            String n = normalizedText(t);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }
}
