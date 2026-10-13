package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.uml.ClassTypes;
import com.nomagic.magicdraw.uml.Finder;
import com.nomagic.magicdraw.uml2.Connectors;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode;
import com.nomagic.uml2.ext.magicdraw.auxiliaryconstructs.mdinformationflows.InformationFlow;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.DirectedRelationship;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Relationship;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectorEnd;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Element queries, containment browsing and relationship listing (all read-only). */
final class QueryService {

    static final int QUERY_DEFAULT_LIMIT = 200;
    static final int QUERY_MAX_LIMIT = 1000;
    static final int TREE_DEFAULT_DEPTH = 3;
    static final int TREE_MAX_DEPTH = 10;
    static final int CHILD_DEFAULT_LIMIT = 50;
    static final int CHILD_MAX_LIMIT = 500;

    private QueryService() {
    }

    /** First non-blank value among parameter aliases. */
    static String alias(Args a, String... names) {
        for (String n : names) {
            String v = a.optTrim(n);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static int limit(Args a, int dflt, int max) {
        Long v = a.lng("limit");
        if (v == null) {
            return dflt;
        }
        if (v < 1) {
            throw ToolException.invalid("limit must be greater than zero.");
        }
        return (int) Math.min(v, max);
    }

    private static int offset(Args a) {
        Long v = a.lng("offset");
        if (v == null) {
            return 0;
        }
        if (v < 0) {
            throw ToolException.invalid("offset must be zero or greater.");
        }
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ get element

    static JsonObject getElement(Args a) throws Exception {
        String id = a.reqStr("element_id");
        return Edt.read(project -> ElementJson.toJson(ModelSupport.element(project, id, "element_id")));
    }

    // ------------------------------------------------------------------ query

    static JsonObject queryElements(Args a) throws Exception {
        String typeFilter = a.optTrim("type");
        String nameFilter = a.optTrim("name");
        String scopeId = alias(a, "package_id", "package_name", "package", "owner_id", "root_id");
        String stereotypeFilter = a.optTrim("stereotype");
        boolean recursive = a.bool("recursive", true);
        String view = ModelText.view(a.str("view"));
        int limit = limit(a, QUERY_DEFAULT_LIMIT, QUERY_MAX_LIMIT);
        int offset = offset(a);

        return Edt.read(project -> {
            Element scope = ModelSupport.root(project, scopeId, "package_id");
            Class<?> metaclass = null;
            if (typeFilter != null) {
                metaclass = resolveMetaclass(typeFilter);
                if (metaclass == null) {
                    throw ToolException.invalid("Unknown type filter '" + typeFilter + "'. Use a UML metaclass name "
                            + "such as Class, Package, Property, Port, Activity, State, UseCase; SysML concepts are "
                            + "stereotypes (type=Class, stereotype=block).");
                }
            }
            Collection<? extends Element> candidates;
            if (metaclass != null && nameFilter != null) {
                candidates = recursive ? Finder.byNameAllRecursively().find(scope, metaclass, nameFilter)
                        : Finder.byNameAll().find(scope, metaclass, nameFilter);
            } else if (metaclass != null) {
                candidates = recursive ? Finder.byTypeRecursively().find(scope, new Class[]{metaclass})
                        : Finder.byType().find(scope, metaclass);
            } else if (nameFilter != null) {
                candidates = recursive ? Finder.byNameAllRecursively().find(scope, NamedElement.class, nameFilter)
                        : Finder.byNameAll().find(scope, NamedElement.class, nameFilter);
            } else {
                candidates = recursive ? Finder.byTypeRecursively().find(scope, new Class[]{Element.class})
                        : scope.getOwnedElement();
            }
            List<Element> filtered = new ArrayList<>();
            if (candidates != null) {
                for (Element el : candidates) {
                    if (el == null) {
                        continue;
                    }
                    if (stereotypeFilter != null && !ModelSupport.hasStereotypeNamed(el, stereotypeFilter)) {
                        continue;
                    }
                    filtered.add(el);
                }
            }
            filtered.sort(ModelSupport.BY_TYPE_NAME_ID);

            int total = filtered.size();
            int[] range = new int[2];
            Map<String, Object> paging = ModelText.page(total, offset, limit, range);
            JsonArray elements = new JsonArray();
            for (int i = range[0]; i < range[1]; i++) {
                Element el = filtered.get(i);
                elements.add(ModelText.VIEW_FULL.equals(view) ? ElementJson.toJson(el) : ElementJson.toJsonCompact(el));
            }
            JsonObject response = new JsonObject();
            response.addProperty("view", view);
            response.addProperty("count", elements.size());
            response.addProperty("returned", elements.size());
            response.addProperty("totalCount", total);
            merge(response, paging);
            JsonObject filters = new JsonObject();
            if (typeFilter != null) {
                filters.addProperty("type", typeFilter);
            }
            if (nameFilter != null) {
                filters.addProperty("name", nameFilter);
            }
            if (scopeId != null) {
                filters.addProperty("package", scopeId);
            }
            if (stereotypeFilter != null) {
                filters.addProperty("stereotype", stereotypeFilter);
            }
            filters.addProperty("recursive", recursive);
            response.add("filters", filters);
            response.add("elements", elements);
            return response;
        });
    }

    static void merge(JsonObject target, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            target.add(e.getKey(), Gs.toElement(e.getValue()));
        }
    }

    static Class<?> resolveMetaclass(String typeInput) {
        String[] candidates = {ModelText.normalizeTypeName(typeInput), typeInput,
                typeInput.substring(0, 1).toUpperCase() + typeInput.substring(1)};
        for (String c : candidates) {
            try {
                Class<?> clazz = ClassTypes.getClassType(c);
                if (clazz != null) {
                    return clazz;
                }
            } catch (Exception ignored) {
                // try the next spelling
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ containment

    static JsonObject containmentTree(Args a) throws Exception {
        String rootId = alias(a, "root_id", "parent_id");
        Long d = a.lng("depth");
        int depth = d == null ? TREE_DEFAULT_DEPTH : (int) Math.min(Math.max(d, 1), TREE_MAX_DEPTH);
        String view = ModelText.view(a.str("view"));
        boolean full = ModelText.VIEW_FULL.equals(view);
        return Edt.read(project -> {
            Element root = ModelSupport.root(project, rootId, "root_id");
            JsonObject response = new JsonObject();
            response.addProperty("rootId", root.getID());
            response.addProperty("depth", depth);
            response.addProperty("view", view);
            response.add("tree", treeNode(root, depth, 0, full));
            return response;
        });
    }

    private static JsonObject treeNode(Element element, int maxDepth, int depth, boolean full) {
        JsonObject node = new JsonObject();
        node.addProperty("id", element.getID());
        node.addProperty("type", ModelSupport.typeName(element));
        node.addProperty("name", ModelSupport.name(element));
        node.add("stereotypes", ModelSupport.stereotypeNames(element));
        if (full) {
            node.add("element", ElementJson.toJson(element));
        }
        JsonArray children = new JsonArray();
        List<Element> owned = Collections.emptyList();
        try {
            owned = ModelSupport.sortedChildren(element);
        } catch (Exception ignored) {
            // leave empty
        }
        if (depth < maxDepth) {
            for (Element child : owned) {
                children.add(treeNode(child, maxDepth, depth + 1, full));
            }
        } else if (!owned.isEmpty()) {
            node.addProperty("childCount", owned.size());
        }
        node.add("children", children);
        return node;
    }

    static JsonObject listChildren(Args a) throws Exception {
        String rootId = alias(a, "root_id", "parent_id");
        String typeFilter = a.optTrim("type");
        String nameFilter = a.optTrim("name");
        String stereotypeFilter = a.optTrim("stereotype");
        Long l = a.lng("limit");
        if (l != null && (l < 1 || l > CHILD_MAX_LIMIT)) {
            throw ToolException.invalid("limit must be between 1 and " + CHILD_MAX_LIMIT + ".");
        }
        int limit = l == null ? CHILD_DEFAULT_LIMIT : (int) (long) l;
        int offset = offset(a);
        String view = ModelText.view(a.str("view"));
        boolean full = ModelText.VIEW_FULL.equals(view);

        return Edt.read(project -> {
            Element root = ModelSupport.root(project, rootId, "root_id");
            List<Element> owned = ModelSupport.sortedChildren(root);
            List<Element> filtered = new ArrayList<>();
            for (Element child : owned) {
                if (typeFilter != null && !ModelText.typeMatches(typeFilter, ModelSupport.typeName(child),
                        ModelSupport.className(child), child.getHumanType())) {
                    continue;
                }
                if (nameFilter != null && !ModelSupport.name(child).toLowerCase()
                        .contains(nameFilter.toLowerCase())) {
                    continue;
                }
                if (stereotypeFilter != null && !ModelSupport.hasStereotypeNamed(child, stereotypeFilter)) {
                    continue;
                }
                filtered.add(child);
            }
            int[] range = new int[2];
            Map<String, Object> paging = ModelText.page(filtered.size(), offset, limit, range);
            JsonArray children = new JsonArray();
            for (int i = range[0]; i < range[1]; i++) {
                Element c = filtered.get(i);
                children.add(full ? ElementJson.toJson(c) : ElementJson.toJsonCompact(c));
            }
            JsonObject response = new JsonObject();
            response.add("root", full ? ElementJson.toJson(root) : ElementJson.toJsonCompact(root));
            response.addProperty("rootId", root.getID());
            response.addProperty("view", view);
            response.addProperty("totalChildren", owned.size());
            response.addProperty("matchedChildren", filtered.size());
            response.addProperty("returned", children.size());
            response.addProperty("count", children.size());
            merge(response, paging);
            JsonObject filters = new JsonObject();
            if (typeFilter != null) {
                filters.addProperty("type", typeFilter);
            }
            if (nameFilter != null) {
                filters.addProperty("name", nameFilter);
            }
            if (stereotypeFilter != null) {
                filters.addProperty("stereotype", stereotypeFilter);
            }
            response.add("filters", filters);
            response.add("children", children);
            return response;
        });
    }

    // ------------------------------------------------------------------ relationships

    static JsonObject relationships(Args a) throws Exception {
        String elementId = a.reqStr("element_id");
        String direction = a.choice("direction", "both", "both", "incoming", "outgoing");
        boolean wantOut = !"incoming".equals(direction);
        boolean wantIn = !"outgoing".equals(direction);
        return Edt.read(project -> {
            Element element = ModelSupport.element(project, elementId, "element_id");
            JsonArray outgoing = new JsonArray();
            JsonArray incoming = new JsonArray();
            Set<String> directedIds = new HashSet<>();
            try {
                Collection<DirectedRelationship> rels = element.get_directedRelationshipOfSource();
                if (rels != null) {
                    for (DirectedRelationship rel : rels) {
                        if (rel != null && directedIds.add(rel.getID()) && wantOut) {
                            outgoing.add(directed(rel, "outgoing"));
                        }
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
            try {
                Collection<DirectedRelationship> rels = element.get_directedRelationshipOfTarget();
                if (rels != null) {
                    for (DirectedRelationship rel : rels) {
                        if (rel != null && directedIds.add(rel.getID()) && wantIn) {
                            incoming.add(directed(rel, "incoming"));
                        }
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
            if (element instanceof ActivityNode) {
                ActivityNode node = (ActivityNode) element;
                try {
                    if (node.getOutgoing() != null) {
                        for (ActivityEdge edge : node.getOutgoing()) {
                            if (edge != null && directedIds.add(edge.getID()) && wantOut) {
                                outgoing.add(activityEdge(edge, "outgoing"));
                            }
                        }
                    }
                    if (node.getIncoming() != null) {
                        for (ActivityEdge edge : node.getIncoming()) {
                            if (edge != null && directedIds.add(edge.getID()) && wantIn) {
                                incoming.add(activityEdge(edge, "incoming"));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // best effort
                }
            }
            JsonArray undirected = new JsonArray();
            Set<String> undirectedIds = new HashSet<>();
            try {
                Collection<Relationship> all = element.get_relationshipOfRelatedElement();
                if (all != null) {
                    for (Relationship rel : all) {
                        if (rel == null || rel instanceof DirectedRelationship) {
                            continue;
                        }
                        if (undirectedIds.add(rel.getID())) {
                            undirected.add(undirectedRel(rel, elementId));
                        }
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
            if (element instanceof ConnectableElement) {
                try {
                    Collection<Connector> connectors = Connectors.collectDirectConnectors((ConnectableElement) element);
                    if (connectors != null) {
                        for (Connector c : connectors) {
                            if (c != null && undirectedIds.add(c.getID())) {
                                undirected.add(connector(c, elementId));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // best effort
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("elementId", elementId);
            response.addProperty("direction", direction);
            response.add("outgoing", outgoing);
            response.add("incoming", incoming);
            if (undirected.size() > 0) {
                response.add("undirected", undirected);
            }
            response.addProperty("totalCount", outgoing.size() + incoming.size() + undirected.size());
            return response;
        });
    }

    private static void typeFields(JsonObject json, Element rel) {
        String type = ModelSupport.typeName(rel);
        json.addProperty("type", type.isEmpty() ? rel.getHumanType() : type);
        json.addProperty("humanType", rel.getHumanType());
    }

    private static void metadata(JsonObject json, Element rel) {
        String name = ModelSupport.name(rel);
        if (!name.isEmpty()) {
            json.addProperty("name", name);
        }
        Element owner = rel.getOwner();
        if (owner != null) {
            json.addProperty("ownerId", owner.getID());
            String ownerName = ModelSupport.name(owner);
            if (!ownerName.isEmpty()) {
                json.addProperty("ownerName", ownerName);
            }
            String ownerType = owner.getHumanType();
            if (ownerType != null && !ownerType.isEmpty()) {
                json.addProperty("ownerType", ownerType);
            }
        }
        JsonArray st = ModelSupport.stereotypeNames(rel);
        if (st.size() > 0) {
            json.add("stereotypes", st);
        }
    }

    private static JsonObject directed(DirectedRelationship rel, String direction) {
        JsonObject json = new JsonObject();
        json.addProperty("relationshipId", rel.getID());
        json.addProperty("direction", direction);
        typeFields(json, rel);
        metadata(json, rel);
        json.add("sources", refs(rel.getSource()));
        json.add("targets", refs(rel.getTarget()));
        if (rel instanceof InformationFlow) {
            informationFlow(json, (InformationFlow) rel);
        }
        return json;
    }

    private static JsonObject activityEdge(ActivityEdge edge, String direction) {
        JsonObject json = new JsonObject();
        json.addProperty("relationshipId", edge.getID());
        json.addProperty("direction", direction);
        typeFields(json, edge);
        metadata(json, edge);
        JsonArray sources = new JsonArray();
        if (edge.getSource() != null) {
            sources.add(ElementJson.toJsonReference(edge.getSource()));
        }
        json.add("sources", sources);
        JsonArray targets = new JsonArray();
        if (edge.getTarget() != null) {
            targets.add(ElementJson.toJsonReference(edge.getTarget()));
        }
        json.add("targets", targets);
        return json;
    }

    private static JsonObject undirectedRel(Relationship rel, String selfId) {
        JsonObject json = new JsonObject();
        json.addProperty("relationshipId", rel.getID());
        json.addProperty("direction", "undirected");
        typeFields(json, rel);
        metadata(json, rel);
        JsonArray related = new JsonArray();
        Set<String> seen = new HashSet<>();
        try {
            for (Element e : rel.getRelatedElement()) {
                addRelated(related, seen, e, selfId);
            }
        } catch (Exception ignored) {
            // best effort
        }
        json.add("relatedElements", related);
        return json;
    }

    private static JsonObject connector(Connector connector, String selfId) {
        JsonObject json = new JsonObject();
        json.addProperty("relationshipId", connector.getID());
        json.addProperty("direction", "undirected");
        json.addProperty("type", "Connector");
        json.addProperty("humanType", connector.getHumanType());
        metadata(json, connector);
        JsonArray related = new JsonArray();
        JsonArray ends = new JsonArray();
        JsonArray selfEnds = new JsonArray();
        JsonArray otherEnds = new JsonArray();
        Set<String> seen = new HashSet<>();
        try {
            Collection<ConnectorEnd> cends = connector.getEnd();
            if (cends != null) {
                for (ConnectorEnd end : cends) {
                    if (end == null) {
                        continue;
                    }
                    JsonObject endJson = ElementJson.toJsonConnectorEnd(end);
                    ConnectableElement role = end.getRole();
                    Property pwp = end.getPartWithPort();
                    boolean self = matches(selfId, role) || matches(selfId, pwp);
                    endJson.addProperty("matchesQueriedElement", self);
                    ends.add(endJson);
                    (self ? selfEnds : otherEnds).add(endJson.deepCopy());
                    addRelated(related, seen, role, selfId);
                    addRelated(related, seen, pwp, selfId);
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        json.add("relatedElements", related);
        if (ends.size() > 0) {
            json.add("connectorEnds", ends);
            json.addProperty("endCount", ends.size());
        }
        if (selfEnds.size() > 0) {
            json.add("selfEnds", selfEnds);
        }
        if (otherEnds.size() > 0) {
            json.add("otherEnds", otherEnds);
        }
        JsonArray flows = refs(elementCollection(connector, "get_informationFlowOfRealizingConnector",
                "getInformationFlowOfRealizingConnector"));
        if (flows.size() > 0) {
            json.add("realizingInformationFlows", flows);
        }
        return json;
    }

    private static void informationFlow(JsonObject json, InformationFlow flow) {
        try {
            json.add("informationSources", refs(flow.getInformationSource()));
            json.add("informationTargets", refs(flow.getInformationTarget()));
            json.add("conveyed", refs(flow.getConveyed()));
            json.add("realizingConnectors", refs(flow.getRealizingConnector()));
            JsonArray details = new JsonArray();
            if (flow.getRealizingConnector() != null) {
                for (Connector c : flow.getRealizingConnector()) {
                    if (c != null) {
                        details.add(connector(c, null));
                    }
                }
            }
            if (details.size() > 0) {
                json.add("realizingConnectorDetails", details);
            }
            Stereotype itemFlow = StereotypesHelper.getAppliedStereotypeByString(flow, "ItemFlow");
            if (itemFlow != null) {
                Object item = StereotypesHelper.getStereotypePropertyFirst(flow, itemFlow, "itemProperty");
                if (item instanceof Element) {
                    json.add("itemProperty", ElementJson.toJsonReference((Element) item));
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static JsonArray refs(Collection<? extends Element> elements) {
        try {
            return elements == null ? new JsonArray() : ElementJson.toJsonReferenceArray(elements);
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    private static void addRelated(JsonArray related, Set<String> seen, Element e, String selfId) {
        if (e == null || (selfId != null && selfId.equals(e.getID())) || !seen.add(e.getID())) {
            return;
        }
        related.add(ElementJson.toJsonReference(e));
    }

    private static boolean matches(String selfId, Element e) {
        return selfId != null && e != null && selfId.equals(e.getID());
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends Element> elementCollection(Object target, String... methods) {
        for (String m : methods) {
            try {
                Method method = target.getClass().getMethod(m);
                Object v = method.invoke(target);
                if (v instanceof Collection<?>) {
                    return (Collection<? extends Element>) v;
                }
            } catch (Exception ignored) {
                // next candidate
            }
        }
        return Collections.emptyList();
    }
}
