package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.json.tree.JsonPrimitive;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions.CallBehaviorAction;
import com.nomagic.uml2.ext.magicdraw.activities.mdintermediateactivities.ActivityPartition;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKind;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.LiteralInteger;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.LiteralUnlimitedNatural;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.TypedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ValueSpecification;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Pseudostate;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.PseudostateKindEnum;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Region;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.State;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Vertex;
import com.nomagic.uml2.impl.ElementsFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Creates one element from cameo_create_element parameters. Must run inside a model session
 * (Edt.write); shared by cameo_create_element and cameo_create_elements.
 */
final class ElementCreator {

    /** Not instantiable; all members are static. */
    private ElementCreator() {
    }

    /** Parameters of one element creation, read from snake_case (or camelCase) arguments. */
    static final class Request {
        // type is what the agent sent; kind is its canonical form from ModelText.elementKind.
        /** Element type exactly as the agent sent it, used in error messages. */
        String type;
        /**
         * Canonical lower-case kind from {@link ModelText#elementKind}, for example "block" or
         * "statemachine".
         */
        String kind;
        /** Name for the new element; required, may be empty. */
        String name;
        /** Id of the owning element; required. */
        String parentId;
        /** Extra stereotype to apply by name, on top of the kind's default one, or null. */
        String stereotype;
        /** Documentation comment body, or null to leave it unset. */
        String documentation;
        /** Behavior a CallBehaviorAction should call, or null. */
        String behaviorId;
        /** Element an ActivityPartition represents, or null. */
        String representsId;
        /** Type of a typed element (Property, Port, pin), or null. */
        String typeId;
        /** Lower multiplicity bound, or null to leave it unset. */
        Integer lower;
        /** Upper multiplicity bound, -1 for unlimited, or null to leave it unset. */
        Integer upper;
        /** Multiplicity isOrdered flag, or null to leave it unchanged. */
        Boolean isOrdered;
        /** Multiplicity isUnique flag, or null to leave it unchanged. */
        Boolean isUnique;
        /** Property aggregation: none, shared or composite; null leaves the default. */
        String aggregation;
        /** Port isBehavior flag, or null. */
        Boolean isBehavior;
        /** Port isConjugated flag, or null. */
        Boolean isConjugated;
        /** Port isService flag, or null. */
        Boolean isService;
        /** FlowProperty direction (in, out, inout), or null. */
        String direction;
        /** UML metaclass names a new Stereotype extends, or null for none. */
        List<String> metaclasses;

        /**
         * Reads and validates the arguments; nothing touches the model yet, so bad input fails before any
         * session opens.
         *
         * @param a the tool arguments
         * @return the parsed request
         * @throws ToolException INVALID_PARAMS when type is unsupported, name or parent_id is missing, or a
         *     value is malformed
         */
        static Request from(Args a) {
            Request r = new Request();
            r.type = a.reqStr("type");
            r.kind = ModelText.elementKind(r.type);
            if (r.kind == null) {
                throw ModelText.unsupportedElementType(r.type);
            }
            r.name = a.str("name");
            if (r.name == null) {
                throw ToolException.invalid("Missing required parameter 'name'.");
            }
            r.parentId = a.reqStr("parent_id");
            r.stereotype = a.optTrim("stereotype");
            r.documentation = a.str("documentation");
            r.behaviorId = a.optTrim("behavior_id");
            r.representsId = a.optTrim("represents_id");
            r.typeId = a.optTrim("type_id");
            r.lower = ModelText.parseLower(a.get("lower"));
            r.upper = ModelText.parseUpper(a.get("upper"));
            r.isOrdered = a.boolOrNull("is_ordered");
            r.isUnique = a.boolOrNull("is_unique");
            r.aggregation = a.optTrim("aggregation");
            r.isBehavior = a.boolOrNull("is_behavior");
            r.isConjugated = a.boolOrNull("is_conjugated");
            r.isService = a.boolOrNull("is_service");
            r.direction = a.optTrim("direction");
            r.metaclasses = a.has("metaclasses") ? a.strList("metaclasses") : null;
            return r;
        }
    }

    /**
     * Creates the element under its parent, applies default and explicit stereotypes, then sets the
     * optional semantics (type, multiplicity, aggregation, port flags, flow direction) and documentation.
     * Any exception aborts the caller's session, so a half-configured element is never kept.
     *
     * <p>Threading: must be called inside a write session on the EDT (see {@code Edt.write}).
     *
     * @param project the open project
     * @param r the parsed request
     * @return the new element, already owned by its parent
     * @throws ToolException when the parent is missing or read-only, an option does not fit the element kind,
     *     or a
     * stereotype or metaclass cannot be found
     *
     * @throws Exception any Cameo API failure, which rolls the caller's session back
     */
    static Element create(Project project, Request r) throws Exception {
        Element parent = ModelSupport.element(project, r.parentId, "parent_id");
        ModelSupport.requireEditable(parent, "Parent");
        ElementsFactory ef = project.getElementsFactory();
        ModelElementsManager mem = ModelElementsManager.getInstance();

        Element created;
        // Stereotypes are created through the profile API so the extension to their metaclasses is wired up.
        if ("stereotype".equals(r.kind)) {
            if (!(parent instanceof Profile)) {
                throw ToolException.invalid("A Stereotype's parent must be a Profile, but parent_id is "
                        + ModelSupport.describe(parent) + ". Create a Profile first (type=Profile).");
            }
            created = StereotypesHelper.createStereotype(parent, r.name, resolveMetaclasses(project, r.metaclasses));
            if (created == null) {
                throw new ToolException("TOOL_FAILED", "Cameo did not create stereotype '" + r.name + "'.");
            }
        } else {
            created = instantiate(ef, r.kind);
            if (created instanceof NamedElement) {
                ((NamedElement) created).setName(r.name);
            }
            // A state machine needs a region before anything can be put in it, so one is created right away.
            if (created instanceof StateMachine) {
                mem.addElement(created, parent);
                ensureRegion(ef, (StateMachine) created);
            // States live in regions: put them in the parent's (first) region, creating one if needed.
            } else if (created instanceof State || created instanceof Pseudostate) {
                Region region = stateRegion(ef, parent, r.type);
                ((Vertex) created).setContainer(region);
                if (created instanceof Pseudostate) {
                    // The only pseudostate kind this tool creates is the initial state.
                    ((Pseudostate) created).setKind(PseudostateKindEnum.INITIAL);
                }
            } else {
                mem.addElement(created, parent);
            }
        }

        if (r.behaviorId != null) {
            if (!(created instanceof CallBehaviorAction)) {
                throw ToolException.invalid("behavior_id applies to CallBehaviorAction only.");
            }
            ((CallBehaviorAction) created).setBehavior(
                    ModelSupport.typed(project, r.behaviorId, "behavior_id", Behavior.class, "an Activity or other Behavior"));
        }
        if (r.representsId != null) {
            if (!(created instanceof ActivityPartition)) {
                throw ToolException.invalid("represents_id applies to ActivityPartition only.");
            }
            ((ActivityPartition) created).setRepresents(ModelSupport.element(project, r.representsId, "represents_id"));
        }
        // SysML kinds (block, requirement, ...) get their stereotype automatically; an explicit stereotype is added on top.
        for (String stName : ModelText.effectiveCreationStereotypes(r.kind, r.stereotype)) {
            Stereotype st = ModelSupport.findStereotype(project, stName, null);
            if (st == null) {
                boolean sysml = ModelText.defaultCreationStereotype(r.kind) != null
                        && stName.equalsIgnoreCase(ModelText.defaultCreationStereotype(r.kind));
                throw new ToolException(sysml ? "PRECONDITION_FAILED" : "NOT_FOUND", "Stereotype '" + stName
                        + "' not found" + (sysml ? ": creating a " + r.type + " needs the SysML profile, which this "
                        + "project does not use. Create a SysML project, or create a plain Class instead."
                        : ". Check the name or apply its profile with cameo_apply_profile."));
            }
            if (!StereotypesHelper.hasStereotype(created, st)) {
                StereotypesHelper.addStereotype(created, st);
            }
        }
        applySemantics(project, ef, created, r);
        if (r.documentation != null && !r.documentation.isEmpty()) {
            ModelSupport.setDocumentation(project, created, r.documentation);
        }
        return created;
    }

    /**
     * A new, unowned UML element for a canonical kind. SysML kinds share the UML metaclass their stereotype
     * extends.
     *
     * @param ef the project's elements factory
     * @param kind a canonical kind from {@link ModelText#elementKind}
     * @return the new element, not yet added to any owner
     * @throws ToolException INVALID_PARAMS for a kind this method does not know
     */
    static Element instantiate(ElementsFactory ef, String kind) {
        switch (kind) {
            case "package":
                return ef.createPackageInstance();
            case "profile":
                return ef.createProfileInstance();
            case "block":
            case "class":
            case "requirement":
            case "interfaceblock":
            case "constraintblock":
                return ef.createClassInstance();
            case "usecase":
                return ef.createUseCaseInstance();
            case "activity":
                return ef.createActivityInstance();
            case "actor":
                return ef.createActorInstance();
            case "statemachine":
                return ef.createStateMachineInstance();
            case "state":
                return ef.createStateInstance();
            case "pseudostate":
                return ef.createPseudostateInstance();
            case "interface":
                return ef.createInterfaceInstance();
            case "valuetype":
            case "datatype":
                return ef.createDataTypeInstance();
            case "signal":
                return ef.createSignalInstance();
            case "property":
            case "flowproperty":
                return ef.createPropertyInstance();
            case "operation":
                return ef.createOperationInstance();
            case "port":
                return ef.createPortInstance();
            case "enumeration":
                return ef.createEnumerationInstance();
            case "component":
                return ef.createComponentInstance();
            case "constraint":
                return ef.createConstraintInstance();
            case "comment":
                return ef.createCommentInstance();
            case "callbehavioraction":
                return ef.createCallBehaviorActionInstance();
            case "activitypartition":
                return ef.createActivityPartitionInstance();
            case "initialnode":
                return ef.createInitialNodeInstance();
            case "activityfinalnode":
                return ef.createActivityFinalNodeInstance();
            case "decisionnode":
                return ef.createDecisionNodeInstance();
            case "mergenode":
                return ef.createMergeNodeInstance();
            case "forknode":
                return ef.createForkNodeInstance();
            case "joinnode":
                return ef.createJoinNodeInstance();
            case "flowfinalnode":
                return ef.createFlowFinalNodeInstance();
            case "inputpin":
                return ef.createInputPinInstance();
            case "outputpin":
                return ef.createOutputPinInstance();
            case "opaqueaction":
                return ef.createOpaqueActionInstance();
            default:
                throw ModelText.unsupportedElementType(kind);
        }
    }

    /**
     * Region that should own a new State/Pseudostate under the given parent.
     *
     * @param ef the project's elements factory, used if a region has to be created
     * @param parent the element the agent named as parent
     * @param type the element type as the agent sent it, for the error message
     * @return the region to put the new vertex in
     * @throws ToolException INVALID_PARAMS when the parent is not a Region, StateMachine or State
     */
    static Region stateRegion(ElementsFactory ef, Element parent, String type) {
        if (parent instanceof Region) {
            return (Region) parent;
        }
        if (parent instanceof StateMachine) {
            return ensureRegion(ef, (StateMachine) parent);
        }
        if (parent instanceof State) {
            return ensureRegion(ef, (State) parent);
        }
        throw ToolException.invalid("The parent of a " + type + " must be a Region, StateMachine or State, but it is "
                + ModelSupport.describe(parent) + ".");
    }

    /**
     * The state machine's first region, creating one if it has none.
     *
     * @param ef the project's elements factory
     * @param sm the state machine
     * @return its first region, possibly new
     */
    static Region ensureRegion(ElementsFactory ef, StateMachine sm) {
        Collection<Region> regions = sm.getRegion();
        if (regions != null && !regions.isEmpty()) {
            return regions.iterator().next();
        }
        Region region = ef.createRegionInstance();
        region.setStateMachine(sm);
        return region;
    }

    /**
     * The composite state's first region, creating one if it has none.
     *
     * @param ef the project's elements factory
     * @param state the state, which becomes composite if a region is added
     * @return its first region, possibly new
     */
    static Region ensureRegion(ElementsFactory ef, State state) {
        Collection<Region> regions = state.getRegion();
        if (regions != null && !regions.isEmpty()) {
            return regions.iterator().next();
        }
        Region region = ef.createRegionInstance();
        region.setState(state);
        return region;
    }

    /**
     * Looks up the UML metaclasses named for a new stereotype.
     *
     * @param project the open project, whose UML metamodel is searched
     * @param names metaclass names such as Class or Property, or null
     * @return the metaclasses in the given order; empty when names is null
     * @throws ToolException INVALID_PARAMS when a name is not a known metaclass
     */
    private static Collection<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class> resolveMetaclasses(
            Project project, List<String> names) {
        List<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String n : names) {
            com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class mc = StereotypesHelper.getMetaClassByName(project, n);
            if (mc == null) {
                throw ToolException.invalid("Metaclass not found: '" + n + "'. Use UML metaclass names such as "
                        + "Class, Property, Package, Activity, Port.");
            }
            out.add(mc);
        }
        return out;
    }

    /**
     * Applies the optional settings that only some element kinds support: type, multiplicity, aggregation,
     * port flags and flow direction. Each one checks the element kind first and explains a mismatch.
     *
     * @param project the open project
     * @param ef the project's elements factory, for new multiplicity literals
     * @param created the new element
     * @param r the request holding the optional settings
     * @throws ToolException INVALID_PARAMS when a setting does not apply to the element kind
     */
    private static void applySemantics(Project project, ElementsFactory ef, Element created, Request r) {
        if (r.typeId != null) {
            if (!(created instanceof TypedElement)) {
                throw ToolException.invalid("type_id is only supported for typed elements (Property, Port, pins...), "
                        + "not " + created.getHumanType() + ".");
            }
            ((TypedElement) created).setType(
                    ModelSupport.typed(project, r.typeId, "type_id", Type.class, "a Type (Class, Block, DataType, ...)"));
        }
        if (r.lower != null || r.upper != null || r.isOrdered != null || r.isUnique != null) {
            if (!(created instanceof MultiplicityElement)) {
                throw ToolException.invalid("Multiplicity fields are only supported for multiplicity elements "
                        + "(Property, Port, pins...), not " + created.getHumanType() + ".");
            }
            MultiplicityElement me = (MultiplicityElement) created;
            if (r.lower != null) {
                setLower(ef, me, r.lower);
            }
            if (r.upper != null) {
                setUpper(ef, me, r.upper);
            }
            if (r.isOrdered != null) {
                me.setOrdered(r.isOrdered);
            }
            if (r.isUnique != null) {
                me.setUnique(r.isUnique);
            }
        }
        if (r.aggregation != null) {
            if (!(created instanceof Property)) {
                throw ToolException.invalid("aggregation is only supported for Property, not "
                        + created.getHumanType() + ".");
            }
            ((Property) created).setAggregation(aggregation(r.aggregation));
        }
        if (r.isBehavior != null || r.isConjugated != null || r.isService != null) {
            if (!(created instanceof Port)) {
                throw ToolException.invalid("Port flags are only supported for Port, not "
                        + created.getHumanType() + ".");
            }
            Port port = (Port) created;
            if (r.isBehavior != null) {
                port.setBehavior(r.isBehavior);
            }
            if (r.isConjugated != null) {
                port.setConjugated(r.isConjugated);
            }
            if (r.isService != null) {
                port.setService(r.isService);
            }
        }
        if (r.direction != null) {
            if (!(created instanceof Property)) {
                throw ToolException.invalid("direction is only supported for FlowProperty, not "
                        + created.getHumanType() + ".");
            }
            Stereotype fp = ModelSupport.flowPropertyStereotype(created);
            if (fp == null) {
                throw ToolException.invalid("direction requires the FlowProperty stereotype (use type=FlowProperty).");
            }
            StereotypesHelper.setStereotypePropertyValue(created, fp, "direction",
                    TaggedValues.coerceForTag(project, fp, "direction", new JsonPrimitive(r.direction)));
        }
    }

    /**
     * Sets the lower bound, reusing the existing literal if there is one.
     *
     * @param ef the project's elements factory, used when no literal exists yet
     * @param me the element to change
     * @param lower the new lower bound
     */
    static void setLower(ElementsFactory ef, MultiplicityElement me, int lower) {
        ValueSpecification existing = me.getLowerValue();
        if (existing instanceof LiteralInteger) {
            ((LiteralInteger) existing).setValue(lower);
            return;
        }
        LiteralInteger v = ef.createLiteralIntegerInstance();
        v.setValue(lower);
        me.setLowerValue(v);
    }

    /**
     * Sets the upper bound; -1 means unlimited ("*"), which UML stores as a LiteralUnlimitedNatural.
     *
     * @param ef the project's elements factory, used when no literal exists yet
     * @param me the element to change
     * @param upper the new upper bound; any negative value means unlimited
     */
    static void setUpper(ElementsFactory ef, MultiplicityElement me, int upper) {
        int value = upper < 0 ? -1 : upper;
        ValueSpecification existing = me.getUpperValue();
        if (existing instanceof LiteralUnlimitedNatural) {
            ((LiteralUnlimitedNatural) existing).setValue(value);
            return;
        }
        LiteralUnlimitedNatural v = ef.createLiteralUnlimitedNaturalInstance();
        v.setValue(value);
        me.setUpperValue(v);
    }

    /**
     * none, shared or composite (by name or literal), as Cameo's AggregationKind.
     *
     * @param text the aggregation text, matched without case
     * @return the matching aggregation kind
     * @throws ToolException INVALID_PARAMS for any other value
     */
    static AggregationKind aggregation(String text) {
        String t = text.trim().toLowerCase();
        AggregationKind resolved = AggregationKindEnum.getByName(t);
        if (resolved == null) {
            resolved = AggregationKindEnum.get(t);
        }
        if (resolved == null) {
            throw ToolException.invalid("Unsupported aggregation value '" + text + "'. Expected none, shared or composite.");
        }
        return resolved;
    }
}
