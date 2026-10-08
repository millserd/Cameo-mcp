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

    private RelationshipCreator() {
    }

    static final class Request {
        String type;
        String kind;
        String sourceId;
        String targetId;
        String name;
        String guard;
        String ownerId;
        String sourcePartWithPortId;
        String targetPartWithPortId;
        String realizingConnectorId;
        List<String> conveyedIds;
        String itemPropertyId;

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

    private static ToolException wrongEnds(String what, Element source, Element target) {
        return ToolException.invalid(what + ". Got source=" + ModelSupport.describe(source) + ", target="
                + ModelSupport.describe(target) + ".");
    }

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

    private static Property partWithPort(Project project, String id, String param) {
        if (id == null) {
            return null;
        }
        return ModelSupport.typed(project, id, param, Property.class, "a part Property");
    }

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

    /** Explicit package owner, else the nearest package above the source, else the primary model. */
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
        Package model = project.getPrimaryModel();
        if (model == null) {
            throw ToolException.invalid("Could not find a package to own the " + what + "; pass owner_id.");
        }
        return model;
    }

    /** Nearest package at or above the context element (owner_id or the source). */
    static Package packageFromContext(Project project, String ownerId, Element fallback, String what) {
        Element context = ownerId != null ? ModelSupport.element(project, ownerId, "owner_id") : fallback;
        while (context != null) {
            if (context instanceof Package) {
                return (Package) context;
            }
            context = context.getOwner();
        }
        Package model = project.getPrimaryModel();
        if (model == null) {
            throw ToolException.invalid("Could not find a package to own the " + what + "; pass a package owner_id.");
        }
        return model;
    }
}
