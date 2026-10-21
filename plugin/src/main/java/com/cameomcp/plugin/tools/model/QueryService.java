package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
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

    // Paging and depth limits: defaults keep answers small for agents; maxima cap the work on huge models.
    /** Default page size of cameo_query_elements. */
    static final int QUERY_DEFAULT_LIMIT = 200;
    /** Largest page size cameo_query_elements accepts; bigger limits are cut to this. */
    static final int QUERY_MAX_LIMIT = 1000;
    /** Default depth of cameo_get_containment_tree. */
    static final int TREE_DEFAULT_DEPTH = 3;
    /** Largest depth of cameo_get_containment_tree; bigger depths are cut to this. */
    static final int TREE_MAX_DEPTH = 10;
    /** Default page size of cameo_list_containment_children. */
    static final int CHILD_DEFAULT_LIMIT = 50;
    /** Largest page size of cameo_list_containment_children; bigger limits are an error. */
    static final int CHILD_MAX_LIMIT = 500;

    /** Not instantiable; all members are static. */
    private QueryService() {
    }

    /**
     * First non-blank value among parameter aliases.
     *
     * @param a the tool arguments
     * @param names the accepted parameter names, in order of preference
     * @return the trimmed value, or null when none is given
     */
    static String alias(Args a, String... names) {
        for (String n : names) {
            String v = a.optTrim(n);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Reads the limit parameter, capped at max. Zero or negative is an error rather than silently meaning
     * "all".
     *
     * @param a the tool arguments
     * @param dflt the value when limit is absent
     * @param max the cap
     * @return the page size
     * @throws ToolException INVALID_PARAMS when limit is below 1
     */
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

    /**
     * Reads the offset parameter; negative is an error.
     *
     * @param a the tool arguments
     * @return the offset, 0 when absent
     * @throws ToolException INVALID_PARAMS when offset is negative
     */
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

    /**
     * Implements cameo_get_element: full detail of one element.
     *
     * @param a the tool arguments: {@code element_id}
     * @return the element's full JSON
     * @throws ToolException NOT_FOUND for a missing element, NO_PROJECT when no project is open
     * @throws Exception any failure while handing the work to the EDT
     */
    static JsonObject getElement(Args a) throws Exception {
        String id = a.reqStr("element_id");
        return Edt.read(project -> ElementJson.toJson(ModelSupport.element(project, id, "element_id")));
    }

    // ------------------------------------------------------------------ query

    /**
     * Implements cameo_query_elements: finds elements under a scope (default: the primary model) by
     * metaclass, name
     * and stereotype. Results are sorted by type, name and id so paging is stable between calls.
     *
     * @param a the tool arguments: {@code type}, {@code name}, a scope ({@code package_id} or its aliases),
     * {@code stereotype}, {@code recursive} (default true), {@code view}, {@code limit} and {@code offset}
     *
     * @return {@code view}, the counts, paging fields, the {@code filters} used and the page of {@code
     *     elements}
     * @throws ToolException INVALID_PARAMS for an unknown type filter or bad paging values, NOT_FOUND for a
     *     missing
     * scope
     *
     * @throws Exception any failure while handing the work to the EDT
     */
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
            // Pick the cheapest Finder for the filters given; the stereotype filter is applied afterwards.
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

    /**
     * Copies plain-JSON paging fields into a tree response.
     *
     * @param target the response to add to
     * @param values field name to plain JSON value, as made by {@link ModelText#page}
     */
    static void merge(JsonObject target, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            target.add(e.getKey(), Gs.toElement(e.getValue()));
        }
    }

    /**
     * The metaclass for a type filter ("Class", "use case", "property"), trying a few spellings; null if
     * unknown.
     *
     * @param typeInput the filter text; must not be empty
     * @return the metaclass interface, for example the UML Class interface for "Class", or null
     */
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

    /**
     * Implements cameo_get_containment_tree: nested owned elements down to the given depth.
     *
     * @param a the tool arguments: {@code root_id} (or parent_id), {@code depth} (clamped to 1 to 10) and
     * {@code view}
     *
     * @return {@code rootId}, {@code depth}, {@code view} and the nested {@code tree}
     * @throws ToolException NOT_FOUND for a missing root, INVALID_PARAMS for a bad view
     * @throws Exception any failure while handing the work to the EDT
     */
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

    /**
     * Builds one tree node. At the depth limit only childCount is reported, so agents know where to drill in.
     *
     * @param element the element for this node
     * @param maxDepth the deepest level to expand
     * @param depth this node's level, 0 for the root
     * @param full true to include each element's full JSON
     * @return the node with id, type, name, stereotypes and children
     */
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

    /**
     * Implements cameo_list_containment_children: one level of owned elements, filtered and paged. The name
     * filter is a substring match ignoring case.
     *
     * @param a the tool arguments: {@code root_id} (or parent_id), {@code type}, {@code name}, {@code
     *     stereotype},
     * {@code limit} (1 to 500), {@code offset} and {@code view}
     *
     * @return the root, counts, paging fields, {@code filters} and the page of {@code children}
     * @throws ToolException INVALID_PARAMS for bad paging values, NOT_FOUND for a missing root
     * @throws Exception any failure while handing the work to the EDT
     */
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

    /**
     * Implements cameo_get_relationships: everything that touches an element. Directed relationships and
     * activity edges are split into outgoing and incoming; associations and connectors are listed as
     * undirected. Each source is read in its own try block so one failing API does not hide the rest.
     *
     * @param a the tool arguments: {@code element_id} and {@code direction} (both, incoming or outgoing)
     * @return {@code outgoing}, {@code incoming}, {@code undirected} (only when non-empty) and {@code
     *     totalCount}
     * @throws ToolException NOT_FOUND for a missing element, INVALID_PARAMS for a bad direction
     * @throws Exception any failure while handing the work to the EDT
     */
    static JsonObject relationships(Args a) throws Exception {
        String elementId = a.reqStr("element_id");
        String direction = a.choice("direction", "both", "both", "incoming", "outgoing");
        boolean wantOut = !"incoming".equals(direction);
        boolean wantIn = !"outgoing".equals(direction);
        return Edt.read(project -> {
            Element element = ModelSupport.element(project, elementId, "element_id");
            JsonArray outgoing = new JsonArray();
            JsonArray incoming = new JsonArray();
            // Ids already reported, so an element that is both source and target of a relationship isn't listed twice.
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
            // Connectors aren't Relationships in UML, so ports and parts need this separate lookup.
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

    /**
     * Adds the type and humanType of a relationship.
     *
     * @param json the object to add to
     * @param rel the relationship
     */
    private static void typeFields(JsonObject json, Element rel) {
        String type = ModelSupport.typeName(rel);
        json.addProperty("type", type.isEmpty() ? rel.getHumanType() : type);
        json.addProperty("humanType", rel.getHumanType());
    }

    /**
     * Adds the name, owner and stereotypes of a relationship, when present.
     *
     * @param json the object to add to
     * @param rel the relationship
     */
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

    /**
     * One directed relationship (Dependency, Satisfy, Generalization and so on) with its sources and targets.
     *
     * @param rel the relationship
     * @param direction "outgoing" or "incoming", as seen from the queried element
     * @return the relationship JSON, with information-flow details when it is one
     */
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

    /**
     * One control or object flow. Activity edges are not DirectedRelationships in Cameo's API.
     *
     * @param edge the edge
     * @param direction "outgoing" or "incoming"
     * @return the edge JSON with one source and one target
     */
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

    /**
     * One undirected relationship, such as an Association, listing the other related elements.
     *
     * @param rel the relationship
     * @param selfId the queried element's id, left out of the related elements
     * @return the relationship JSON
     */
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

    /**
     * One connector: every end, which ends attach to the queried element, and the information flows it
     * realizes. The realizing-flow getter is called by reflection because its name differs between releases.
     *
     * @param connector the connector
     * @param selfId the queried element's id, or null when there is none (as for realizing-connector details)
     * @return the connector JSON
     */
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

    /**
     * Adds the sources, targets, conveyed items, realizing connectors and ItemFlow item property of an
     * information flow. Failures are ignored.
     *
     * @param json the object to add to
     * @param flow the information flow
     */
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

    /**
     * Reference JSON for each element; empty when the collection cannot be read.
     *
     * @param elements the elements, may be null
     * @return the references
     */
    private static JsonArray refs(Collection<? extends Element> elements) {
        try {
            return elements == null ? new JsonArray() : ElementJson.toJsonReferenceArray(elements);
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    /**
     * Adds a reference once, skipping the queried element itself.
     *
     * @param related the array to add to
     * @param seen ids already added
     * @param e the element, may be null
     * @param selfId the queried element's id, or null
     */
    private static void addRelated(JsonArray related, Set<String> seen, Element e, String selfId) {
        if (e == null || (selfId != null && selfId.equals(e.getID())) || !seen.add(e.getID())) {
            return;
        }
        related.add(ElementJson.toJsonReference(e));
    }

    /**
     * True when the element is the queried element.
     *
     * @param selfId the queried element's id, or null
     * @param e the element, may be null
     * @return true when both are non-null and the ids match
     */
    private static boolean matches(String selfId, Element e) {
        return selfId != null && e != null && selfId.equals(e.getID());
    }

    /**
     * Calls the first of the named collection getters that exists. Getter names differ across releases, so
     * the calls are reflective.
     *
     * @param target the object to call on
     * @param methods zero-argument getter names to try in order
     * @return the first collection returned, or an empty list
     */
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
