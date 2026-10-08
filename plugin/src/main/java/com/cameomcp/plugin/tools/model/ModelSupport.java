package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.ProjectKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    private ModelSupport() {
    }

    // ------------------------------------------------------------------ lookup

    /** Element by id, or NOT_FOUND naming the parameter. */
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

    /** Element by id or null when the id is blank; NOT_FOUND when it does not resolve. */
    static Element optionalElement(Project project, String id, String param) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return element(project, id, param);
    }

    /** Element of the given type, or INVALID_PARAMS describing what was found instead. */
    static <T> T typed(Project project, String id, String param, Class<T> type, String expected) {
        Element e = element(project, id, param);
        if (!type.isInstance(e)) {
            throw ToolException.invalid(param + " must reference " + expected + ", but '" + id + "' is "
                    + describe(e) + ".");
        }
        return type.cast(e);
    }

    static Element root(Project project, String id, String param) {
        if (id != null && !id.trim().isEmpty()) {
            return element(project, id, param);
        }
        return ProjectKind.primaryModel(project);
    }

    // ------------------------------------------------------------------ naming

    static String name(Element e) {
        if (e instanceof NamedElement) {
            String n = ((NamedElement) e).getName();
            return n == null ? "" : n;
        }
        return "";
    }

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

    static String className(Element e) {
        try {
            Class<?> c = e.getClassType();
            return c == null ? null : c.getSimpleName();
        } catch (Exception ex) {
            return null;
        }
    }

    /** "Class 'Engine' [id=...]" for error messages. */
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

    static JsonObject ref(Element e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getID());
        if (e instanceof NamedElement) {
            o.addProperty("name", ((NamedElement) e).getName());
        }
        return o;
    }

    static final Comparator<Element> BY_TYPE_NAME_ID = Comparator
            .comparing(ModelSupport::typeName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelSupport::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelSupport::safeId, String.CASE_INSENSITIVE_ORDER);

    static final Comparator<Element> BY_NAME_ID = Comparator
            .comparing(ModelSupport::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelSupport::safeId);

    static String safeId(Element e) {
        String id = e.getID();
        return id == null ? "" : id;
    }

    static List<Element> sortedChildren(Element e) {
        Collection<Element> owned = e.getOwnedElement();
        List<Element> out = owned == null ? new ArrayList<>() : new ArrayList<>(owned);
        out.sort(BY_NAME_ID);
        return out;
    }

    // ------------------------------------------------------------------ stereotypes

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

    /** Names of the stereotype's profile and owner chain, used for preference ranking. */
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

    /** Lenient lookup that fails with an actionable message. */
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

    /** Replace the first owned comment's body, or add a new owned comment. */
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
