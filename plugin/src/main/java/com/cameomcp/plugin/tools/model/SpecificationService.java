package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.cameomcp.json.tree.JsonPrimitive;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityParameterNode;
import com.nomagic.uml2.ext.magicdraw.activities.mdintermediateactivities.ActivityPartition;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.LiteralString;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Parameter;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ParameterDirectionKind;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ParameterDirectionKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.TypedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ValueSpecification;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.VisibilityKind;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.VisibilityKindEnum;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** cameo_get_specification / cameo_set_specification: Specification-window style property access. */
final class SpecificationService {

    /** UML features read via JMI reflection and accepted by the generic setter. */
    static final String[] STANDARD_FEATURES = {
            "name", "visibility", "isAbstract", "isFinalSpecialization", "isLeaf", "isStatic", "isQuery",
            "isReadOnly", "isDerived", "isDerivedUnion", "isOrdered", "isUnique", "lower", "upper", "isActive",
            "aggregation", "direction", "isBehavior", "isConjugated", "isService", "represents", "parameter", "type"
    };

    /** Not instantiable; all members are static. */
    private SpecificationService() {
    }

    // ------------------------------------------------------------------ read

    /**
     * Implements cameo_get_specification: standard UML properties, documentation, every applied stereotype
     * with its tagged values, and named owned constraints. This is roughly what Cameo's Specification window
     * shows.
     *
     * @param a the tool arguments: {@code element_id}
     * @return {@code elementId}, {@code name}, {@code type}, {@code properties}, and {@code documentation},
     * {@code appliedStereotypes} and {@code constraints} when present
     *
     * @throws ToolException NOT_FOUND for a missing element, NO_PROJECT when no project is open
     * @throws Exception any failure while handing the work to the EDT
     */
    static JsonObject get(Args a) throws Exception {
        String id = a.reqStr("element_id");
        return Edt.read(project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            JsonObject response = new JsonObject();
            response.addProperty("elementId", element.getID());
            if (element instanceof NamedElement) {
                response.addProperty("name", ModelSupport.name(element));
            }
            response.addProperty("type", element.getHumanType());
            response.add("properties", standardProperties(element));
            String doc = ModelSupport.documentation(element);
            if (doc != null) {
                response.addProperty("documentation", doc);
            }
            JsonArray stereotypes = appliedStereotypes(element);
            if (stereotypes.size() > 0) {
                response.add("appliedStereotypes", stereotypes);
            }
            JsonObject constraints = ownedConstraints(element);
            if (constraints.size() > 0) {
                response.add("constraints", constraints);
            }
            return response;
        });
    }

    /**
     * Reads each standard feature through JMI reflection ({@code refGetValue}). Features the metaclass lacks
     * throw and are skipped, so one list works for every metaclass.
     *
     * @param element the element
     * @return feature name to JSON value, for the non-null features
     */
    private static JsonObject standardProperties(Element element) {
        JsonObject props = new JsonObject();
        for (String feature : STANDARD_FEATURES) {
            try {
                Object value = element.refGetValue(feature);
                if (value != null) {
                    addValue(props, feature, value);
                }
            } catch (Throwable ignored) {
                // feature not defined for this metaclass
            }
        }
        try {
            Object subject = element.refGetValue("subject");
            if (subject instanceof Collection) {
                JsonArray arr = new JsonArray();
                for (Object item : (Collection<?>) subject) {
                    if (item instanceof NamedElement) {
                        arr.add(ModelSupport.ref((Element) item));
                    }
                }
                if (arr.size() > 0) {
                    props.add("subject", arr);
                }
            }
        } catch (Throwable ignored) {
            // not a use case
        }
        if (element instanceof Property) {
            Stereotype fp = ModelSupport.flowPropertyStereotype(element);
            if (fp != null) {
                String dir = ElementJson.readFlowPropertyDirection((Property) element, fp);
                if (dir != null && !dir.isEmpty()) {
                    props.addProperty("direction", dir);
                }
            }
        }
        return props;
    }

    /**
     * Adds a feature value as JSON: elements become {id, name}, collections become arrays, enums their name.
     *
     * @param target the object to add to
     * @param name the feature name
     * @param value the non-null value
     */
    private static void addValue(JsonObject target, String name, Object value) {
        if (value instanceof Boolean) {
            target.addProperty(name, (Boolean) value);
        } else if (value instanceof Number) {
            target.addProperty(name, (Number) value);
        } else if (value instanceof VisibilityKind || value instanceof String) {
            target.addProperty(name, value.toString());
        } else if (value instanceof Element) {
            target.add(name, ModelSupport.ref((Element) value));
        } else if (value instanceof Collection) {
            JsonArray arr = new JsonArray();
            for (Object item : (Collection<?>) value) {
                if (item instanceof Element) {
                    arr.add(ModelSupport.ref((Element) item));
                } else if (item != null) {
                    arr.add(String.valueOf(item));
                }
            }
            if (arr.size() > 0) {
                target.add(name, arr);
            }
        } else {
            target.addProperty(name, String.valueOf(value));
        }
    }

    /**
     * Lists the applied stereotypes with their profile and non-empty tag values.
     *
     * @param element the element
     * @return one object per stereotype; empty when there are none or Cameo throws
     */
    @SuppressWarnings("rawtypes")
    private static JsonArray appliedStereotypes(Element element) {
        JsonArray result = new JsonArray();
        List<Stereotype> stereotypes;
        try {
            stereotypes = StereotypesHelper.getStereotypes(element);
        } catch (Exception e) {
            return result;
        }
        if (stereotypes == null) {
            return result;
        }
        for (Stereotype st : stereotypes) {
            JsonObject so = new JsonObject();
            so.addProperty("stereotype", st.getName());
            try {
                Profile profile = st.getProfile();
                if (profile != null) {
                    so.addProperty("profile", profile.getName());
                }
            } catch (Throwable e) {
                // Older releases lack Stereotype.getProfile(); this helper finds the owning profile instead.
                try {
                    com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package pkg =
                            StereotypesHelper.getProfileForStereotype(st);
                    if (pkg != null) {
                        so.addProperty("profile", pkg.getName());
                    }
                } catch (Throwable ignored) {
                    // no profile name
                }
            }
            JsonObject tags = new JsonObject();
            for (String tag : TaggedValues.tagNames(st)) {
                try {
                    List values = StereotypesHelper.getStereotypePropertyValue(element, st, tag);
                    if (values != null && !values.isEmpty()) {
                        JsonElement s = TaggedValues.serialize(values.size() == 1 ? values.get(0) : values);
                        if (s != null) {
                            tags.add(tag, s);
                        }
                    }
                } catch (Exception ignored) {
                    // unreadable tag
                }
            }
            if (tags.size() > 0) {
                so.add("taggedValues", tags);
            }
            result.add(so);
        }
        return result;
    }

    /**
     * Named constraints owned by the element, as name to body text. Unnamed constraints are skipped.
     *
     * @param element the element
     * @return the constraints; empty when there are none or Cameo throws
     */
    private static JsonObject ownedConstraints(Element element) {
        JsonObject out = new JsonObject();
        try {
            Collection<Element> owned = element.getOwnedElement();
            if (owned != null) {
                for (Element child : owned) {
                    if (child instanceof Constraint) {
                        String name = ((Constraint) child).getName();
                        if (name == null || name.isEmpty()) {
                            continue;
                        }
                        String body = constraintBody((Constraint) child);
                        out.addProperty(name, body != null ? body : "");
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return out;
    }

    /**
     * A constraint's text: the literal string, the first opaque body, or the spec's "value". Null if none.
     *
     * @param c the constraint
     * @return the text, or null when there is no specification or it cannot be read
     */
    static String constraintBody(Constraint c) {
        try {
            ValueSpecification spec = c.getSpecification();
            if (spec == null) {
                return null;
            }
            if (spec instanceof LiteralString) {
                return ((LiteralString) spec).getValue();
            }
            if (spec instanceof OpaqueExpression) {
                List<String> bodies = ((OpaqueExpression) spec).getBody();
                return bodies != null && !bodies.isEmpty() ? bodies.get(0) : null;
            }
            try {
                Object v = spec.refGetValue("value");
                return v != null ? String.valueOf(v) : null;
            } catch (Throwable e) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ write

    /**
     * Implements cameo_set_specification. Each property name is first matched against the tags of the applied
     * stereotypes, then against the standard UML features. Unknown names are reported back rather than
     * failing, so one typo does not undo the other changes; invalid values do fail the call. Constraints are
     * set or created by name.
     *
     * @param a the tool arguments: {@code element_id}, and {@code properties} and/or {@code constraints}
     *     (name to
     * value)
     *
     * @return {@code updated}, {@code setCount}, {@code setProperties}, and {@code setConstraints},
     * {@code unrecognized} with a {@code hint}, and {@code failed} when they apply, plus the full {@code
     * element}
     *
     * @throws ToolException INVALID_PARAMS when both maps are empty or a value is invalid, NOT_FOUND or
     * PRECONDITION_FAILED for a missing or read-only element
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
    static JsonObject set(Args a) throws Exception {
        String id = a.reqStr("element_id");
        Map<String, Object> props = a.map("properties");
        Map<String, Object> constraints = a.map("constraints");
        boolean hasProps = props != null && !props.isEmpty();
        boolean hasConstraints = constraints != null && !constraints.isEmpty();
        if (!hasProps && !hasConstraints) {
            throw ToolException.invalid("Give a non-empty 'properties' and/or 'constraints' object.");
        }
        JsonObject gprops = hasProps ? Gs.toTree(props) : new JsonObject();
        JsonObject gconstraints = hasConstraints ? Gs.toTree(constraints) : new JsonObject();
        return Edt.write("Set specification", project -> {
            Element element = ModelSupport.element(project, id, "element_id");
            ModelSupport.requireEditable(element, "Element");
            // Tag name -> applied stereotype that defines it; a name defined by several keeps the last one.
            Map<String, Stereotype> tagOwner = new LinkedHashMap<>();
            List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
            if (stereotypes != null) {
                for (Stereotype st : stereotypes) {
                    for (String tag : TaggedValues.tagNames(st)) {
                        tagOwner.put(tag, st);
                    }
                }
            }
            JsonArray setProps = new JsonArray();
            JsonArray unrecognized = new JsonArray();
            JsonObject failures = new JsonObject();
            int count = 0;
            for (String name : gprops.keySet()) {
                JsonElement value = gprops.get(name);
                if (tagOwner.containsKey(name)) {
                    MutationService.setTag(project, element, tagOwner.get(name), name, value);
                    setProps.add(name);
                    count++;
                    continue;
                }
                boolean handled;
                try {
                    handled = setStandard(element, name, value, project);
                } catch (ToolException e) {
                    throw e;
                } catch (Exception e) {
                    failures.addProperty(name, String.valueOf(e.getMessage() != null ? e.getMessage() : e));
                    continue;
                }
                if (handled) {
                    setProps.add(name);
                    count++;
                } else {
                    unrecognized.add(name);
                }
            }
            JsonArray setConstraints = new JsonArray();
            for (String cname : gconstraints.keySet()) {
                JsonElement v = gconstraints.get(cname);
                String text = v == null || v.isJsonNull() ? "" : (v.isJsonPrimitive() ? v.getAsString() : v.toString());
                setOrCreateConstraint(project, element, cname, text);
                setConstraints.add(cname);
                count++;
            }
            JsonObject out = new JsonObject();
            out.addProperty("updated", true);
            out.addProperty("setCount", count);
            out.add("setProperties", setProps);
            if (setConstraints.size() > 0) {
                out.add("setConstraints", setConstraints);
            }
            if (unrecognized.size() > 0) {
                out.add("unrecognized", unrecognized);
                out.addProperty("hint", "Unrecognized names are neither tags of an applied stereotype nor settable "
                        + "UML properties of a " + element.getHumanType() + ". Check spelling with "
                        + "cameo_get_specification.");
            }
            if (failures.size() > 0) {
                out.add("failed", failures);
            }
            out.add("element", ElementJson.toJson(element));
            return out;
        });
    }

    /**
     * Reads a value as text; JSON null is rejected.
     *
     * @param value the value
     * @param name the property name, for the error message
     * @return the string value, or the JSON text of an object or array
     * @throws ToolException INVALID_PARAMS for null or JSON null
     */
    private static String text(JsonElement value, String name) {
        if (value == null || value.isJsonNull()) {
            throw ToolException.invalid(name + " needs a value.");
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /**
     * Reads a strict boolean: true or false, as JSON booleans or strings ignoring case.
     *
     * @param value the value
     * @param name the property name, for the error message
     * @return the boolean
     * @throws ToolException INVALID_PARAMS for anything else
     */
    private static boolean bool(JsonElement value, String name) {
        if (value != null && value.isJsonPrimitive()) {
            JsonPrimitive p = value.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            String s = p.getAsString().trim().toLowerCase();
            if (s.equals("true") || s.equals("false")) {
                return Boolean.parseBoolean(s);
            }
        }
        throw ToolException.invalid(name + " must be true or false.");
    }

    /**
     * Sets one standard UML feature with the metaclass check and conversion it needs.
     *
     * @param element the element to change
     * @param name the feature name, one of {@code STANDARD_FEATURES}
     * @param value the new value
     * @param project the open project, for new literals and type lookups
     * @return false when the feature does not apply to this element, so the caller can report it as
     *     unrecognized
     * @throws ToolException INVALID_PARAMS for a value of the wrong form
     * @throws Exception any other Cameo failure, which the caller reports in {@code failed}
     */
    private static boolean setStandard(Element element, String name, JsonElement value, Project project)
            throws Exception {
        switch (name) {
            case "name":
                if (element instanceof NamedElement) {
                    ((NamedElement) element).setName(text(value, name));
                    return true;
                }
                return false;
            case "visibility":
                if (element instanceof NamedElement) {
                    VisibilityKind vk = VisibilityKindEnum.getByName(text(value, name).trim().toLowerCase());
                    if (vk == null) {
                        throw ToolException.invalid("visibility must be public, private, protected or package.");
                    }
                    ((NamedElement) element).setVisibility(vk);
                    return true;
                }
                return false;
            case "isAbstract":
                if (element instanceof Classifier) {
                    ((Classifier) element).setAbstract(bool(value, name));
                    return true;
                }
                return false;
            case "isFinalSpecialization":
                if (element instanceof Classifier) {
                    ((Classifier) element).setFinalSpecialization(bool(value, name));
                    return true;
                }
                return false;
            case "documentation":
                ModelSupport.setDocumentation(project, element, text(value, name));
                return true;
            case "type":
                return setType(element, value, project);
            case "direction":
                if (element instanceof Parameter) {
                    ((Parameter) element).setDirection(parameterDirection(text(value, name)));
                    return true;
                }
                if (element instanceof Property) {
                    Stereotype fp = ModelSupport.flowPropertyStereotype(element);
                    if (fp != null) {
                        MutationService.setTag(project, element, fp, "direction", value);
                        return true;
                    }
                }
                return false;
            case "parameter":
                if (element instanceof ActivityParameterNode) {
                    Object v = TaggedValues.coerceGeneric(project, idObject(value));
                    if (!(v instanceof Parameter)) {
                        throw ToolException.invalid("parameter must reference a Parameter element by id.");
                    }
                    ((ActivityParameterNode) element).setParameter((Parameter) v);
                    return true;
                }
                return false;
            case "lower":
            case "upper":
                if (!(element instanceof MultiplicityElement)) {
                    return false;
                }
                Object raw = value == null || value.isJsonNull() ? null : Gs.fromTree(value);
                if ("lower".equals(name)) {
                    Integer lower = ModelText.parseLower(raw);
                    if (lower == null) {
                        return false;
                    }
                    ElementCreator.setLower(project.getElementsFactory(), (MultiplicityElement) element, lower);
                } else {
                    Integer upper = ModelText.parseUpper(raw);
                    if (upper == null) {
                        return false;
                    }
                    ElementCreator.setUpper(project.getElementsFactory(), (MultiplicityElement) element, upper);
                }
                return true;
            case "isOrdered":
                if (element instanceof MultiplicityElement) {
                    ((MultiplicityElement) element).setOrdered(bool(value, name));
                    return true;
                }
                return false;
            case "isUnique":
                if (element instanceof MultiplicityElement) {
                    ((MultiplicityElement) element).setUnique(bool(value, name));
                    return true;
                }
                return false;
            case "aggregation":
                if (element instanceof Property) {
                    ((Property) element).setAggregation(ElementCreator.aggregation(text(value, name)));
                    return true;
                }
                return false;
            case "isBehavior":
                if (element instanceof Port) {
                    ((Port) element).setBehavior(bool(value, name));
                    return true;
                }
                return false;
            case "isConjugated":
                if (element instanceof Port) {
                    ((Port) element).setConjugated(bool(value, name));
                    return true;
                }
                return false;
            case "isService":
                if (element instanceof Port) {
                    ((Port) element).setService(bool(value, name));
                    return true;
                }
                return false;
            case "represents":
                if (element instanceof ActivityPartition) {
                    Object v = TaggedValues.coerceGeneric(project, idObject(value));
                    if (!(v instanceof Element)) {
                        throw ToolException.invalid("represents must reference an element by id.");
                    }
                    ((ActivityPartition) element).setRepresents((Element) v);
                    return true;
                }
                return false;
            default:
                // Any other standard feature: let JMI set it with generic coercion.
                for (String f : STANDARD_FEATURES) {
                    if (f.equals(name)) {
                        element.refSetValue(name, TaggedValues.coerceGeneric(project, value));
                        return true;
                    }
                }
                return false;
        }
    }

    /** Accept "id" strings as {"id": "..."} so they resolve to elements. */
    private static JsonElement idObject(JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            JsonObject o = new JsonObject();
            o.add("id", value);
            return o;
        }
        return value;
    }

    /**
     * Sets the type of a typed element. The value is an id, an {"id": ...} object, or null to clear the type.
     *
     * @param element the element
     * @param value the type reference, or null or JSON null
     * @param project the open project
     * @return false when the element is not a TypedElement
     * @throws ToolException INVALID_PARAMS for a bad value or a non-Type element, NOT_FOUND for a missing
     *     type
     */
    private static boolean setType(Element element, JsonElement value, Project project) {
        if (!(element instanceof TypedElement)) {
            return false;
        }
        if (value == null || value.isJsonNull()) {
            ((TypedElement) element).setType(null);
            return true;
        }
        String typeId = null;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            typeId = value.getAsString();
        } else if (value.isJsonObject() && value.getAsJsonObject().has("id")) {
            typeId = value.getAsJsonObject().get("id").getAsString();
        }
        if (typeId == null || typeId.isEmpty()) {
            throw ToolException.invalid("type must be an element id or {\"id\": \"...\"} (null clears it).");
        }
        ((TypedElement) element).setType(ModelSupport.typed(project, typeId, "type", Type.class,
                "a Type (Class, Block, DataType, ValueType, Signal...)"));
        return true;
    }

    /**
     * Converts a direction keyword to Cameo's enum.
     *
     * @param text the keyword, see {@link ModelText#parameterDirection}
     * @return the enum value
     * @throws ToolException INVALID_PARAMS for an unknown keyword
     */
    private static ParameterDirectionKind parameterDirection(String text) {
        switch (ModelText.parameterDirection(text)) {
            case "in":
                return ParameterDirectionKindEnum.IN;
            case "out":
                return ParameterDirectionKindEnum.OUT;
            case "inout":
                return ParameterDirectionKindEnum.INOUT;
            default:
                return ParameterDirectionKindEnum.RETURN;
        }
    }

    /**
     * Updates the body of the owned constraint with that name, ignoring case, or creates one.
     *
     * @param project the open project
     * @param owner the constrained element
     * @param name the constraint name
     * @param text the new body
     * @throws Exception any Cameo failure while adding a new constraint
     */
    private static void setOrCreateConstraint(Project project, Element owner, String name, String text)
            throws Exception {
        for (Element child : owner.getOwnedElement()) {
            if (child instanceof Constraint && name.equalsIgnoreCase(((Constraint) child).getName())) {
                setConstraintText(project, (Constraint) child, text);
                return;
            }
        }
        Constraint c = project.getElementsFactory().createConstraintInstance();
        c.setName(name);
        setConstraintText(project, c, text);
        ModelElementsManager.getInstance().addElement(c, owner);
    }

    /**
     * Rewrites the constraint text in whatever specification form it already has. A new or other
     * specification
     * becomes an OpaqueExpression.
     *
     * @param project the open project, for a new specification
     * @param c the constraint
     * @param text the new body
     */
    private static void setConstraintText(Project project, Constraint c, String text) {
        ValueSpecification existing = c.getSpecification();
        if (existing instanceof OpaqueExpression) {
            List<String> bodies = ((OpaqueExpression) existing).getBody();
            bodies.clear();
            bodies.add(text);
            return;
        }
        if (existing instanceof LiteralString) {
            ((LiteralString) existing).setValue(text);
            return;
        }
        OpaqueExpression oe = project.getElementsFactory().createOpaqueExpressionInstance();
        oe.getBody().add(text);
        c.setSpecification(oe);
    }
}
