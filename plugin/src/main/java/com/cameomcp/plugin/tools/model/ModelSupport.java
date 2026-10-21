package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.ProjectKind;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.magicdraw.uml.ClassTypes;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Cameo-facing helpers shared by the model family's services. */
final class ModelSupport {

    /** Not instantiable; all members are static. */
    private ModelSupport() {
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Element by id, or NOT_FOUND naming the parameter.
     *
     * @param project the open project
     * @param id the element id; surrounding spaces are ignored
     * @param param the parameter name the id came from, used in error messages
     * @return the element
     * @throws ToolException INVALID_PARAMS when the id is blank, NOT_FOUND when it does not resolve to a UML
     *     element
     */
    static Element element(Project project, String id, String param) {
        if (id == null || id.trim().isEmpty()) {
            throw ToolException.invalid("Missing required parameter '" + param + "'.");
        }
        BaseElement found = project.getElementByID(id.trim());
        if (!(found instanceof Element)) {
            throw ProjectKind.notUmlElement(found, "Element '" + id + "' (" + param + ") not found. Use "
                    + "cameo_query_elements or cameo_list_containment_children to find valid IDs.");
        }
        return (Element) found;
    }

    /**
     * Element by id or null when the id is blank; NOT_FOUND when it does not resolve.
     *
     * @param project the open project
     * @param id the element id, or null or blank for none
     * @param param the parameter name, used in error messages
     * @return the element, or null when no id was given
     * @throws ToolException NOT_FOUND when a non-blank id does not resolve
     */
    static Element optionalElement(Project project, String id, String param) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return element(project, id, param);
    }

    /**
     * Element of the given type, or INVALID_PARAMS describing what was found instead.
     *
     * @param <T> the expected element type
     * @param project the open project
     * @param id the element id
     * @param param the parameter name, used in error messages
     * @param type the class the element must be an instance of
     * @param expected a phrase such as "a Behavior" naming the expected kind, used in error messages
     * @return the element cast to the expected type
     * @throws ToolException INVALID_PARAMS when the id is blank or the element is of the wrong type,
     *     NOT_FOUND when
     * the id does not resolve
     */
    static <T> T typed(Project project, String id, String param, Class<T> type, String expected) {
        Element e = element(project, id, param);
        if (!type.isInstance(e)) {
            throw ToolException.invalid(param + " must reference " + expected + ", but '" + id + "' is "
                    + describe(e) + ".");
        }
        return type.cast(e);
    }

    /**
     * The given element, or the project's primary model when no id is given (the usual default root).
     *
     * @param project the open project
     * @param id the element id, or null or blank for the primary model
     * @param param the parameter name, used in error messages
     * @return the element or the primary model
     * @throws ToolException NOT_FOUND when a non-blank id does not resolve
     */
    static Element root(Project project, String id, String param) {
        if (id != null && !id.trim().isEmpty()) {
            return element(project, id, param);
        }
        return ProjectKind.primaryModel(project);
    }

    // ------------------------------------------------------------------ naming

    /**
     * The element's name, or "" for unnamed and non-named elements.
     *
     * @param e the element
     * @return the name, never null
     */
    static String name(Element e) {
        if (e instanceof NamedElement) {
            String n = ((NamedElement) e).getName();
            return n == null ? "" : n;
        }
        return "";
    }

    /**
     * The metaclass short name (Class, Package, ...), falling back to the human type.
     *
     * @param e the element
     * @return the short metaclass name, the human type if that fails, or ""
     */
    static String typeName(Element e) {
        try {
            String shortName = ClassTypes.getShortName(e.getClassType());
            if (shortName != null && !shortName.isEmpty()) {
                return shortName;
            }
        } catch (Exception ignored) {
            // fall back to the human type
        }
        String human = e.getHumanType();
        return human == null ? "" : human;
    }

    /**
     * The Java interface name of the element's metaclass, or null.
     *
     * @param e the element
     * @return the simple name of {@code getClassType()}, or null when it is unknown or the call fails
     */
    static String className(Element e) {
        try {
            Class<?> c = e.getClassType();
            return c == null ? null : c.getSimpleName();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * "Class 'Engine' [id=...]" for error messages.
     *
     * @param element the element, may be null
     * @return a description with type, human name and id; never throws
     */
    static String describe(Element element) {
        if (element == null) {
            return "<null>";
        }
        String type = element.getHumanType();
        if (type == null || type.trim().isEmpty()) {
            type = className(element);
        }
        String human = null;
        try {
            human = element.getHumanName();
        } catch (Exception ignored) {
            // use the plain name
        }
        if ((human == null || human.trim().isEmpty()) && element instanceof NamedElement) {
            human = ((NamedElement) element).getName();
        }
        if (human == null || human.trim().isEmpty()) {
            human = "<unnamed>";
        }
        String id = element.getID();
        return type + " '" + human + "' [id=" + (id == null ? "<no-id>" : id) + "]";
    }

    /**
     * Minimal {id, name} reference used inside results.
     *
     * @param e the element
     * @return an object with {@code id} and, for named elements, {@code name}
     */
    static JsonObject ref(Element e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getID());
        if (e instanceof NamedElement) {
            o.addProperty("name", ((NamedElement) e).getName());
        }
        return o;
    }

    /** Stable order for query results: metaclass name, then name, then id, all ignoring case. */
    static final Comparator<Element> BY_TYPE_NAME_ID = Comparator
            .comparing(ModelSupport::typeName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelSupport::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelSupport::safeId, String.CASE_INSENSITIVE_ORDER);

    /** Stable order for child listings: name ignoring case, then id. */
    static final Comparator<Element> BY_NAME_ID = Comparator
            .comparing(ModelSupport::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelSupport::safeId);

    /**
     * The element id, or "" if it has none.
     *
     * @param e the element
     * @return the id, never null
     */
    static String safeId(Element e) {
        String id = e.getID();
        return id == null ? "" : id;
    }

    /**
     * Owned elements sorted by name, then id.
     *
     * @param e the owner
     * @return a new mutable list of its owned elements in {@link #BY_NAME_ID} order
     */
    static List<Element> sortedChildren(Element e) {
        Collection<Element> owned = e.getOwnedElement();
        List<Element> out = owned == null ? new ArrayList<>() : new ArrayList<>(owned);
        out.sort(BY_NAME_ID);
        return out;
    }

    // ------------------------------------------------------------------ stereotypes

    /**
     * True if any applied stereotype has this name (case-insensitive).
     *
     * @param element the element to check
     * @param name the stereotype name, compared ignoring case
     * @return true on a match; false when nothing matches or Cameo throws
     */
    static boolean hasStereotypeNamed(Element element, String name) {
        try {
            List<Stereotype> list = StereotypesHelper.getStereotypes(element);
            if (list != null) {
                for (Stereotype st : list) {
                    if (st != null && st.getName() != null && st.getName().equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // treat as no match
        }
        return false;
    }

    /**
     * Names of the applied stereotypes.
     *
     * @param element the element to read
     * @return the applied stereotype names; empty when there are none or Cameo throws
     */
    static JsonArray stereotypeNames(Element element) {
        JsonArray a = new JsonArray();
        try {
            List<Stereotype> list = StereotypesHelper.getStereotypes(element);
            if (list != null) {
                for (Stereotype st : list) {
                    if (st != null && st.getName() != null) {
                        a.add(st.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // empty
        }
        return a;
    }

    /**
     * Names of the stereotype's profile and owner chain, used for preference ranking.
     *
     * @param stereotype the stereotype
     * @return the profile name first (when known), then the names of the stereotype and each of its owners up
     *     to
     * the root; blank names are left out
     */
    static List<String> contexts(Stereotype stereotype) {
        List<String> out = new ArrayList<>();
        try {
            Profile p = stereotype.getProfile();
            if (p != null && p.getName() != null && !p.getName().trim().isEmpty()) {
                out.add(p.getName());
            }
        } catch (Exception ignored) {
            // owner chain only
        }
        Element cur = stereotype;
        while (cur != null) {
            String n = name(cur);
            if (!n.trim().isEmpty()) {
                out.add(n);
            }
            cur = cur.getOwner();
        }
        return out;
    }

    /**
     * Every stereotype in the project, including those of used profiles, with this name ignoring case.
     *
     * @param project the open project
     * @param name the stereotype name
     * @return the matches in the order Cameo lists them; may be empty
     */
    private static List<Stereotype> stereotypesNamed(Project project, String name) {
        List<Stereotype> matches = new ArrayList<>();
        Collection<Stereotype> all = StereotypesHelper.getAllStereotypes(project);
        if (all != null) {
            for (Stereotype st : all) {
                if (st != null && name.equalsIgnoreCase(st.getName())) {
                    matches.add(st);
                }
            }
        }
        return matches;
    }

    /**
     * Lenient lookup used for applying stereotypes: the named profile first, then every loaded
     * stereotype with that name, preferring SysML contexts; falls back to the first match.
     *
     * @param project the open project
     * @param name the stereotype name, compared ignoring case
     * @param profileName the profile to look in first, or null or blank for any profile
     * @return the best match, or null when no stereotype has that name
     */
    static Stereotype findStereotype(Project project, String name, String profileName) {
        if (profileName != null && !profileName.trim().isEmpty()) {
            Profile profile = StereotypesHelper.getProfile(project, profileName.trim());
            if (profile != null) {
                Stereotype st = StereotypesHelper.getStereotype(project, name, profile);
                if (st != null) {
                    return st;
                }
            }
            List<Stereotype> inProfile = new ArrayList<>();
            for (Stereotype st : stereotypesNamed(project, name)) {
                for (String c : contexts(st)) {
                    if (ModelText.normalizeContextToken(c).equals(ModelText.normalizeContextToken(profileName))) {
                        inProfile.add(st);
                        break;
                    }
                }
            }
            if (!inProfile.isEmpty()) {
                return inProfile.get(0);
            }
        }
        List<Stereotype> matches = stereotypesNamed(project, name);
        if (matches.isEmpty()) {
            return null;
        }
        List<String> preferred = ModelText.preferredStereotypeContexts(name);
        int bestRank = Integer.MAX_VALUE;
        Stereotype best = matches.get(0);
        for (Stereotype st : matches) {
            int rank = ModelText.contextRank(contexts(st), preferred);
            if (rank < bestRank) {
                bestRank = rank;
                best = st;
            }
        }
        return best;
    }

    /**
     * Lenient lookup that fails with an actionable message.
     *
     * @param project the open project
     * @param name the stereotype name
     * @param profileName the profile to look in first, or null
     * @return the stereotype found by {@link #findStereotype}
     * @throws ToolException NOT_FOUND when there is none
     */
    static Stereotype requireStereotypeLenient(Project project, String name, String profileName) {
        Stereotype st = findStereotype(project, name, profileName);
        if (st == null) {
            throw new ToolException("NOT_FOUND", "Stereotype '" + name + "' not found"
                    + (profileName != null ? " in profile '" + profileName + "'" : "")
                    + ". Check the spelling (stereotype names are like block, requirement, valueType) and that the "
                    + "profile defining it is loaded; apply custom profiles with cameo_apply_profile.");
        }
        return st;
    }

    /**
     * Strict lookup used for relationship stereotypes (Satisfy, Allocate, ItemFlow...): ambiguous
     * candidates that preference cannot separate are an error rather than a silent guess.
     *
     * @param project the open project
     * @param name the stereotype name, for example Satisfy
     * @return the single preferred match
     * @throws ToolException PRECONDITION_FAILED when the stereotype is not in the project, CONFLICT listing
     *     the
     * candidates when the preferred contexts cannot pick one
     */
    static Stereotype requireStereotypeStrict(Project project, String name) {
        List<Stereotype> matches = stereotypesNamed(project, name);
        List<String> preferred = ModelText.preferredStereotypeContexts(name);
        if (matches.isEmpty()) {
            throw new ToolException("PRECONDITION_FAILED", "SysML stereotype not found: " + name
                    + ". Preferred contexts: " + preferred + ". Ensure the SysML profile is used by the project "
                    + "(create the project from a SysML template or apply the SysML profile).");
        }
        List<List<String>> ctx = new ArrayList<>();
        for (Stereotype st : matches) {
            ctx.add(contexts(st));
        }
        int pick = ModelText.pickPreferred(ctx, preferred);
        if (pick >= 0) {
            return matches.get(pick);
        }
        StringBuilder sb = new StringBuilder("Ambiguous stereotype resolution for ").append(name)
                .append(". Candidates: ");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            Stereotype st = matches.get(i);
            String profile = "<no-profile>";
            try {
                if (st.getProfile() != null && st.getProfile().getName() != null) {
                    profile = st.getProfile().getName();
                }
            } catch (Exception ignored) {
                // keep placeholder
            }
            sb.append(describe(st)).append(" profile=").append(profile).append(" ownerPath=").append(ownerPath(st));
        }
        sb.append(". Use cameo_apply_stereotype with an explicit profile, or remove the duplicate profile.");
        throw new ToolException("CONFLICT", sb.toString());
    }

    /**
     * "Model > Package > Sub" path of the element's owners, or "<root>".
     *
     * @param element the element, may be null
     * @return the names of its owners from the root down, joined by " > "; blank names are skipped
     */
    static String ownerPath(Element element) {
        List<String> names = new ArrayList<>();
        Element cur = element == null ? null : element.getOwner();
        while (cur != null) {
            String n = name(cur);
            if (!n.trim().isEmpty()) {
                names.add(0, n);
            }
            cur = cur.getOwner();
        }
        return names.isEmpty() ? "<root>" : String.join(" > ", names);
    }

    /**
     * The SysML FlowProperty stereotype applied to the element, or null; both name spellings are tried.
     *
     * @param element the element
     * @return the applied stereotype, or null when it is not applied or Cameo throws
     */
    static Stereotype flowPropertyStereotype(Element element) {
        try {
            Stereotype st = StereotypesHelper.getAppliedStereotypeByString(element, "FlowProperty");
            if (st == null) {
                st = StereotypesHelper.getAppliedStereotypeByString(element, "flowProperty");
            }
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ documentation

    /**
     * The bodies of the element's owned comments joined by newlines, or null if there are none.
     *
     * @param element the element
     * @return the joined text, or null when there is no non-empty comment or Cameo throws
     */
    static String documentation(Element element) {
        try {
            Collection<Comment> comments = element.getOwnedComment();
            if (comments == null || comments.isEmpty()) {
                return null;
            }
            StringBuilder doc = new StringBuilder();
            for (Comment c : comments) {
                String body = c.getBody();
                if (body != null && !body.isEmpty()) {
                    if (doc.length() > 0) {
                        doc.append('\n');
                    }
                    doc.append(body);
                }
            }
            return doc.length() > 0 ? doc.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replace the first owned comment's body, or add a new owned comment.
     *
     * <p>Threading: must run inside a write session on the EDT.
     *
     * @param project the open project, whose factory creates a new comment
     * @param element the element to document
     * @param text the new documentation text
     * @throws Exception any Cameo failure while adding the comment
     */
    static void setDocumentation(Project project, Element element, String text) throws Exception {
        Collection<Comment> comments = element.getOwnedComment();
        if (comments != null && !comments.isEmpty()) {
            comments.iterator().next().setBody(text);
            return;
        }
        Comment comment = project.getElementsFactory().createCommentInstance();
        comment.setBody(text);
        ModelElementsManager.getInstance().addElement(comment, element);
    }

    // ------------------------------------------------------------------ editability

    /**
     * Fails with PRECONDITION_FAILED when Cameo reports the element read-only. If the check itself
     * throws (older API), the element is treated as editable and the write decides.
     *
     * @param element the element to check
     * @param what a word such as "Parent" or "Element" that starts the error message
     * @throws ToolException PRECONDITION_FAILED when the element is read-only
     */
    static void requireEditable(Element element, String what) {
        boolean editable;
        try {
            editable = element.isEditable();
        } catch (Throwable t) {
            editable = true;
        }
        if (!editable) {
            throw new ToolException("PRECONDITION_FAILED", what + " " + describe(element) + " is read-only (locked "
                    + "in Teamwork Cloud, part of a used project or a standard profile). Lock it for editing in Cameo "
                    + "or choose an editable element.");
        }
    }

    /**
     * True if {@code candidateAncestor} is the element itself or one of its owners; used to block cyclic
     * moves.
     *
     * @param candidateAncestor the would-be new owner; compared by identity, then by id
     * @param element the element being moved
     * @return true when the move would put the element inside itself
     */
    static boolean isAncestorOrSelf(Element candidateAncestor, Element element) {
        Element cur = element;
        while (cur != null) {
            if (cur == candidateAncestor || (cur.getID() != null && cur.getID().equals(candidateAncestor.getID()))) {
                return true;
            }
            cur = cur.getOwner();
        }
        return false;
    }
}
