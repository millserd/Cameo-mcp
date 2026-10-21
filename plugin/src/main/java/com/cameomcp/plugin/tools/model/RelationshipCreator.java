package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ControlFlow;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ObjectFlow;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.Activity;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode;
import com.nomagic.uml2.ext.magicdraw.auxiliaryconstructs.mdinformationflows.InformationFlow;
import com.nomagic.uml2.ext.magicdraw.classes.mddependencies.Abstraction;
import com.nomagic.uml2.ext.magicdraw.classes.mddependencies.Dependency;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Association;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Generalization;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.LiteralString;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectorEnd;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.StructuredClassifier;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.ext.magicdraw.mdusecases.Extend;
import com.nomagic.uml2.ext.magicdraw.mdusecases.Include;
import com.nomagic.uml2.ext.magicdraw.mdusecases.UseCase;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Region;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Transition;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Vertex;
import com.nomagic.uml2.impl.ElementsFactory;

import java.util.List;

/** Creates one relationship from cameo_create_relationship parameters; runs inside a model session. */
final class RelationshipCreator {

    /** Not instantiable; all members are static. */
    private RelationshipCreator() {
    }

    /** Parameters of one relationship creation; validated before any session opens. */
    static final class Request {
        /** Relationship type exactly as the agent sent it, used in messages and results. */
        String type;
        /** Canonical lower-case kind from {@link ModelText#relationshipKind}, for example "satisfy". */
        String kind;
        /** Id of the source end (client, specific classifier, including use case and so on); required. */
        String sourceId;
        /** Id of the target end (supplier, general classifier, addition and so on); required. */
        String targetId;
        /** Name for the new relationship, or null to leave it unnamed. */
        String name;
        /** Guard text for control flows, object flows and transitions, or null. */
        String guard;
        /** Explicit owner id, or null to use the default owner for the kind. Required for connectors. */
        String ownerId;
        /** Part through which the connector's source port is reached, or null. */
        String sourcePartWithPortId;
        /** Part through which the connector's target port is reached, or null. */
        String targetPartWithPortId;
        /** Connector that realizes an information flow, or null. */
        String realizingConnectorId;
        /** Classifiers an information flow conveys, or null. */
        List<String> conveyedIds;
        /**
         * Property an ItemFlow carries (its itemProperty tag), or null. Giving it also applies the ItemFlow
         * stereotype.
         */
        String itemPropertyId;

        /**
         * Reads and validates the arguments without touching the model.
         *
         * @param a the tool arguments
         * @return the parsed request
         * @throws ToolException INVALID_PARAMS for an unsupported type or a missing source_id or target_id
         */
        static Request from(Args a) {
            Request r = new Request();
            r.type = a.reqStr("type");
            r.kind = ModelText.relationshipKind(r.type);
            if (r.kind == null) {
                throw ModelText.unsupportedRelationshipType(r.type);
            }
            r.sourceId = a.reqStr("source_id");
            r.targetId = a.reqStr("target_id");
            r.name = a.str("name");
            r.guard = a.str("guard");
            r.ownerId = a.optTrim("owner_id");
            r.sourcePartWithPortId = a.optTrim("source_part_with_port_id");
            r.targetPartWithPortId = a.optTrim("target_part_with_port_id");
            r.realizingConnectorId = a.optTrim("realizing_connector_id");
            r.conveyedIds = a.has("conveyed_ids") ? a.strList("conveyed_ids") : null;
            r.itemPropertyId = a.optTrim("item_property_id");
            return r;
        }
    }

    /**
     * Creates the relationship of the requested kind between source and target, checking that both
     * ends have the metaclass the kind requires. Each kind is owned where UML (and Cameo's
     * containment tree) expects it: generalizations under the specific classifier, dependencies
     * and abstractions in a package, flows in the activity, transitions in the region.
     *
     * <p>Threading: must be called inside a write session on the EDT.
     *
     * @param project the open project
     * @param r the parsed request
     * @return the new relationship
     * @throws ToolException INVALID_PARAMS when an end has the wrong kind or the owner does not fit,
     *     NOT_FOUND for a
     * missing end, owner or referenced element, PRECONDITION_FAILED or CONFLICT for a missing or ambiguous
     * SysML stereotype
     *
     * @throws Exception any Cameo failure, which rolls the caller's session back
     */
    static Element create(Project project, Request r) throws Exception {
        Element source = ModelSupport.element(project, r.sourceId, "source_id");
        Element target = ModelSupport.element(project, r.targetId, "target_id");
        ElementsFactory ef = project.getElementsFactory();
        Element rel;
        switch (r.kind) {
            case "generalization":
                rel = generalization(ef, source, target);
                break;
            case "include":
                rel = include(ef, source, target);
                break;
            case "extend":
                rel = extend(ef, source, target);
                break;
            case "dependency":
                rel = dependency(ef, project, source, target, r.ownerId);
                break;
            case "association":
                rel = association(ef, source, target, false, false);
                break;
            case "directedassociation":
                rel = association(ef, source, target, true, false);
                break;
            case "composition":
                rel = association(ef, source, target, true, true);
                break;
            case "controlflow":
                rel = activityFlow(ef, project, ef.createControlFlowInstance(), source, target, r.guard, r.ownerId,
                        "ControlFlow");
                break;
            case "objectflow":
                rel = activityFlow(ef, project, ef.createObjectFlowInstance(), source, target, r.guard, r.ownerId,
                        "ObjectFlow");
                break;
            case "allocate":
            case "satisfy":
            case "verify":
            case "derive":
            case "refine":
            case "trace":
                rel = abstraction(ef, project, source, target, r.ownerId, ModelText.abstractionStereotype(r.kind));
                break;
            case "transition":
                rel = transition(ef, source, target, r.guard);
                break;
            case "connector":
                rel = connector(ef, project, source, target, r);
                break;
            case "informationflow":
                rel = informationFlow(ef, project, source, target, r, false);
                break;
            case "itemflow":
                rel = informationFlow(ef, project, source, target, r, true);
                break;
            default:
                throw ModelText.unsupportedRelationshipType(r.type);
        }
        if (r.name != null && rel instanceof NamedElement) {
            ((NamedElement) rel).setName(r.name);
        }
        return rel;
    }

    /**
     * Builds the INVALID_PARAMS error naming both ends, so the agent sees which one has the wrong kind.
     *
     * @param what the rule that was broken
     * @param source the source element
     * @param target the target element
     * @return the exception to throw
     */
    private static ToolException wrongEnds(String what, Element source, Element target) {
        return ToolException.invalid(what + ". Got source=" + ModelSupport.describe(source) + ", target="
                + ModelSupport.describe(target) + ".");
    }

    /**
     * Creates a generalization: source is the specific classifier, target the general one. Owned by the
     * source.
     *
     * @param ef the elements factory
     * @param source the specific classifier
     * @param target the general classifier
     * @return the new generalization
     * @throws ToolException INVALID_PARAMS when either end is not a Classifier
     * @throws Exception any Cameo failure while adding it
     */
    private static Generalization generalization(ElementsFactory ef, Element source, Element target) throws Exception {
        if (!(source instanceof Classifier) || !(target instanceof Classifier)) {
            throw wrongEnds("Generalization requires Classifier source (specific) and target (general)", source, target);
        }
        Generalization g = ef.createGeneralizationInstance();
        g.setSpecific((Classifier) source);
        g.setGeneral((Classifier) target);
        ModelElementsManager.getInstance().addElement(g, source);
        return g;
    }

    /**
     * Creates an include: source includes target. Owned by the including use case.
     *
     * @param ef the elements factory
     * @param source the including use case
     * @param target the included use case
     * @return the new include
     * @throws ToolException INVALID_PARAMS when either end is not a UseCase
     * @throws Exception any Cameo failure while adding it
     */
    private static Include include(ElementsFactory ef, Element source, Element target) throws Exception {
        if (!(source instanceof UseCase) || !(target instanceof UseCase)) {
            throw wrongEnds("Include requires UseCase source (including) and target (addition)", source, target);
        }
        Include inc = ef.createIncludeInstance();
        inc.setIncludingCase((UseCase) source);
        inc.setAddition((UseCase) target);
        ModelElementsManager.getInstance().addElement(inc, source);
        return inc;
    }

    /**
     * Creates an extend: source extends target. Owned by the extending use case.
     *
     * @param ef the elements factory
     * @param source the extending use case
     * @param target the extended use case
     * @return the new extend
     * @throws ToolException INVALID_PARAMS when either end is not a UseCase
     * @throws Exception any Cameo failure while adding it
     */
    private static Extend extend(ElementsFactory ef, Element source, Element target) throws Exception {
        if (!(source instanceof UseCase) || !(target instanceof UseCase)) {
            throw wrongEnds("Extend requires UseCase source (extension) and target (extended case)", source, target);
        }
        Extend ext = ef.createExtendInstance();
        ext.setExtension((UseCase) source);
        ext.setExtendedCase((UseCase) target);
        ModelElementsManager.getInstance().addElement(ext, source);
        return ext;
    }

    /**
     * Creates a dependency: source (client) depends on target (supplier). Owned by a package, see
     * {@link #packagedOwner}.
     *
     * @param ef the elements factory
     * @param project the open project
     * @param source the client
     * @param target the supplier
     * @param ownerId explicit owner package id, or null
     * @return the new dependency
     * @throws ToolException INVALID_PARAMS when an end is not a NamedElement or the owner is not a package
     * @throws Exception any Cameo failure while adding it
     */
    private static Dependency dependency(ElementsFactory ef, Project project, Element source, Element target,
                                         String ownerId) throws Exception {
        if (!(source instanceof NamedElement) || !(target instanceof NamedElement)) {
            throw wrongEnds("Dependency requires NamedElement source and target", source, target);
        }
        Dependency dep = ef.createDependencyInstance();
        dep.getClient().add((NamedElement) source);
        dep.getSupplier().add((NamedElement) target);
        ModelElementsManager.getInstance().addElement(dep, packagedOwner(project, ownerId, source, "Dependency"));
        return dep;
    }

    /**
     * Creates an association typed by source and target. Of the two owned ends, index 0 is typed by the
     * source and index 1 by the target. Composition makes the source end composite (the whole). Directed
     * makes the target end association-owned, so only the source can navigate to it. The association is owned
     * by the source's owner.
     *
     * @param ef the elements factory
     * @param source the source type
     * @param target the target type
     * @param directed true for a directed association or composition
     * @param composition true for a composition
     * @return the new association
     * @throws ToolException INVALID_PARAMS when an end is not a Type or the source has no owner, TOOL_FAILED
     *     when
     * Cameo did not create the two ends
     *
     * @throws Exception any Cameo failure while adding it
     */
    private static Association association(ElementsFactory ef, Element source, Element target, boolean directed,
                                           boolean composition) throws Exception {
        if (!(source instanceof Type) || !(target instanceof Type)) {
            throw wrongEnds("Association requires Type source and target (classes, blocks, actors...)", source, target);
        }
        Association assoc = ef.createAssociationInstance();
        List<Property> ends = assoc.getOwnedEnd();
        if (ends == null || ends.size() < 2) {
            throw new ToolException("TOOL_FAILED", "Cameo created an association without its two owned ends.");
        }
        Property sourceEnd = ends.get(0);
        sourceEnd.setType((Type) source);
        Property targetEnd = ends.get(1);
        targetEnd.setType((Type) target);
        if (composition) {
            sourceEnd.setAggregation(AggregationKindEnum.COMPOSITE);
        }
        if (directed) {
            targetEnd.setAssociation(assoc);
        }
        Element owner = source.getOwner();
        if (owner == null) {
            throw ToolException.invalid("The association source has no owner to hold the association: "
                    + ModelSupport.describe(source) + ".");
        }
        ModelElementsManager.getInstance().addElement(assoc, owner);
        return assoc;
    }

    /**
     * Sets up a control or object flow, owned by the activity that contains the source node unless owner_id
     * says otherwise. If neither gives an owner, the edge is left unowned.
     *
     * @param ef the elements factory, for the guard literal
     * @param project the open project
     * @param edge the new, empty edge
     * @param source the source node
     * @param target the target node
     * @param guard guard text, or null or empty for none
     * @param ownerId explicit owner id, or null
     * @param what "ControlFlow" or "ObjectFlow", for error messages
     * @return the edge
     * @throws ToolException INVALID_PARAMS when an end is not an ActivityNode, NOT_FOUND for a missing owner
     * @throws Exception any Cameo failure while adding it
     */
    private static ActivityEdge activityFlow(ElementsFactory ef, Project project, ActivityEdge edge, Element source,
                                             Element target, String guard, String ownerId, String what)
            throws Exception {
        if (!(source instanceof ActivityNode) || !(target instanceof ActivityNode)) {
            throw wrongEnds(what + " requires ActivityNode source and target (actions, control nodes, pins)",
                    source, target);
        }
        Element owner = ModelSupport.optionalElement(project, ownerId, "owner_id");
        if (owner == null) {
            owner = source.getOwner();
        }
        if (owner instanceof Activity) {
            ((Activity) owner).getEdge().add(edge);
        } else if (owner != null) {
            ModelElementsManager.getInstance().addElement(edge, owner);
        }
        edge.setSource((ActivityNode) source);
        edge.setTarget((ActivityNode) target);
        if (guard != null && !guard.isEmpty()) {
            LiteralString spec = ef.createLiteralStringInstance();
            spec.setValue(guard);
            edge.setGuard(spec);
        }
        return edge;
    }

    /**
     * Creates a transition inside the shared region of its two vertices. The guard becomes a Constraint named
     * "guard" with a text specification.
     *
     * @param ef the elements factory
     * @param source the source vertex
     * @param target the target vertex
     * @param guard guard text, or null or empty for none
     * @return the new transition
     * @throws ToolException INVALID_PARAMS when an end is not a Vertex, is not in a region, or the regions
     *     differ
     */
    private static Transition transition(ElementsFactory ef, Element source, Element target, String guard) {
        if (!(source instanceof Vertex) || !(target instanceof Vertex)) {
            throw wrongEnds("Transition requires Vertex source and target (states, pseudostates)", source, target);
        }
        Region sr = ((Vertex) source).getContainer();
        Region tr = ((Vertex) target).getContainer();
        if (sr == null || tr == null) {
            throw ToolException.invalid("Transition endpoints must already belong to a Region.");
        }
        if (!sr.getID().equals(tr.getID())) {
            throw ToolException.invalid("Transition source and target must share the same Region (source region "
                    + sr.getID() + ", target region " + tr.getID() + ").");
        }
        Transition t = ef.createTransitionInstance();
        t.setContainer(sr);
        t.setSource((Vertex) source);
        t.setTarget((Vertex) target);
        if (guard != null && !guard.isEmpty()) {
            Constraint c = ef.createConstraintInstance();
            c.setName("guard");
            c.setContext(sr);
            LiteralString spec = ef.createLiteralStringInstance();
            spec.setValue(guard);
            c.setSpecification(spec);
            t.setGuard(c);
        }
        return t;
    }

    /**
     * Creates an information flow in a package, optionally conveying classifiers and realized by a connector.
     * An ItemFlow (or any flow given item_property_id) also gets the SysML ItemFlow stereotype and its item
     * property.
     *
     * @param ef the elements factory
     * @param project the open project
     * @param source the information source
     * @param target the information target
     * @param r the request, for owner, conveyed items, realizing connector and item property
     * @param itemFlow true to apply the ItemFlow stereotype
     * @return the new flow
     * @throws ToolException INVALID_PARAMS when an end is not a NamedElement or a referenced element has the
     *     wrong
     * kind, NOT_FOUND for a missing reference, PRECONDITION_FAILED or CONFLICT for the ItemFlow stereotype
     *
     * @throws Exception any Cameo failure while adding it
     */
    private static InformationFlow informationFlow(ElementsFactory ef, Project project, Element source, Element target,
                                                   Request r, boolean itemFlow) throws Exception {
        if (!(source instanceof NamedElement) || !(target instanceof NamedElement)) {
            throw wrongEnds("InformationFlow requires NamedElement source and target", source, target);
        }
        Package owner = packageFromContext(project, r.ownerId, source, "InformationFlow");
        InformationFlow flow = ef.createInformationFlowInstance();
        flow.getInformationSource().add((NamedElement) source);
        flow.getInformationTarget().add((NamedElement) target);
        if (r.conveyedIds != null) {
            for (String id : r.conveyedIds) {
                flow.getConveyed().add(ModelSupport.typed(project, id, "conveyed_ids", Classifier.class,
                        "a Classifier (block, signal, value type...)"));
            }
        }
        if (r.realizingConnectorId != null) {
            flow.getRealizingConnector().add(ModelSupport.typed(project, r.realizingConnectorId,
                    "realizing_connector_id", Connector.class, "a Connector"));
        }
        ModelElementsManager.getInstance().addElement(flow, owner);
        if (itemFlow || r.itemPropertyId != null) {
            Stereotype st = ModelSupport.requireStereotypeStrict(project, "ItemFlow");
            if (!StereotypesHelper.hasStereotype(flow, st)) {
                StereotypesHelper.addStereotype(flow, st);
            }
            if (r.itemPropertyId != null) {
                Property item = ModelSupport.typed(project, r.itemPropertyId, "item_property_id", Property.class,
                        "a Property");
                StereotypesHelper.setStereotypePropertyValue(flow, st, "itemProperty", item);
            }
        }
        return flow;
    }

    /**
     * Creates a connector inside the owning block (the IBD context). Each end's role is the part or port; the
     * part-with-port names the part through which a nested port is reached.
     *
     * @param ef the elements factory
     * @param project the open project
     * @param source the source role
     * @param target the target role
     * @param r the request, for owner_id and the part-with-port ids
     * @return the new connector
     * @throws ToolException INVALID_PARAMS when an end is not connectable, owner_id is missing, or a
     *     referenced
     * element has the wrong kind; NOT_FOUND for a missing reference
     */
    private static Connector connector(ElementsFactory ef, Project project, Element source, Element target, Request r) {
        if (!(source instanceof ConnectableElement) || !(target instanceof ConnectableElement)) {
            throw wrongEnds("Connector requires ConnectableElement source and target (parts, ports, properties)",
                    source, target);
        }
        if (r.ownerId == null) {
            throw ToolException.invalid("Connector requires owner_id: the block / structured classifier that owns "
                    + "the connected parts (the IBD's context).");
        }
        StructuredClassifier owner = ModelSupport.typed(project, r.ownerId, "owner_id", StructuredClassifier.class,
                "a StructuredClassifier (block, class, component)");
        Connector connector = ef.createConnectorInstance();
        owner.getOwnedConnector().add(connector);
        // Cameo may create the two ends with the connector or leave them to us; handle both.
        List<ConnectorEnd> ends = connector.getEnd();
        ConnectorEnd se = ends.size() > 0 ? ends.get(0) : ef.createConnectorEndInstance();
        if (ends.isEmpty()) {
            ends.add(se);
        }
        se.setRole((ConnectableElement) source);
        Property spwp = partWithPort(project, r.sourcePartWithPortId, "source_part_with_port_id");
        if (spwp != null) {
            se.setPartWithPort(spwp);
        }
        ConnectorEnd te = ends.size() > 1 ? ends.get(1) : ef.createConnectorEndInstance();
        if (ends.size() < 2) {
            ends.add(te);
        }
        te.setRole((ConnectableElement) target);
        Property tpwp = partWithPort(project, r.targetPartWithPortId, "target_part_with_port_id");
        if (tpwp != null) {
            te.setPartWithPort(tpwp);
        }
        return connector;
    }

    /**
     * Looks up the optional part-with-port for a connector end.
     *
     * @param project the open project
     * @param id the part's id, or null
     * @param param the parameter name, for error messages
     * @return the part property, or null when no id was given
     * @throws ToolException INVALID_PARAMS when the id is not a Property, NOT_FOUND when it does not resolve
     */
    private static Property partWithPort(Project project, String id, String param) {
        if (id == null) {
            return null;
        }
        return ModelSupport.typed(project, id, param, Property.class, "a part Property");
    }

    /**
     * Creates a SysML stereotyped Abstraction (Satisfy, Verify, Allocate, DeriveReqt, Refine or Trace), owned
     * by a package, see {@link #packagedOwner}.
     *
     * @param ef the elements factory
     * @param project the open project
     * @param source the client
     * @param target the supplier
     * @param ownerId explicit owner package id, or null
     * @param stereotypeName the SysML stereotype to apply
     * @return the new abstraction
     * @throws ToolException INVALID_PARAMS when an end is not a NamedElement or the owner is not a package,
     * PRECONDITION_FAILED or CONFLICT for a missing or ambiguous stereotype
     *
     * @throws Exception any Cameo failure while adding it
     */
    private static Abstraction abstraction(ElementsFactory ef, Project project, Element source, Element target,
                                           String ownerId, String stereotypeName) throws Exception {
        if (!(source instanceof NamedElement) || !(target instanceof NamedElement)) {
            throw wrongEnds(stereotypeName + " requires NamedElement source and target", source, target);
        }
        Stereotype st = ModelSupport.requireStereotypeStrict(project, stereotypeName);
        Abstraction a = ef.createAbstractionInstance();
        a.getClient().add((NamedElement) source);
        a.getSupplier().add((NamedElement) target);
        StereotypesHelper.addStereotype(a, st);
        ModelElementsManager.getInstance().addElement(a, packagedOwner(project, ownerId, source, stereotypeName));
        return a;
    }

    /**
     * Explicit package owner, else the nearest package above the source, else the primary model.
     *
     * @param project the open project
     * @param ownerId explicit owner id, or null
     * @param source the relationship's source; its owners are searched (not the source itself)
     * @param what the relationship kind, for the error message
     * @return the owner package
     * @throws ToolException INVALID_PARAMS when owner_id is not a package, NOT_FOUND when it does not resolve
     */
    static Package packagedOwner(Project project, String ownerId, Element source, String what) {
        if (ownerId != null) {
            Element owner = ModelSupport.element(project, ownerId, "owner_id");
            if (!(owner instanceof Package)) {
                throw ToolException.invalid(what + " owner must be a Package or Model, but owner_id is "
                        + ModelSupport.describe(owner) + ". Omit owner_id to use the source's containing package.");
            }
            return (Package) owner;
        }
        Element cur = source;
        while (cur != null) {
            cur = cur.getOwner();
            if (cur instanceof Package) {
                return (Package) cur;
            }
        }
        return com.cameomcp.plugin.cameo.ProjectKind.primaryModel(project);
    }

    /**
     * Nearest package at or above the context element (owner_id or the source).
     *
     * @param project the open project
     * @param ownerId context element id, or null to use the fallback
     * @param fallback the element to start from when ownerId is null
     * @param what the relationship kind; currently unused
     * @return the nearest package, or the primary model when none is found
     * @throws ToolException NOT_FOUND when ownerId does not resolve
     */
    static Package packageFromContext(Project project, String ownerId, Element fallback, String what) {
        Element context = ownerId != null ? ModelSupport.element(project, ownerId, "owner_id") : fallback;
        while (context != null) {
            if (context instanceof Package) {
                return (Package) context;
            }
            context = context.getOwner();
        }
        return com.cameomcp.plugin.cameo.ProjectKind.primaryModel(project);
    }
}
