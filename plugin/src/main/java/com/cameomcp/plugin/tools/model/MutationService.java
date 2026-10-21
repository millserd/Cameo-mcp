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

    /**
     * Most entries one cameo_create_elements call may hold. Larger plans risk the EDT timeout and one huge
     * undo step.
     */
    static final int BULK_MAX_ENTRIES = 500;

    /** Not instantiable; all members are static. */
    private MutationService() {
    }

    // ------------------------------------------------------------------ create

    /**
     * Implements cameo_create_element. The request is parsed and validated before the session opens, then the
     * element is created in one write session (one undo step) on the EDT.
     *
     * @param a the tool arguments, see {@link ElementCreator.Request#from}
     * @return {@code created: true} and the full {@code element}
     * @throws ToolException for bad input, a missing parent or stereotype, or no open project
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_create_relationship, with the same pattern as {@link #createElement}.
     *
     * @param a the tool arguments, see {@code RelationshipCreator.Request.from}
     * @return {@code created: true}, the {@code relationshipType} as sent, and the full {@code relationship}
     * @throws ToolException for bad input, missing ends or stereotypes, or no open project
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * cameo_create_elements: all entries in one session; any failure rolls everything back.
     *
     * <p>Entries are created in order, so a later entry can name an earlier one with "ref:name" in any id
     * field. The timeout grows with the plan size.
     *
     * @param a the tool arguments: {@code elements} (the entries) and {@code view} (compact or full)
     * @return the counts, the {@code refs} map from ref name to id, and one {@code results} item per entry
     * @throws ToolException for an invalid plan, or naming the failed entry; nothing is kept in either case
     * @throws Exception any failure while handing the work to the EDT
     */
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

    /**
     * Sets an entry's tagged_values. The stereotype that owns the tags is tag_stereotype, else the entry's
     * stereotype, else the default SysML stereotype of its type (for example requirement).
     *
     * @param project the open project
     * @param created the element the entry just created
     * @param entry the entry's arguments, for tag_stereotype
     * @param req the entry's parsed request
     * @param tags tag name to plain JSON value
     * @throws ToolException INVALID_PARAMS when no stereotype can be found, it is not applied, or a tag is
     *     unknown
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

    /**
     * Validates every tag name against the stereotype, coerces each value to the tag's type, and sets it.
     * Stops at the first unknown tag; tags set before it stay set unless the caller's session is cancelled.
     *
     * @param project the open project
     * @param element the element whose tags are set
     * @param st a stereotype applied to the element
     * @param values tag name to JSON value
     * @return the number of tags set
     * @throws ToolException INVALID_PARAMS for an unknown tag or a bad enumeration literal
     */
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

    /**
     * Implements cameo_modify_element: renames the element and/or replaces its documentation. Every other
     * property goes through cameo_set_specification.
     *
     * @param a the tool arguments: {@code element_id}, and at least one of {@code name} and {@code
     *     documentation}
     * @return {@code modified: true} and the full {@code element}
     * @throws ToolException INVALID_PARAMS when neither field is given or the element has no name, NOT_FOUND
     *     or
     * PRECONDITION_FAILED for a missing or read-only element
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_delete_element: removes the element and everything it owns, and reports what was
     * deleted.
     *
     * @param a the tool arguments: {@code element_id}
     * @return {@code deleted: true}, the {@code elementId}, {@code name}, {@code type} and {@code ownerId}
     * @throws ToolException NOT_FOUND for a missing element, PRECONDITION_FAILED for the primary model or a
     *     read-only
     * element
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_move_element: changes the owner. Guards against cycles and read-only elements, puts
     * states into a region of the target state machine or state, and checks the owner afterwards because
     * Cameo can silently refuse an owner of the wrong kind.
     *
     * @param a the tool arguments: {@code element_id} and {@code new_owner_id} (or its aliases owner_id and
     * parent_id)
     *
     * @return {@code moved: false} with a message when the owner is unchanged; otherwise {@code moved: true},
     *     the
     * previous and new owner, and the full {@code element}
     *
     * @throws ToolException INVALID_PARAMS for a cyclic move or an owner of the wrong kind, NOT_FOUND for a
     *     missing
     * element, PRECONDITION_FAILED for the primary model, a read-only element, or a move Cameo did not accept
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
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

    /**
     * Implements cameo_apply_stereotype. Applying a stereotype that is already applied is not an error. The
     * element is checked afterwards because Cameo ignores a stereotype that does not extend its metaclass.
     *
     * @param a the tool arguments: {@code element_id}, {@code stereotype} and an optional {@code profile}
     * @return {@code applied: true}, {@code alreadyApplied}, the stereotype name and id, and the full {@code
     *     element}
     * @throws ToolException NOT_FOUND for a missing element or stereotype, PRECONDITION_FAILED when Cameo did
     *     not
     * apply it
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_remove_stereotype: removes the first applied stereotype with that name, ignoring case,
     * and optionally only one from the given profile.
     *
     * @param a the tool arguments: {@code element_id}, {@code stereotype} and an optional {@code profile}
     * @return {@code removed: false} with the applied stereotype names when nothing matched; otherwise
     * {@code removed: true}, the stereotype id and the full {@code element}
     *
     * @throws ToolException NOT_FOUND for a missing element, PRECONDITION_FAILED for a read-only element
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_set_tagged_values: sets tags of a stereotype that is already applied to the element.
     *
     * @param a the tool arguments: {@code element_id}, {@code stereotype} and {@code values} (tag name to
     *     value)
     * @return {@code updated: true}, the {@code tagCount} and the full {@code element}
     * @throws ToolException INVALID_PARAMS for missing values or an unknown tag, NOT_FOUND for a missing
     *     element or
     * stereotype, PRECONDITION_FAILED when the stereotype is not applied
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_set_stereotype_metaclasses: replaces the metaclasses a custom stereotype extends.
     *
     * @param a the tool arguments: {@code stereotype_id} and {@code metaclasses} (UML metaclass names)
     * @return {@code updated: true}, the stereotype id and name, its {@code metaclasses} after the change,
     *     and the
     * full {@code element}
     *
     * @throws ToolException INVALID_PARAMS for an unknown metaclass or an id that is not a stereotype,
     *     NOT_FOUND or
     * PRECONDITION_FAILED for a missing or read-only stereotype
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_apply_profile: applies a profile, by id or name, to a package so its stereotypes can
     * be used inside it. Applying a profile that is already applied is not an error.
     *
     * @param a the tool arguments: {@code package_id}, and {@code profile_id} or {@code profile_name}
     * @return the package and profile ids and names, {@code applied}, {@code alreadyApplied} and the
     *     package's
     * {@code appliedProfiles}
     *
     * @throws ToolException INVALID_PARAMS when neither profile parameter is given, NOT_FOUND for a missing
     *     package
     * or profile, PRECONDITION_FAILED when Cameo says the profile cannot be applied
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Implements cameo_set_usecase_subject: sets, or with append adds to, the classifiers a use case applies
     * to. The subject is drawn as the system boundary.
     *
     * @param a the tool arguments: {@code element_id}, {@code subject_ids} and {@code append} (default false)
     * @return {@code updated: true}, {@code subjectCount}, the given {@code subjects} and the full {@code
     *     element}
     * @throws ToolException INVALID_PARAMS when an id is not a use case or classifier, NOT_FOUND for a
     *     missing element
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * Used by specification writes: sets one stereotype tag with type-aware coercion.
     *
     * <p>Threading: must run inside a write session on the EDT.
     *
     * @param project the open project
     * @param element the element whose tag is set
     * @param st a stereotype applied to the element
     * @param tag the tag name
     * @param value the JSON value; JSON null clears the tag
     * @throws ToolException INVALID_PARAMS for a bad enumeration literal
     */
    static void setTag(Project project, Element element, Stereotype st, String tag, JsonElement value) {
        StereotypesHelper.setStereotypePropertyValue(element, st, tag,
                TaggedValues.coerceForTag(project, st, tag, value));
    }
}
