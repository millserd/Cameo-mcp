package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.ext.magicdraw.mdusecases.UseCase;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Pseudostate;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Region;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.State;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Vertex;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Element, stereotype, profile and relationship changes. Every method is one undoable session. */
final class MutationService {

    // Upper bound for cameo_create_elements; larger plans risk the EDT timeout and a huge single undo step.
    static final int BULK_MAX_ENTRIES = 500;

    private MutationService() {
    }

    // ------------------------------------------------------------------ create

    // cameo_create_element: parses and validates the request before opening the session, then creates.
    static JsonObject createElement(Args a) throws Exception {
        ElementCreator.Request r = ElementCreator.Request.from(a);
        return Edt.write("Create " + r.type + " " + r.name, project -> {
            Element created = ElementCreator.create(project, r);
            JsonObject out = new JsonObject();
            out.addProperty("created", true);
            out.add("element", ElementJson.toJson(created));
            return out;
        });
    }

    // cameo_create_relationship: same pattern for relationships.
    static JsonObject createRelationship(Args a) throws Exception {
        RelationshipCreator.Request r = RelationshipCreator.Request.from(a);
        return Edt.write("Create " + r.type + " relationship", project -> {
            Element rel = RelationshipCreator.create(project, r);
            JsonObject out = new JsonObject();
            out.addProperty("created", true);
            out.addProperty("relationshipType", r.type);
            out.add("relationship", ElementJson.toJson(rel));
            return out;
        });
    }

    /** cameo_create_elements: all entries in one session; any failure rolls everything back. */
    static JsonObject createElements(Args a) throws Exception {
        List<Map<String, Object>> entries = a.maps("elements");
        List<String> refNames = ModelText.validatePlan(entries, BULK_MAX_ENTRIES);
        boolean full = ModelText.VIEW_FULL.equals(ModelText.view(a.str("view")));
        // Allow roughly half a second per entry on top of 30 s, but never less than the default.
        long timeout = Math.max(Edt.DEFAULT_TIMEOUT_SECONDS, 30 + entries.size() / 2L);
        return Edt.write("Create " + entries.size() + " elements", project -> {
            Map<String, String> refs = new LinkedHashMap<>();
            JsonArray results = new JsonArray();
            int elements = 0;
            int relationships = 0;
            for (int i = 0; i < entries.size(); i++) {
                String refName = refNames.get(i);
                String where = "Entry " + i + (refName != null ? " (ref '" + refName + "')" : "");
                Element created;
                boolean isRelationship;
                try {
                    // Earlier entries' "ref:name" values become real ids before this entry is parsed.
                    Map<String, Object> resolved = ModelText.resolveRefs(entries.get(i), refs, where);
                    Args entry = new Args(resolved);
                    isRelationship = ModelText.isRelationshipEntry(resolved);
                    if (isRelationship) {
                        created = RelationshipCreator.create(project, RelationshipCreator.Request.from(entry));
                        relationships++;
                    } else {
                        ElementCreator.Request req = ElementCreator.Request.from(entry);
                        created = ElementCreator.create(project, req);
                        Map<String, Object> tags = entry.map("tagged_values");
                        if (tags != null && !tags.isEmpty()) {
                            applyTaggedValues(project, created, entry, req, tags);
                        }
                        elements++;
                    }
                // Rethrowing inside the session makes Edt cancel it, which rolls back every entry created so far.
                } catch (ToolException e) {
                    throw new ToolException(e.code(), where + " failed: " + e.getMessage()
                            + " Nothing was created (the whole batch was rolled back).", e.details(), e);
                } catch (Exception e) {
                    throw new ToolException("TOOL_FAILED", where + " failed: " + e
                            + ". Nothing was created (the whole batch was rolled back).", null, e);
                }
                if (refName != null) {
                    refs.put(refName, created.getID());
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                if (refName != null) {
                    item.addProperty("ref", refName);
                }
                item.addProperty("kind", isRelationship ? "relationship" : "element");
                item.addProperty("id", created.getID());
                if (full) {
                    item.add("element", ElementJson.toJson(created));
                } else {
                    item.addProperty("type", ModelSupport.typeName(created));
                    item.addProperty("name", ModelSupport.name(created));
                    Element owner = created.getOwner();
                    if (owner != null) {
                        item.addProperty("ownerId", owner.getID());
                    }
                }
                results.add(item);
            }
            JsonObject out = new JsonObject();
            out.addProperty("created", results.size());
            out.addProperty("elementCount", elements);
            out.addProperty("relationshipCount", relationships);
            out.add("refs", Gs.toElement(refs));
            out.add("results", results);
            return out;
        }, timeout);
    }

    /*
     * Sets an entry's tagged_values. The stereotype that owns the tags is tag_stereotype, else the
     * entry's stereotype, else the default SysML stereotype of its type (e.g. requirement).
     */
    private static void applyTaggedValues(Project project, Element created, Args entry, ElementCreator.Request req,
                                          Map<String, Object> tags) {
        String stName = entry.optTrim("tag_stereotype");
        if (stName == null) {
            stName = req.stereotype != null ? req.stereotype : ModelText.defaultCreationStereotype(req.kind);
        }
        if (stName == null) {
            throw ToolException.invalid("tagged_values needs a stereotype: give the entry a stereotype (or "
                    + "tag_stereotype), or use a SysML type such as Requirement or Block.");
        }
        Stereotype st = StereotypesHelper.getAppliedStereotypeByString(created, stName);
        if (st == null) {
            st = ModelSupport.findStereotype(project, stName, null);
            if (st == null || !StereotypesHelper.hasStereotype(created, st)) {
                throw ToolException.invalid("tagged_values: stereotype '" + stName + "' is not applied to the new "
                        + "element. Set the entry's stereotype.");
            }
        }
        setTags(project, created, st, Gs.toTree(tags));
    }

    // Validates every tag name against the stereotype, coerces each value to the tag's type, and sets it.
    private static int setTags(Project project, Element element, Stereotype st, JsonObject values) {
        List<String> known = TaggedValues.tagNames(st);
        int count = 0;
        for (String tag : values.keySet()) {
            if (TaggedValues.tagDefinition(st, tag) == null) {
                throw ToolException.invalid("Stereotype '" + st.getName() + "' has no tag '" + tag + "'. Tags: "
                        + known + ".");
            }
            Object value = TaggedValues.coerceForTag(project, st, tag, values.get(tag));
            StereotypesHelper.setStereotypePropertyValue(element, st, tag, value);
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ modify / move / delete

    // cameo_modify_element: rename and/or replace documentation; everything else goes through cameo_set_specification.
    static JsonObject modifyElement(Args a) throws Exception {
        String id = a.reqStr("element_id");
        String newName = a.str("name");
        String newDoc = a.str("documentation");
        if (newName == null && newDoc == null) {
            throw ToolException.invalid("Give name and/or documentation. For other properties use "
                    + "cameo_set_specification.");
        }
        return Edt.write("Modify element", project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            ModelSupport.requireEditable(element, "Element");
            if (newName != null) {
                if (!(element instanceof NamedElement)) {
                    throw ToolException.invalid(ModelSupport.describe(element) + " has no name to set.");
                }
                ((NamedElement) element).setName(newName);
            }
            if (newDoc != null) {
                ModelSupport.setDocumentation(project, element, newDoc);
            }
            JsonObject out = new JsonObject();
            out.addProperty("modified", true);
            out.add("element", ElementJson.toJson(element));
            return out;
        });
    }

    // cameo_delete_element: removes the element and everything it owns; reports what was deleted for the agent's log.
    static JsonObject deleteElement(Args a) throws Exception {
        String id = a.reqStr("element_id");
        return Edt.write("Delete element", project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            if (element == project.getPrimaryModel()) {
                throw new ToolException("PRECONDITION_FAILED", "Refusing to delete the project's primary model.");
            }
            ModelSupport.requireEditable(element, "Element");
            String name = element instanceof NamedElement ? ((NamedElement) element).getName() : null;
            String type = element.getHumanType();
            Element owner = element.getOwner();
            ModelElementsManager.getInstance().removeElement(element);
            JsonObject out = new JsonObject();
            out.addProperty("deleted", true);
            out.addProperty("elementId", id);
            if (name != null) {
                out.addProperty("name", name);
            }
            out.addProperty("type", type);
            if (owner != null) {
                out.addProperty("ownerId", owner.getID());
            }
            return out;
        });
    }

    /*
     * cameo_move_element: changes the owner. Guards against cycles and read-only elements, puts states
     * into a region of the target state machine, and verifies afterwards because Cameo can silently
     * refuse an incompatible owner.
     */
    static JsonObject moveElement(Args a) throws Exception {
        String id = a.reqStr("element_id");
        String ownerId = QueryService.alias(a, "new_owner_id", "owner_id", "parent_id");
        if (ownerId == null) {
            throw ToolException.invalid("Missing required parameter 'new_owner_id'.");
        }
        return Edt.write("Move element", project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            Element target = ModelSupport.element(project, ownerId, "new_owner_id");
            if (element == project.getPrimaryModel()) {
                throw new ToolException("PRECONDITION_FAILED", "The primary model cannot be moved.");
            }
            if (ModelSupport.isAncestorOrSelf(element, target)) {
                throw ToolException.invalid("Cannot move " + ModelSupport.describe(element) + " into "
                        + (element.getID().equals(target.getID()) ? "itself" : "its own descendant "
                        + ModelSupport.describe(target)) + ".");
            }
            ModelSupport.requireEditable(element, "Element");
            ModelSupport.requireEditable(target, "New owner");
            Element previous = element.getOwner();
            Element newOwner = target;
            if (element instanceof Vertex && !(target instanceof Region)) {
                if (target instanceof StateMachine || target instanceof State) {
                    newOwner = ElementCreator.stateRegion(project.getElementsFactory(), target,
                            element instanceof Pseudostate ? "Pseudostate" : "State");
                } else {
                    throw ToolException.invalid("States and pseudostates can only move into a Region, StateMachine "
                            + "or State, not " + ModelSupport.describe(target) + ".");
                }
            }
            if (previous != null && previous.getID().equals(newOwner.getID())) {
                JsonObject out = new JsonObject();
                out.addProperty("moved", false);
                out.addProperty("message", "The element is already owned by " + ModelSupport.describe(newOwner) + ".");
                out.add("element", ElementJson.toJsonReference(element));
                return out;
            }
            try {
                ModelElementsManager.getInstance().moveElement(element, newOwner);
            } catch (IllegalArgumentException | ClassCastException e) {
                throw ToolException.invalid(ModelSupport.describe(newOwner) + " cannot own "
                        + ModelSupport.describe(element) + ": " + e.getMessage());
            }
            Element now = element.getOwner();
            if (now == null || !now.getID().equals(newOwner.getID())) {
                throw new ToolException("PRECONDITION_FAILED", ModelSupport.describe(newOwner) + " cannot own "
                        + ModelSupport.describe(element) + " (Cameo did not accept the move). Choose an owner of a "
                        + "compatible kind, e.g. a Package for packageable elements or a Class/Block for properties.");
            }
            JsonObject out = new JsonObject();
            out.addProperty("moved", true);
            if (previous != null) {
                out.add("previousOwner", ElementJson.toJsonCompact(previous));
            }
            out.add("newOwner", ElementJson.toJsonCompact(newOwner));
            out.add("element", ElementJson.toJson(element));
            return out;
        });
    }

    // ------------------------------------------------------------------ stereotypes / profiles

    // cameo_apply_stereotype: idempotent; re-checks afterwards because Cameo ignores stereotypes that don't fit the metaclass.
    static JsonObject applyStereotype(Args a) throws Exception {
        String id = a.reqStr("element_id");
        String stereotype = a.reqStr("stereotype").trim();
        String profile = a.optTrim("profile");
        return Edt.write("Apply stereotype " + stereotype, project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            Stereotype st = ModelSupport.requireStereotypeLenient(project, stereotype, profile);
            boolean already = StereotypesHelper.hasStereotype(element, st);
            if (!already) {
                StereotypesHelper.addStereotype(element, st);
                if (!StereotypesHelper.hasStereotype(element, st)) {
                    throw new ToolException("PRECONDITION_FAILED", "Cameo did not apply «" + st.getName() + "» to "
                            + ModelSupport.describe(element) + ". The stereotype probably does not extend this "
                            + "element's metaclass, or its profile is not applied to an enclosing package.");
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("applied", true);
            out.addProperty("alreadyApplied", already);
            out.addProperty("stereotype", stereotype);
            out.addProperty("stereotypeId", st.getID());
            out.add("element", ElementJson.toJson(element));
            return out;
        });
    }

    // cameo_remove_stereotype: removes the first applied stereotype with that name (optionally from a given profile).
    static JsonObject removeStereotype(Args a) throws Exception {
        String id = a.reqStr("element_id");
        String stereotype = a.reqStr("stereotype").trim();
        String profile = a.optTrim("profile");
        return Edt.write("Remove stereotype " + stereotype, project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            ModelSupport.requireEditable(element, "Element");
            Stereotype target = null;
            List<Stereotype> applied = StereotypesHelper.getStereotypes(element);
            if (applied != null) {
                for (Stereotype st : applied) {
                    if (st == null || st.getName() == null || !st.getName().equalsIgnoreCase(stereotype)) {
                        continue;
                    }
                    if (profile != null) {
                        boolean inProfile = false;
                        for (String c : ModelSupport.contexts(st)) {
                            if (ModelText.normalizeContextToken(c).equals(ModelText.normalizeContextToken(profile))) {
                                inProfile = true;
                                break;
                            }
                        }
                        if (!inProfile) {
                            continue;
                        }
                    }
                    target = st;
                    break;
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("stereotype", stereotype);
            if (target == null) {
                out.addProperty("removed", false);
                out.addProperty("message", "Stereotype '" + stereotype + "' is not applied to this element.");
                out.add("appliedStereotypes", ModelSupport.stereotypeNames(element));
                return out;
            }
            StereotypesHelper.removeStereotype(element, target);
            out.addProperty("removed", true);
            out.addProperty("stereotypeId", target.getID());
            out.add("element", ElementJson.toJson(element));
            return out;
        });
    }

    // cameo_set_tagged_values: sets tags of a stereotype that is already applied to the element.
    static JsonObject setTaggedValues(Args a) throws Exception {
        String id = a.reqStr("element_id");
        String stereotype = a.reqStr("stereotype").trim();
        Map<String, Object> values = a.map("values");
        if (values == null) {
            throw ToolException.invalid("values must be an object mapping tag name to value.");
        }
        JsonObject gvalues = Gs.toTree(values);
        return Edt.write("Set tagged values", project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            Stereotype st = StereotypesHelper.getAppliedStereotypeByString(element, stereotype);
            if (st == null) {
                Stereotype candidate = ModelSupport.findStereotype(project, stereotype, null);
                if (candidate == null) {
                    throw new ToolException("NOT_FOUND", "Stereotype '" + stereotype + "' not found.");
                }
                if (!StereotypesHelper.hasStereotype(element, candidate)) {
                    throw new ToolException("PRECONDITION_FAILED", "Stereotype '" + stereotype + "' is not applied to "
                            + ModelSupport.describe(element) + ". Applied: " + ModelSupport.stereotypeNames(element)
                            + ". Call cameo_apply_stereotype first.");
                }
                st = candidate;
            }
            int count = setTags(project, element, st, gvalues);
            JsonObject out = new JsonObject();
            out.addProperty("updated", true);
            out.addProperty("tagCount", count);
            out.add("element", ElementJson.toJson(element));
            return out;
        });
    }

    // cameo_set_stereotype_metaclasses: replaces the metaclasses a custom stereotype extends.
    static JsonObject setStereotypeMetaclasses(Args a) throws Exception {
        String id = a.reqStr("stereotype_id");
        List<String> metaclasses = a.reqStrList("metaclasses");
        return Edt.write("Set stereotype metaclasses", project -> {
            Stereotype st = ModelSupport.typed(project, id, "stereotype_id", Stereotype.class, "a Stereotype");
            ModelSupport.requireEditable(st, "Stereotype");
            for (String n : metaclasses) {
                if (StereotypesHelper.getMetaClassByName(project, n) == null) {
                    throw ToolException.invalid("Metaclass not found: '" + n + "'. Use UML metaclass names such as "
                            + "Class, Property, Package, Activity, Port.");
                }
            }
            StereotypesHelper.setBaseClassesByName(st, metaclasses);
            JsonObject out = new JsonObject();
            out.addProperty("updated", true);
            out.addProperty("stereotypeId", st.getID());
            out.addProperty("stereotypeName", st.getName() != null ? st.getName() : "");
            JsonArray names = new JsonArray();
            List<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class> bases = StereotypesHelper.getBaseClasses(st);
            if (bases != null) {
                for (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class c : bases) {
                    if (c != null && c.getName() != null) {
                        names.add(c.getName());
                    }
                }
            }
            out.add("metaclasses", names);
            out.add("element", ElementJson.toJson(st));
            return out;
        });
    }

    // cameo_apply_profile: applies a profile (by id or name) to a package so its stereotypes can be used inside.
    static JsonObject applyProfile(Args a) throws Exception {
        String pkgId = a.reqStr("package_id");
        String profileId = a.optTrim("profile_id");
        String profileName = a.optTrim("profile_name");
        if (profileId == null && profileName == null) {
            throw ToolException.invalid("Give profile_id or profile_name.");
        }
        return Edt.write("Apply profile", project -> {
            Package pkg = ModelSupport.typed(project, pkgId, "package_id", Package.class, "a Package or Model");
            Profile profile;
            if (profileId != null) {
                profile = ModelSupport.typed(project, profileId, "profile_id", Profile.class, "a Profile");
            } else {
                profile = StereotypesHelper.getProfile(project, profileName);
                if (profile == null) {
                    throw new ToolException("NOT_FOUND", "Profile '" + profileName + "' not found. Use "
                            + "cameo_query_elements type=Profile to list profiles.");
                }
            }
            Collection<Profile> appliedProfiles = StereotypesHelper.getAppliedProfiles(pkg);
            boolean already = appliedProfiles != null && appliedProfiles.contains(profile);
            if (!already) {
                if (!StereotypesHelper.canApplyProfile(pkg, profile)) {
                    throw new ToolException("PRECONDITION_FAILED", "Profile '" + profile.getName()
                            + "' cannot be applied to " + ModelSupport.describe(pkg) + ".");
                }
                StereotypesHelper.applyProfile(pkg, profile);
                appliedProfiles = StereotypesHelper.getAppliedProfiles(pkg);
            }
            JsonObject out = new JsonObject();
            out.addProperty("packageId", pkg.getID());
            out.addProperty("packageName", pkg.getName() != null ? pkg.getName() : "");
            out.addProperty("profileId", profile.getID());
            out.addProperty("profileName", profile.getName() != null ? profile.getName() : "");
            out.addProperty("applied", !already);
            out.addProperty("alreadyApplied", already);
            JsonArray names = new JsonArray();
            if (appliedProfiles != null) {
                for (Profile p : appliedProfiles) {
                    if (p != null && p.getName() != null) {
                        names.add(p.getName());
                    }
                }
            }
            out.add("appliedProfiles", names);
            return out;
        });
    }

    // cameo_set_usecase_subject: sets or appends the classifiers a use case applies to (shown as the system boundary).
    static JsonObject setUseCaseSubject(Args a) throws Exception {
        String id = a.reqStr("element_id");
        List<String> subjectIds = a.reqStrList("subject_ids");
        boolean append = a.bool("append", false);
        return Edt.write("Set use case subject", project -> {
            UseCase uc = ModelSupport.typed(project, id, "element_id", UseCase.class, "a UseCase");
            Collection<Classifier> subjects = uc.getSubject();
            if (!append) {
                subjects.clear();
            }
            JsonArray arr = new JsonArray();
            for (String sid : subjectIds) {
                Classifier c = ModelSupport.typed(project, sid, "subject_ids", Classifier.class,
                        "a Classifier (block, component, class...)");
                if (!subjects.contains(c)) {
                    subjects.add(c);
                }
                arr.add(ModelSupport.ref(c));
            }
            JsonObject out = new JsonObject();
            out.addProperty("updated", true);
            out.addProperty("elementId", uc.getID());
            out.addProperty("append", append);
            out.addProperty("subjectCount", subjects.size());
            out.add("subjects", arr);
            out.add("element", ElementJson.toJson(uc));
            return out;
        });
    }

    /** Used by specification writes: sets one stereotype tag with type-aware coercion. */
    static void setTag(Project project, Element element, Stereotype st, String tag, JsonElement value) {
        StereotypesHelper.setStereotypePropertyValue(element, st, tag,
                TaggedValues.coerceForTag(project, st, tag, value));
    }
}
