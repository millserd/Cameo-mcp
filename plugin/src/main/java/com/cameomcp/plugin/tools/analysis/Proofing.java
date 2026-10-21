package com.cameomcp.plugin.tools.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static com.cameomcp.plugin.tools.analysis.J.truthy;

/**
 * Proofing for spelling, style and naming of requirement text, comments, state and transition
 * names and diagram labels. Analysis is read-only and returns findings plus a previewable patch
 * plan; {@link #applyPatchPlan} applies a plan through the {@link Bridge}.
 */
public final class Proofing {

    /** A word: a letter followed by letters or apostrophes. */
    static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z']*");
    /** Splits camel and Pascal case identifiers and acronyms: "HTTPServerPort" gives HTTP, Server, Port. */
    static final Pattern IDENTIFIER = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?=[A-Z][a-z]|\\b)|[0-9]+");
    /** Whitespace run (Unicode-aware). */
    static final Pattern MULTISPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    /** Run of non-alphanumeric characters, used to split text for re-casing. */
    static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9]+");
    /** Leading or trailing whitespace, punctuation or underscores. */
    static final Pattern LEADING_TRAILING_PUNCT = Pattern.compile("^[\\s\\W_]+|[\\s\\W_]+$", Pattern.UNICODE_CHARACTER_CLASS);
    /** Placeholder markers: todo, fixme, tbd, placeholder or xxx, case-insensitive. */
    static final Pattern TODO = Pattern.compile("\\b(todo|fixme|tbd|placeholder|xxx)\\b", Pattern.CASE_INSENSITIVE);
    /** A character repeated three times, such as "xxx"; such words are not spell checked. */
    static final Pattern TRIPLE = Pattern.compile("(.)\\1\\1");

    /**
     * Acronyms and product names with a fixed spelling, keyed by lower case; applied by every casing helper.
     */
    static final Map<String, String> PREFERRED_CASE = new HashMap<>();

    static {
        String[][] pairs = {
                {"api", "API"}, {"ap", "AP"}, {"bdd", "BDD"}, {"cim", "CIM"}, {"catia", "CATIA"}, {"cameo", "Cameo"},
                {"gui", "GUI"}, {"id", "ID"}, {"ibd", "IBD"}, {"mcp", "MCP"}, {"oosem", "OOSEM"}, {"pdf", "PDF"},
                {"pim", "PIM"}, {"ppt", "PPT"}, {"sc", "SC"}, {"sql", "SQL"}, {"sysml", "SysML"}, {"ui", "UI"},
        };
        for (String[] p : pairs) {
            PREFERRED_CASE.put(p[0], p[1]);
        }
    }

    /** Short function words that are never flagged, whatever the lexicon contains. */
    static final Set<String> ALWAYS_ALLOWED = new HashSet<>(Arrays.asList(
            "a", "an", "and", "be", "by", "for", "from", "in", "is", "of", "on", "or", "the", "to", "with"));

    /**
     * Fields holding element names: proofed with title or Pascal case and patched by renaming the element.
     */
    private static final Set<String> NAME_FIELDS = new HashSet<>(Arrays.asList("name", "label", "elementName", "title"));

    /** Lexicon words, built lazily on first use by {@link #lexicon}; null until then. */
    private static volatile Set<String> lexiconSet;
    /** Sorted copy of the lexicon, used for close-match suggestions. */
    private static volatile List<String> lexiconSorted;

    /** Static methods only. */
    private Proofing() {
    }

    // ------------------------------------------------------------------ finding

    /** One proofing finding; serialized with snake_case keys. */
    static final class Finding {
        /** Finding category, such as "requirements" or "comments". */
        final String category;
        /** "high" or "medium". */
        final String severity;
        /** Kind of the proofed item, such as its element type. */
        final String artifactType;
        /** ID of the proofed item. */
        final String artifactId;
        /** Proofed field, such as "name" or "documentation". */
        final String field;
        /** Human-readable reason. */
        final String message;
        /** The text as found. */
        final String currentText;
        /** The proposed replacement text. */
        final String suggestedText;
        /** Spelling suggestions. */
        final List<String> suggestions;
        /** Confidence, 0.0 to 1.0. */
        final double confidence;
        /** Supporting data. */
        final Map<String, Object> evidence;

        /**
         * Creates a finding; see the fields for the meaning of each argument.
         *
         * @param category finding category
         * @param severity "high" or "medium"
         * @param artifactType kind of the proofed item
         * @param artifactId ID of the proofed item
         * @param field proofed field
         * @param message human-readable reason
         * @param currentText the text as found
         * @param suggestedText the proposed replacement
         * @param suggestions spelling suggestions
         * @param confidence 0.0 to 1.0
         * @param evidence supporting data, or null
         */
        Finding(String category, String severity, String artifactType, String artifactId, String field, String message,
                String currentText, String suggestedText, List<String> suggestions, double confidence,
                Map<String, Object> evidence) {
            this.category = category;
            this.severity = severity;
            this.artifactType = artifactType;
            this.artifactId = artifactId;
            this.field = field;
            this.message = message;
            this.currentText = currentText;
            this.suggestedText = suggestedText;
            this.suggestions = suggestions;
            this.confidence = confidence;
            this.evidence = evidence;
        }

        /**
         * Serializes the finding with snake_case keys.
         *
         * @return a new map
         */
        Map<String, Object> toMap() {
            return obj(
                    "category", category,
                    "severity", severity,
                    "artifact_type", artifactType,
                    "artifact_id", artifactId,
                    "field", field,
                    "message", message,
                    "current_text", currentText,
                    "suggested_text", suggestedText,
                    "suggestions", new ArrayList<>(suggestions),
                    "confidence", confidence,
                    "evidence", Json(evidence));
        }

        /**
         * Reads a finding from a map. Accepts both the camelCase and snake_case spellings so plans can
         * round-trip through callers. Missing fields get defaults (category "proofing", severity "medium").
         *
         * @param f finding map
         * @return the finding
         */
        static Finding fromMap(Map<String, Object> f) {
            Object raw = f.get("suggestions");
            List<String> suggestions = new ArrayList<>();
            if (raw instanceof String) {
                suggestions.add((String) raw);
            } else {
                for (Object s : J.list(raw)) {
                    suggestions.add(J.str(s));
                }
            }
            return new Finding(
                    J.str(J.or(f.get("category"), "proofing")),
                    J.str(J.or(f.get("severity"), "medium")),
                    J.str(J.or(f.get("artifactType"), f.get("artifact_type"), "")),
                    J.str(J.or(f.get("artifactId"), f.get("artifact_id"), "")),
                    J.str(J.or(f.get("field"), "")),
                    J.str(J.or(f.get("message"), "")),
                    J.str(J.or(f.get("currentText"), f.get("current_text"), "")),
                    J.str(J.or(f.get("suggestedText"), f.get("suggested_text"), "")),
                    suggestions,
                    J.toDouble(J.or(f.get("confidence"), 0.0), 0.0),
                    J.copy(f.get("evidence")));
        }
    }

    /** Defensive copy of a finding's evidence map (null becomes empty). */
    private static Map<String, Object> Json(Map<String, Object> m) {
        return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }

    /** Common report envelope shared by every proofing section. */
    private static Map<String, Object> report(String name, boolean ok, String summary, List<Object> checks,
                                              Map<String, Object> metrics, List<Finding> findings,
                                              Map<String, Object> patchPlan, Map<String, Object> semanticBaseline,
                                              Map<String, Object> sections) {
        List<Object> f = new ArrayList<>();
        for (Finding finding : findings) {
            f.add(finding.toMap());
        }
        return obj(
                "name", name,
                "ok", ok,
                "summary", summary,
                "checks", checks,
                "metrics", metrics,
                "findings", f,
                "patch_plan", patchPlan,
                "semantic_baseline", semanticBaseline == null ? new LinkedHashMap<>() : semanticBaseline,
                "sections", sections);
    }

    // ------------------------------------------------------------------ text utilities

    /**
     * Collapses whitespace to single spaces (Unicode-aware) and trims.
     *
     * @param value any value
     * @return the normalized text
     */
    static String normalizedText(Object value) {
        return MULTISPACE.matcher(J.text(value).trim()).replaceAll(" ");
    }

    /**
     * First non-empty identifier of a text item, or "".
     *
     * @param item text item
     * @return the ID
     */
    static String extractId(Map<String, Object> item) {
        for (String key : new String[]{"id", "elementId", "artifactId", "presentationId", "commentId"}) {
            Object v = item.get(key);
            if (truthy(v)) {
                return J.str(v);
            }
        }
        return "";
    }

    /**
     * First non-empty kind/type descriptor of a text item, or {@code fallback}.
     *
     * @param item text item
     * @param fallback value when no descriptor is set
     * @return the descriptor
     */
    static String extractKind(Map<String, Object> item, String fallback) {
        for (String key : new String[]{"artifactType", "kind", "type", "humanType", "elementType", "role"}) {
            Object v = item.get(key);
            if (truthy(v)) {
                return J.str(v);
            }
        }
        return fallback;
    }

    /**
     * The proofable (field, text) pairs of an item. The field order depends on the role: requirement and
     * comment bodies come first, names first for states and transitions. Only non-blank string fields are
     * used; with none, a generic "text" field built from name, label, text or body is the fallback.
     *
     * @param item text item
     * @param role requirement, comment, state, transition or diagram
     * @return {field, normalized text} pairs
     */
    static List<String[]> extractTextFields(Map<String, Object> item, String role) {
        String roleKey = J.lower(role);
        String[] keys;
        if (roleKey.equals("requirement")) {
            keys = new String[]{"text", "documentation", "body", "comment", "name", "label", "elementName", "title"};
        } else if (roleKey.equals("comment")) {
            keys = new String[]{"body", "comment", "text", "documentation", "name", "label", "elementName"};
        } else if (roleKey.equals("state") || roleKey.equals("transition")) {
            keys = new String[]{"name", "elementName", "label", "text", "body", "comment"};
        } else {
            keys = new String[]{"label", "name", "elementName", "text", "body", "comment", "documentation", "title"};
        }
        List<String[]> out = new ArrayList<>();
        for (String key : keys) {
            Object v = item.get(key);
            if (v instanceof String && !normalizedText(v).isEmpty()) {
                out.add(new String[]{key, normalizedText(v)});
            }
        }
        if (out.isEmpty()) {
            String fallback = normalizedText(J.or(item.get("name"), item.get("label"), item.get("text"), item.get("body")));
            if (!fallback.isEmpty()) {
                out.add(new String[]{"text", fallback});
            }
        }
        return out;
    }

    /**
     * Copy of a requirement payload whose missing requirementId is filled from its element ID, so the
     * semantic baseline does not flag every item.
     *
     * @param item requirement payload
     * @return a new map
     */
    static Map<String, Object> normalizeRequirementPayload(Map<String, Object> item) {
        Map<String, Object> payload = new LinkedHashMap<>(item);
        if (!truthy(payload.get("requirementId")) && truthy(payload.get("id"))) {
            payload.put("requirementId", payload.get("id"));
        }
        return payload;
    }

    /**
     * Whether a string has an upper-case character.
     *
     * @param s a string
     * @return whether s has an upper-case character
     */
    static boolean hasUpper(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a string has a lower-case character.
     *
     * @param s a string
     * @return whether s has a lower-case character
     */
    static boolean hasLower(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLowerCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Python {@code str.isupper()}: has cased characters and none of them are lower case.
     *
     * @param s a string
     * @return the Python isupper() result
     */
    static boolean isUpper(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                cased = true;
            }
        }
        return cased;
    }

    /**
     * All matches of a pattern.
     *
     * @param p the pattern
     * @param text text to search
     * @return the matched substrings, in order
     */
    static List<String> findAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * Words of a text for spell checking. Mixed-case identifiers ("BrakeController") are split into their
     * parts.
     *
     * @param text text to split
     * @return the words
     */
    static List<String> iterWords(String text) {
        List<String> words = new ArrayList<>();
        for (String part : findAll(WORD, text)) {
            if (hasUpper(part.substring(1)) && hasLower(part)) {
                for (String token : findAll(IDENTIFIER, part)) {
                    if (!token.isEmpty()) {
                        words.add(token);
                    }
                }
            } else {
                words.add(part);
            }
        }
        return words;
    }

    /**
     * The spell-check lexicon: {@link ProofLexicon} words of three or more letters, plus {@link
     * #ALWAYS_ALLOWED} and the {@link #PREFERRED_CASE} keys. Built once per JVM with double-checked locking
     * over volatile fields.
     *
     * @return the unmodifiable lower-case word set
     */
    static Set<String> lexicon() {
        Set<String> set = lexiconSet;
        if (set == null) {
            synchronized (Proofing.class) {
                if (lexiconSet == null) {
                    Set<String> words = new HashSet<>(ALWAYS_ALLOWED);
                    words.addAll(PREFERRED_CASE.keySet());
                    for (String w : ProofLexicon.words().split(" ")) {
                        if (w.length() >= 3) {
                            words.add(w);
                        }
                    }
                    lexiconSorted = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(words)));
                    lexiconSet = Collections.unmodifiableSet(words);
                }
                set = lexiconSet;
            }
        }
        return set;
    }

    /**
     * Casing for one token: known acronyms from PREFERRED_CASE, short all-caps tokens and tokens with
     * inner capitals are kept as written, anything else is capitalized.
     *
     * @param token one word
     * @return the re-cased token
     */
    static String preferredCase(String token) {
        String lowered = J.lower(token);
        if (PREFERRED_CASE.containsKey(lowered)) {
            return PREFERRED_CASE.get(lowered);
        }
        if (isUpper(token) && token.length() <= 6) {
            return token;
        }
        if (token.length() > 1 && hasUpper(token.substring(1))) {
            return token;
        }
        return token.isEmpty() ? token : token.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + token.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Splits text on non-alphanumerics and camel-case boundaries for re-casing.
     *
     * @param text text to split
     * @return the chunks
     */
    static List<String> splitForCasing(String text) {
        List<String> chunks = new ArrayList<>();
        for (String part : TOKEN_SPLIT.split(normalizedText(text))) {
            if (part.isEmpty()) {
                continue;
            }
            List<String> sub = findAll(IDENTIFIER, part);
            if (sub.isEmpty()) {
                chunks.add(part);
            } else {
                chunks.addAll(sub);
            }
        }
        return chunks;
    }

    /**
     * "brake controller" -> "Brake Controller", with acronyms fixed.
     *
     * @param text text to re-case
     * @return the title-cased text
     */
    static String titleCase(String text) {
        List<String> pieces = new ArrayList<>();
        for (String w : splitForCasing(text)) {
            if (!w.isEmpty()) {
                pieces.add(preferredCase(w));
            }
        }
        return String.join(" ", pieces);
    }

    /**
     * First word capitalized, the rest lower-cased (acronyms excepted), with a trailing period added if
     * missing.
     *
     * @param text text to re-case
     * @return the sentence, or "" for blank text
     */
    static String sentenceCase(String text) {
        String normalized = normalizedText(text);
        if (normalized.isEmpty()) {
            return normalized;
        }
        String[] words = normalized.split(" ", -1);
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) {
                continue;
            }
            String lw = J.lower(word);
            if (PREFERRED_CASE.containsKey(lw)) {
                pieces.add(PREFERRED_CASE.get(lw));
                continue;
            }
            pieces.add(i == 0 ? preferredCase(word) : word.toLowerCase(java.util.Locale.ROOT));
        }
        String sentence = String.join(" ", pieces);
        if (!sentence.isEmpty() && ".!?".indexOf(sentence.charAt(sentence.length() - 1)) < 0) {
            sentence += ".";
        }
        return sentence;
    }

    /**
     * "start engine" -> "StartEngine" (used for transition names), with acronyms fixed.
     *
     * @param text text to re-case
     * @return the Pascal-case text, or "" for blank text
     */
    static String pascalCase(String text) {
        String normalized = normalizedText(text);
        if (normalized.isEmpty()) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder();
        for (String token : splitForCasing(normalized)) {
            if (token.isEmpty()) {
                continue;
            }
            if (PREFERRED_CASE.containsKey(J.lower(token))) {
                sb.append(preferredCase(token));
            } else {
                sb.append(token.substring(0, 1).toUpperCase(java.util.Locale.ROOT))
                        .append(token.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return sb.toString();
    }

    /**
     * Close lexicon matches for words not in the lexicon. Short all-caps tokens (acronyms) and words
     * with a tripled letter are skipped; a cutoff of 0.88 keeps suggestions to near-misses.
     *
     * @param text text to check
     * @return up to two suggestions per unknown word, de-duplicated, in preferred casing
     */
    static List<String> spellingSuggestions(String text) {
        Set<String> lex = lexicon();
        List<String> sorted = lexiconSorted;
        List<String> suggestions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String word : iterWords(text)) {
            String lowered = J.lower(word);
            if (lowered.isEmpty() || ALWAYS_ALLOWED.contains(lowered)) {
                continue;
            }
            if (lex.contains(lowered)) {
                continue;
            }
            if (isUpper(word) && word.length() <= 6) {
                continue;
            }
            if (TRIPLE.matcher(word).find()) {
                continue;
            }
            for (String match : SequenceMatcher.closeMatches(lowered, sorted, 2, 0.88)) {
                String suggestion = preferredCase(match);
                if (seen.add(suggestion)) {
                    suggestions.add(suggestion);
                }
            }
        }
        return suggestions;
    }

    /**
     * Expected casing style per role: sentence for prose, Pascal for transitions, title for other names.
     *
     * @param role requirement, comment, state, transition or diagram
     * @return "sentence", "pascal" or "title"
     */
    static String roleStyle(String role) {
        String r = J.lower(role);
        if (r.equals("requirement") || r.equals("comment")) {
            return "sentence";
        }
        if (r.equals("transition")) {
            return "pascal";
        }
        return "title";
    }

    /**
     * The text re-cased in the role's style: name fields get title/Pascal case, prose gets sentence case.
     *
     * @param text current text
     * @param role item role
     * @param field proofed field
     * @return the suggested text, or "" for blank text
     */
    static String suggestText(String text, String role, String field) {
        String normalized = normalizedText(text);
        if (normalized.isEmpty()) {
            return normalized;
        }
        if (NAME_FIELDS.contains(field)) {
            return "pascal".equals(roleStyle(role)) ? pascalCase(normalized) : titleCase(normalized);
        }
        return sentenceCase(normalized);
    }

    /**
     * Human-readable reason for a finding, combining spelling and casing problems.
     *
     * @param role item role
     * @param field proofed field
     * @param current current text
     * @param suggested suggested text
     * @param spelling spelling suggestions
     * @return the message
     */
    static String issueMessage(String role, String field, String current, String suggested, List<String> spelling) {
        List<String> parts = new ArrayList<>();
        if (!spelling.isEmpty()) {
            parts.add("possible spelling issue(s): " + String.join(", ", spelling));
        }
        if (!suggested.equals(current)) {
            if (NAME_FIELDS.contains(field)) {
                parts.add("suggested " + roleStyle(role) + " case");
            } else {
                parts.add("normalize punctuation, spacing, or sentence case");
            }
        }
        return parts.isEmpty() ? "proofing adjustment recommended" : String.join("; ", parts);
    }

    /**
     * One-line summary of a section's findings.
     *
     * @param role item role
     * @param findings the section's findings
     * @return the summary
     */
    static String summaryFor(String role, List<Finding> findings) {
        if (findings.isEmpty()) {
            return "No " + role + " proofing issues detected.";
        }
        Set<String> categories = new TreeSet<>();
        for (Finding f : findings) {
            categories.add(f.category);
        }
        return findings.size() + " " + role + " proofing issue(s) across " + String.join(", ", categories) + ".";
    }

    // ------------------------------------------------------------------ patch plans

    /**
     * One preview operation per (artifact type, artifact ID, field) with a suggested text; the first
     * finding wins. Name fields become rename_element, everything else replace_text.
     *
     * @param findings findings, in order
     * @param autoApply recorded on the plan; the plan itself is always preview-only
     * @return mode, previewOnly, operationCount and operations
     */
    static Map<String, Object> buildPatchPlanFromFindings(List<Finding> findings, boolean autoApply) {
        List<Object> operations = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (Finding f : findings) {
            if (f.suggestedText == null || f.suggestedText.isEmpty()) {
                continue;
            }
            List<String> key = Arrays.asList(f.artifactType, f.artifactId, f.field);
            if (!seen.add(key)) {
                continue;
            }
            operations.add(obj(
                    "action", NAME_FIELDS.contains(f.field) ? "rename_element" : "replace_text",
                    "target", obj("artifactType", f.artifactType, "artifactId", f.artifactId, "field", f.field),
                    "currentText", f.currentText,
                    "suggestedText", f.suggestedText,
                    "category", f.category,
                    "reason", f.message,
                    "confidence", f.confidence,
                    "previewOnly", true,
                    "autoApply", autoApply));
        }
        return obj(
                "mode", autoApply ? "auto_apply" : "preview",
                "previewOnly", true,
                "operationCount", operations.size(),
                "operations", operations);
    }

    /**
     * Patch plan from finding objects in either snake_case or camelCase form.
     *
     * @param findings finding maps
     * @param autoApply recorded on the plan
     * @return the patch plan
     */
    public static Map<String, Object> buildPatchPlan(List<Map<String, Object>> findings, boolean autoApply) {
        List<Finding> normalized = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            normalized.add(Finding.fromMap(f));
        }
        return buildPatchPlanFromFindings(normalized, autoApply);
    }

    // ------------------------------------------------------------------ analyzers

    /** Result of analyzing one role's items: the report plus the parts needed to merge sections. */
    private static final class Collected {
        /** The section report. */
        Map<String, Object> report;
        /** The section's findings. */
        List<Finding> findings;
        /** True when there are no findings. */
        boolean ok;
        /** The section's checks. */
        List<Object> checks;
        /** The section's metrics. */
        Map<String, Object> metrics;
    }

    /**
     * Proofs every text field of every item for one role. Placeholder text (TODO, TBD ...) gets its own
     * finding and skips other checks for that field; otherwise a finding is emitted when the re-cased
     * text differs or spelling suggestions exist.
     *
     * @param items text items, or null
     * @param role requirement, comment, state, transition or diagram
     * @param category category recorded on findings
     * @param autoApply recorded on the patch plan
     * @return the report and its parts
     */
    static Collected analyzeCollection(List<Map<String, Object>> items, String role, String category, boolean autoApply) {
        List<Map<String, Object>> records = items == null ? new ArrayList<>() : items;
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> metrics = obj("itemCount", records.size(), "textItemCount", 0,
                "spellingIssueCount", 0, "styleIssueCount", 0, "namingIssueCount", 0);
        for (Map<String, Object> item : records) {
            List<String[]> fields = extractTextFields(item, role);
            if (fields.isEmpty()) {
                continue;
            }
            inc(metrics, "textItemCount");
            for (String[] ft : fields) {
                String field = ft[0];
                String text = ft[1];
                Matcher todo = TODO.matcher(text);
                if (todo.find()) {
                    String stripped = J.strip(todo.replaceFirst(""), " :-");
                    findings.add(new Finding(category, "medium", extractKind(item, role), extractId(item), field,
                            "remove placeholder text before submission", text, sentenceCase(stripped),
                            new ArrayList<>(), 0.74, obj("role", role, "field", field, "placeholder", "todo")));
                    inc(metrics, "styleIssueCount");
                    continue;
                }
                String suggested = suggestText(text, role, field);
                List<String> spelling = spellingSuggestions(text);
                if (!spelling.isEmpty()) {
                    inc(metrics, "spellingIssueCount");
                }
                // Leading/trailing punctuation is counted as a style issue in metrics but does not by itself create a finding.
                if (LEADING_TRAILING_PUNCT.matcher(text).find()) {
                    inc(metrics, "styleIssueCount");
                }
                boolean changed = !suggested.equals(text);
                if (changed) {
                    inc(metrics, NAME_FIELDS.contains(field) ? "namingIssueCount" : "styleIssueCount");
                }
                if (text.equals(suggested) && spelling.isEmpty()) {
                    continue;
                }
                findings.add(new Finding(category,
                        !spelling.isEmpty() && changed ? "high" : "medium",
                        extractKind(item, role), extractId(item), field,
                        issueMessage(role, field, text, suggested, spelling),
                        text, suggested, spelling, changed ? 0.86 : 0.62,
                        obj("role", role, "field", field, "suggestedStyle", roleStyle(role),
                                "spellingSuggestions", new ArrayList<>(spelling))));
            }
        }
        List<Object> checks = J.arr(
                check(role + "-items-present", !records.isEmpty(), obj("itemCount", records.size())),
                check(role + "-proofing-clean", findings.isEmpty(),
                        obj("findingCount", findings.size(), "spellingIssueCount", metrics.get("spellingIssueCount"))));
        Collected c = new Collected();
        c.findings = findings;
        c.ok = findings.isEmpty();
        c.checks = checks;
        c.metrics = metrics;
        c.report = report(role, c.ok, summaryFor(role, findings), checks, metrics, findings,
                buildPatchPlanFromFindings(findings, autoApply), null, obj("items", records));
        return c;
    }

    /**
     * Increments an integer metric.
     *
     * @param metrics metrics map whose value under key is an Integer
     * @param key metric name
     */
    private static void inc(Map<String, Object> metrics, String key) {
        metrics.put(key, ((Integer) metrics.get(key)) + 1);
    }

    /**
     * Requirement proofing plus the requirement-quality heuristics from {@link Verification} as a semantic
     * baseline.
     *
     * <p>The baseline uses requireId and requireMeasurement on with a 20-character minimum, and the section
     * is ok only when the baseline passes too.
     *
     * @param requirements requirement payloads, or null
     * @param autoApply recorded on the patch plan
     * @return the requirement section report
     */
    public static Map<String, Object> proofRequirements(List<Map<String, Object>> requirements, boolean autoApply) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (requirements != null) {
            for (Map<String, Object> r : requirements) {
                list.add(normalizeRequirementPayload(r));
            }
        }
        Map<String, Object> baseline = Verification.verifyRequirementQuality(list, true, true, 20);
        Collected c = analyzeCollection(list, "requirement", "requirements", autoApply);
        Map<String, Object> report = c.report;
        // The baseline is exposed under both key styles for snake_case and camelCase consumers.
        J.map(report.get("sections")).put("semanticBaseline", baseline);
        List<Object> checks = new ArrayList<>(c.checks);
        checks.add(check("requirement-semantic-baseline", truthy(baseline.get("ok")), baseline.get("metrics")));
        report.put("checks", checks);
        report.put("ok", c.ok && truthy(baseline.get("ok")));
        report.put("summary", summaryFor("requirement", c.findings));
        report.put("semantic_baseline", baseline);
        report.put("semanticBaseline", baseline);
        return report;
    }

    /**
     * Comment bodies, proofed in sentence case.
     *
     * @param comments comment payloads, or null
     * @param autoApply recorded on the patch plan
     * @return the comment section report
     */
    public static Map<String, Object> proofComments(List<Map<String, Object>> comments, boolean autoApply) {
        return analyzeCollection(comments, "comment", "comments", autoApply).report;
    }

    /**
     * Diagram shape labels, proofed as title-case names.
     *
     * @param diagramText label items, or null
     * @param autoApply recorded on the patch plan
     * @return the diagram text section report
     */
    public static Map<String, Object> proofDiagramText(List<Map<String, Object>> diagramText, boolean autoApply) {
        return analyzeCollection(diagramText, "diagram", "diagram-text", autoApply).report;
    }

    /**
     * States (title case) and transitions (Pascal case) as one combined section.
     *
     * @param states state payloads, or null
     * @param transitions transition payloads, or null
     * @param autoApply recorded on the patch plan
     * @return the combined report with both sections
     */
    public static Map<String, Object> proofStateTransitionNames(List<Map<String, Object>> states,
                                                                List<Map<String, Object>> transitions,
                                                                boolean autoApply) {
        Collected s = analyzeCollection(states, "state", "states", autoApply);
        Collected t = analyzeCollection(transitions, "transition", "transitions", autoApply);
        List<Finding> findings = new ArrayList<>(s.findings);
        findings.addAll(t.findings);
        List<Object> checks = new ArrayList<>(s.checks);
        checks.addAll(t.checks);
        String summary = findings.isEmpty() ? "No state or transition naming issues detected."
                : findings.size() + " naming issue(s) across states and transitions.";
        return report("state-transition", s.ok && t.ok, summary, checks,
                obj("stateCount", s.metrics.get("itemCount"), "transitionCount", t.metrics.get("itemCount"),
                        "findingCount", findings.size()),
                findings, buildPatchPlanFromFindings(findings, autoApply), null,
                obj("states", s.report, "transitions", t.report));
    }

    /**
     * All sections combined into one report with a merged, de-duplicated patch plan.
     *
     * @param requirements requirement payloads, or null
     * @param comments comment payloads, or null
     * @param states state payloads, or null
     * @param transitions transition payloads, or null
     * @param diagramText label items, or null
     * @param autoApply recorded on the patch plan
     * @return the combined report; ok only when every section is ok
     */
    public static Map<String, Object> proofTexts(List<Map<String, Object>> requirements,
                                                 List<Map<String, Object>> comments,
                                                 List<Map<String, Object>> states,
                                                 List<Map<String, Object>> transitions,
                                                 List<Map<String, Object>> diagramText,
                                                 boolean autoApply) {
        Map<String, Object> sections = obj(
                "requirements", proofRequirements(requirements, autoApply),
                "comments", proofComments(comments, autoApply),
                "stateTransitions", proofStateTransitionNames(states, transitions, autoApply),
                "diagramText", proofDiagramText(diagramText, autoApply));
        List<Map<String, Object>> findings = new ArrayList<>();
        boolean ok = true;
        int failedSections = 0;
        List<Object> checks = new ArrayList<>();
        for (Map.Entry<String, Object> e : sections.entrySet()) {
            Map<String, Object> rep = J.map(e.getValue());
            findings.addAll(J.maps(rep.get("findings")));
            boolean sectionOk = truthy(rep.get("ok"));
            if (!sectionOk) {
                ok = false;
                failedSections++;
            }
            checks.add(check(e.getKey() + "-section", sectionOk, J.mapOrEmpty(rep.get("metrics"))));
        }
        List<Finding> normalized = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            normalized.add(Finding.fromMap(f));
        }
        String summary = ok ? "No proofing issues detected."
                : findings.size() + " proofing issue(s) found across " + failedSections + " section(s).";
        return report("proofing", ok, summary, checks,
                obj("sectionCount", sections.size(), "findingCount", findings.size(), "autoApply", autoApply),
                normalized, buildPatchPlanFromFindings(normalized, autoApply), null, sections);
    }

    // ------------------------------------------------------------------ live collection

    /**
     * All type/kind/stereotype descriptors of an item joined into one string for classification.
     *
     * @param item element payload
     * @return the descriptors, space separated
     */
    static String typeDescriptor(Map<String, Object> item) {
        List<Object> values = new ArrayList<>(Arrays.asList(item.get("artifactType"), item.get("kind"), item.get("type"),
                item.get("humanType"), item.get("elementType")));
        Object st = item.get("stereotypes");
        if (st instanceof String) {
            values.add(st);
        } else {
            values.addAll(J.list(st));
        }
        List<String> parts = new ArrayList<>();
        for (Object v : values) {
            String n = normalizedText(v);
            if (!n.isEmpty()) {
                parts.add(n);
            }
        }
        return String.join(" ", parts);
    }

    /**
     * True when the type descriptor mentions a requirement. Classification is by substring of the descriptor
     * (types and stereotypes) and is used when no explicit IDs are given.
     *
     * @param item element payload
     * @return whether the item is a requirement
     */
    static boolean isRequirement(Map<String, Object> item) {
        return J.lower(normalizedText(typeDescriptor(item))).contains("requirement");
    }

    /**
     * True when the type descriptor mentions a comment, note or annotation.
     *
     * @param item element payload
     * @return whether the item is a comment
     */
    static boolean isComment(Map<String, Object> item) {
        String d = J.lower(normalizedText(typeDescriptor(item)));
        return d.contains("comment") || d.contains("note") || d.contains("annotation");
    }

    /**
     * True when the type descriptor mentions a transition.
     *
     * @param item element payload
     * @return whether the item is a transition
     */
    static boolean isTransition(Map<String, Object> item) {
        return J.lower(normalizedText(typeDescriptor(item))).contains("transition");
    }

    /**
     * True for states: the descriptor mentions "state" but not a transition or state machine.
     *
     * @param item element payload
     * @return whether the item is a state
     */
    static boolean isState(Map<String, Object> item) {
        String d = J.lower(normalizedText(typeDescriptor(item)));
        // "state" matches StateMachine and Transition descriptors too, so those are excluded.
        return d.contains("state") && !d.contains("transition") && !d.contains("state machine");
    }

    /**
     * Adds specification text to an element payload: documentation and constraints override, while
     * specification properties and stereotype tagged values only fill keys the element lacks.
     *
     * @param item element payload
     * @param spec specification payload, or null
     * @return a new merged map; the full specification is under "specification"
     */
    static Map<String, Object> mergeSpecification(Map<String, Object> item, Map<String, Object> spec) {
        Map<String, Object> merged = new LinkedHashMap<>(item);
        if (spec == null) {
            return merged;
        }
        merged.put("specification", new LinkedHashMap<>(spec));
        for (String key : new String[]{"documentation", "constraints"}) {
            if (spec.containsKey(key)) {
                merged.put(key, spec.get(key));
            }
        }
        Map<String, Object> properties = J.map(spec.get("properties"));
        if (properties != null) {
            for (String key : new String[]{"name", "documentation", "body", "text", "comment"}) {
                if (properties.containsKey(key) && !merged.containsKey(key)) {
                    merged.put(key, properties.get(key));
                }
            }
        }
        for (Map<String, Object> stereotype : J.maps(spec.get("appliedStereotypes"))) {
            Map<String, Object> tagged = J.map(stereotype.get("taggedValues"));
            if (tagged == null) {
                continue;
            }
            for (String key : new String[]{"text", "body", "comment", "documentation", "id"}) {
                Object v = tagged.get(key);
                if (v != null && !merged.containsKey(key)) {
                    merged.put(key, v);
                }
            }
        }
        return merged;
    }

    /**
     * Trimmed, de-duplicated, non-blank IDs in first-seen order.
     *
     * @param values IDs, possibly null or blank
     * @return the unique trimmed IDs
     */
    static List<String> uniqueIds(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            String t = v == null ? "" : v.trim();
            if (!t.isEmpty()) {
                seen.add(t);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Up to {@code max} elements under a package (all pages of cameo_query_elements, full view).
     *
     * <p>Pages of 1000 are requested until hasMore is false or paging stops advancing; max is 5000.
     *
     * @param rootPackageId package to search recursively
     * @param bridge model access
     * @return copies of the element payloads
     * @throws Exception from the bridge
     */
    static List<Map<String, Object>> collectRootElements(String rootPackageId, Bridge bridge) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        int offset = 0;
        int max = 5000;
        while (out.size() < max) {
            Map<String, Object> result = bridge.queryElements(obj(
                    "package_id", rootPackageId, "recursive", true, "limit", 1000, "offset", offset, "view", "full"));
            if (result == null) {
                break;
            }
            List<Map<String, Object>> page = J.maps(result.get("elements"));
            for (Map<String, Object> e : page) {
                out.add(new LinkedHashMap<>(e));
            }
            Integer next = J.safeInt(result.get("nextOffset"));
            if (!truthy(result.get("hasMore")) || next == null || next <= offset || page.isEmpty()) {
                break;
            }
            offset = next;
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    /**
     * Diagram label targets: the given diagram IDs, or else every diagram owned by the root package or
     * any element under it. Each labelled shape becomes one "label" item pointing at its model element.
     *
     * @param rootPackageId root package, or null
     * @param diagramIds explicit diagram IDs, or null or empty to discover them
     * @param packageElements elements under the root package
     * @param bridge model access
     * @return one label item per labelled shape
     * @throws Exception from the bridge
     */
    static List<Map<String, Object>> collectDiagramTextTargets(String rootPackageId, List<String> diagramIds,
                                                               List<Map<String, Object>> packageElements,
                                                               Bridge bridge) throws Exception {
        List<String> selected = new ArrayList<>();
        if (diagramIds != null) {
            for (String id : diagramIds) {
                if (id != null && !id.trim().isEmpty()) {
                    selected.add(id);
                }
            }
        }
        if (selected.isEmpty() && truthy(rootPackageId)) {
            Set<String> subtree = new HashSet<>();
            for (Map<String, Object> e : packageElements) {
                Object id = J.or(e.get("id"), e.get("elementId"));
                if (truthy(id)) {
                    subtree.add(J.str(id));
                }
            }
            subtree.add(rootPackageId);
            Map<String, Object> diagrams = bridge.listDiagrams();
            for (Map<String, Object> d : J.maps(diagrams == null ? null : diagrams.get("diagrams"))) {
                if (subtree.contains(J.text(d.get("ownerId"))) && truthy(d.get("id"))) {
                    selected.add(J.str(d.get("id")));
                }
            }
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String diagramId : selected) {
            Map<String, Object> shapes = bridge.listDiagramShapes(diagramId);
            for (Map<String, Object> shape : J.maps(shapes == null ? null : shapes.get("shapes"))) {
                String label = normalizedText(J.or(shape.get("elementName"), shape.get("name"), shape.get("label"),
                        shape.get("text"), ""));
                if (label.isEmpty()) {
                    continue;
                }
                targets.add(obj(
                        "id", J.text(J.or(shape.get("presentationId"), shape.get("elementId"))),
                        "artifactType", J.or(shape.get("elementType"), shape.get("shapeType"), "DiagramText"),
                        "artifactId", J.text(J.or(shape.get("elementId"), shape.get("presentationId"))),
                        "field", "label",
                        "label", label,
                        "diagramId", diagramId,
                        "presentationId", shape.get("presentationId"),
                        "elementId", shape.get("elementId"),
                        "shapeType", shape.get("shapeType"),
                        "elementType", shape.get("elementType")));
            }
        }
        return targets;
    }

    /**
     * Collect proofable model text from a package or explicit element lists.
     *
     * <p>Explicit IDs for a role replace type-based discovery for that role. Requirements and comments are
     * merged with their specification.
     *
     * @param rootPackageId package whose elements are proofed, or null
     * @param requirementIds explicit requirement IDs, or null
     * @param commentIds explicit comment IDs, or null
     * @param stateIds explicit state IDs, or null
     * @param transitionIds explicit transition IDs, or null
     * @param diagramIds explicit diagram IDs, or null
     * @param bridge model access
     * @return requirements, comments, states, transitions and diagramText item lists
     * @throws Exception from the bridge
     */
    public static Map<String, Object> collectProofingTargets(String rootPackageId, List<String> requirementIds,
                                                             List<String> commentIds, List<String> stateIds,
                                                             List<String> transitionIds, List<String> diagramIds,
                                                             Bridge bridge) throws Exception {
        List<Map<String, Object>> packageElements = truthy(rootPackageId)
                ? collectRootElements(rootPackageId, bridge) : new ArrayList<>();
        // Elements under the root package are read once; explicit IDs outside it are fetched individually.
        Set<String> explicit = new LinkedHashSet<>();
        for (List<String> seq : Arrays.asList(requirementIds, commentIds, stateIds, transitionIds)) {
            if (seq != null) {
                for (String id : seq) {
                    if (id != null && !id.trim().isEmpty()) {
                        explicit.add(id);
                    }
                }
            }
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> e : packageElements) {
            Object id = J.or(e.get("id"), e.get("elementId"));
            if (truthy(id)) {
                byId.put(J.str(id), new LinkedHashMap<>(e));
            }
        }
        for (String id : explicit) {
            if (byId.containsKey(id)) {
                continue;
            }
            Map<String, Object> element = bridge.getElement(id);
            if (element != null) {
                Object eid = J.or(element.get("id"), element.get("elementId"));
                if (truthy(eid)) {
                    byId.put(J.str(eid), new LinkedHashMap<>(element));
                }
            }
        }
        // For each role, explicit IDs win; otherwise every package element of the matching type is proofed.
        List<String> reqCandidates = candidates(requirementIds, byId, Proofing::isRequirement);
        List<String> commentCandidates = candidates(commentIds, byId, Proofing::isComment);
        List<String> stateCandidates = candidates(stateIds, byId, Proofing::isState);
        List<String> transitionCandidates = candidates(transitionIds, byId, Proofing::isTransition);

        // Only requirements and comments need their specification (documentation, Text tag, body).
        List<String> specIdsRaw = new ArrayList<>(reqCandidates);
        specIdsRaw.addAll(commentCandidates);
        Map<String, Map<String, Object>> specs = new LinkedHashMap<>();
        for (String id : uniqueIds(specIdsRaw)) {
            Map<String, Object> spec = bridge.getSpecification(id);
            if (spec != null) {
                specs.put(id, spec);
            }
        }
        List<Object> requirements = new ArrayList<>();
        for (String id : uniqueIds(reqCandidates)) {
            if (byId.containsKey(id)) {
                requirements.add(mergeSpecification(byId.get(id), specs.get(id)));
            }
        }
        List<Object> comments = new ArrayList<>();
        for (String id : uniqueIds(commentCandidates)) {
            if (byId.containsKey(id)) {
                comments.add(mergeSpecification(byId.get(id), specs.get(id)));
            }
        }
        List<Object> statesOut = new ArrayList<>();
        for (String id : uniqueIds(stateCandidates)) {
            if (byId.containsKey(id)) {
                statesOut.add(new LinkedHashMap<>(byId.get(id)));
            }
        }
        List<Object> transitionsOut = new ArrayList<>();
        for (String id : uniqueIds(transitionCandidates)) {
            if (byId.containsKey(id)) {
                transitionsOut.add(new LinkedHashMap<>(byId.get(id)));
            }
        }
        List<Map<String, Object>> diagramText = collectDiagramTextTargets(rootPackageId, diagramIds,
                new ArrayList<>(byId.values()), bridge);
        return obj(
                "requirements", requirements,
                "comments", comments,
                "states", statesOut,
                "transitions", transitionsOut,
                "diagramText", diagramText);
    }

    /** Explicit IDs when given, else the IDs of elements passing {@code test}. */
    private static List<String> candidates(List<String> explicit, Map<String, Map<String, Object>> byId,
                                           java.util.function.Predicate<Map<String, Object>> test) {
        if (explicit != null && !explicit.isEmpty()) {
            return new ArrayList<>(explicit);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : byId.entrySet()) {
            if (test.test(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Apply a (preview) patch plan to the live model where the tools support it.
     *
     * <p>Name fields go through modify_element (for a diagram label this renames the underlying element),
     * documentation likewise, and any other field is written with set_specification. Each operation is
     * applied on its own; failures are recorded per receipt and do not stop the rest.
     *
     * @param patchPlan a plan from {@link #buildPatchPlan} or a proofing report, or null
     * @param bridge model access
     * @return ok, counts and one receipt per operation (applied, skipped or failed)
     */
    public static Map<String, Object> applyPatchPlan(Map<String, Object> patchPlan, Bridge bridge) {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (Map<String, Object> op : J.maps(patchPlan == null ? null : patchPlan.get("operations"))) {
            operations.add(new LinkedHashMap<>(op));
        }
        // Used by cameo_apply_proofing_patch_plan; each operation is applied on its own and failures
        // are reported per receipt instead of aborting the rest.
        List<Object> receipts = new ArrayList<>();
        int failed = 0;
        for (Map<String, Object> op : operations) {
            Map<String, Object> target = J.mapOrEmpty(op.get("target"));
            String artifactId = J.text(target.get("artifactId")).trim();
            String field = J.text(target.get("field")).trim();
            String suggested = J.text(op.get("suggestedText"));
            if (artifactId.isEmpty() || field.isEmpty() || suggested.isEmpty()) {
                receipts.add(obj("status", "skipped", "operation", op,
                        "reason", "missing artifact id, field, or suggested text"));
                continue;
            }
            try {
                Map<String, Object> result;
                String appliedWith;
                // Names go through cameo_modify_element (for a diagram label this renames the underlying element),
                // documentation likewise, and any other field is written as a specification property.
                if (NAME_FIELDS.contains(field)) {
                    result = bridge.modifyElement(artifactId, obj("name", suggested));
                    appliedWith = "modify_element";
                } else if (field.equals("documentation")) {
                    result = bridge.modifyElement(artifactId, obj("documentation", suggested));
                    appliedWith = "modify_element";
                } else {
                    result = bridge.setSpecification(artifactId, obj(field, suggested));
                    appliedWith = "set_specification";
                }
                receipts.add(obj("status", "applied", "artifactId", artifactId, "field", field,
                        "suggestedText", suggested, "appliedWith", appliedWith, "result", result));
            } catch (Exception e) {
                failed++;
                receipts.add(obj("status", "failed", "artifactId", artifactId, "field", field,
                        "suggestedText", suggested, "error", String.valueOf(e.getMessage())));
            }
        }
        return obj(
                "ok", failed == 0,
                "mode", "applied",
                "operationCount", operations.size(),
                "receiptCount", receipts.size(),
                "failedCount", failed,
                "receipts", receipts);
    }

    /**
     * Collect model text, analyze it, and optionally apply the suggested fixes.
     *
     * @param rootPackageId package whose elements are proofed, or null
     * @param requirementIds explicit requirement IDs, or null
     * @param commentIds explicit comment IDs, or null
     * @param stateIds explicit state IDs, or null
     * @param transitionIds explicit transition IDs, or null
     * @param diagramIds explicit diagram IDs, or null
     * @param autoApply apply the resulting plan right away
     * @param bridge model access
     * @return the proofing report plus "collected" and, after auto-apply, applyReceipts
     * @throws Exception from the bridge while collecting
     */
    public static Map<String, Object> proofModelText(String rootPackageId, List<String> requirementIds,
                                                     List<String> commentIds, List<String> stateIds,
                                                     List<String> transitionIds, List<String> diagramIds,
                                                     boolean autoApply, Bridge bridge) throws Exception {
        Map<String, Object> sections = collectProofingTargets(rootPackageId, requirementIds, commentIds, stateIds,
                transitionIds, diagramIds, bridge);
        Map<String, Object> report = proofTexts(
                J.maps(sections.get("requirements")),
                J.maps(sections.get("comments")),
                J.maps(sections.get("states")),
                J.maps(sections.get("transitions")),
                J.maps(sections.get("diagramText")),
                autoApply);
        List<String> ids = new ArrayList<>();
        if (diagramIds != null) {
            for (String d : diagramIds) {
                if (d != null && !d.trim().isEmpty()) {
                    ids.add(d);
                }
            }
        }
        report.put("collected", obj(
                "rootPackageId", rootPackageId,
                "requirementCount", J.list(sections.get("requirements")).size(),
                "commentCount", J.list(sections.get("comments")).size(),
                "stateCount", J.list(sections.get("states")).size(),
                "transitionCount", J.list(sections.get("transitions")).size(),
                "diagramTextCount", J.list(sections.get("diagramText")).size(),
                "diagramIds", ids));
        // cameo_proof_model_text with auto_apply: the plan is applied right after analysis.
        Map<String, Object> plan = J.mapOrEmpty(report.get("patch_plan"));
        if (autoApply && !J.list(plan.get("operations")).isEmpty()) {
            Map<String, Object> receipts = applyPatchPlan(plan, bridge);
            report.put("applyReceipts", receipts);
            report.put("autoApplyCompleted", truthy(receipts.get("ok")));
        }
        return report;
    }
}
