package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.google.gson.JsonPrimitive;
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

    private ElementCreator() {
    }

    /** Parameters of one element creation, read from snake_case (or camelCase) arguments. */
    static final class Request {
        String type;
        String kind;
        String name;
        String parentId;
        String stereotype;
        String documentation;
        String behaviorId;
        String representsId;
        String typeId;
        Integer lower;
        Integer upper;
        Boolean isOrdered;
        Boolean isUnique;
        String aggregation;
        Boolean isBehavior;
        Boolean isConjugated;
        Boolean isService;
        String direction;
        List<String> metaclasses;

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

    static Element create(Project project, Request r) throws Exception {
        Element parent = ModelSupport.element(project, r.parentId, "parent_id");
        ModelSupport.requireEditable(parent, "Parent");
        ElementsFactory ef = project.getElementsFactory();
        ModelElementsManager mem = ModelElementsManager.getInstance();

        Element created;
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
            if (created instanceof StateMachine) {
                mem.addElement(created, parent);
                ensureRegion(ef, (StateMachine) created);
            } else if (created instanceof State || created instanceof Pseudostate) {
                Region region = stateRegion(ef, parent, r.type);
                ((Vertex) created).setContainer(region);
                if (created instanceof Pseudostate) {
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

    /** Region that should own a new State/Pseudostate under the given parent. */
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

    static Region ensureRegion(ElementsFactory ef, StateMachine sm) {
        Collection<Region> regions = sm.getRegion();
        if (regions != null && !regions.isEmpty()) {
            return regions.iterator().next();
        }
        Region region = ef.createRegionInstance();
        region.setStateMachine(sm);
        return region;
    }

    static Region ensureRegion(ElementsFactory ef, State state) {
        Collection<Region> regions = state.getRegion();
        if (regions != null && !regions.isEmpty()) {
            return regions.iterator().next();
        }
        Region region = ef.createRegionInstance();
        region.setState(state);
        return region;
    }

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
