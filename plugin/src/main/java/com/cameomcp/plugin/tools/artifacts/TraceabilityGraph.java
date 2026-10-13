package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.ElementJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.ClassTypes;
import com.nomagic.magicdraw.uml2.Connectors;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.DirectedRelationship;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Relationship;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectorEnd;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

/**
 * Breadth-first relationship graph from root elements (cameo_get_traceability_graph and the
 * traversal part of relation-map verification). Independent of Relation Map criteria.
 * <p>
 * Request keys (snake_case): root_element_ids, context_element_id, relationship_types,
 * direction (incoming|outgoing|both), max_depth (1-12, default 3), max_nodes (1-1000, default 250).
 */
public final class TraceabilityGraph {

    private TraceabilityGraph() {
    }

    /**
     * Build the graph. {@code mapContext} is the Relation Map's context element used as root when the
     * request names no roots (may be null); {@code relationMapId} is echoed in the result.
     */
    public static JsonObject build(Project project, JsonObject body, String relationMapId, Element mapContext) {
        List<Element> roots = resolveRoots(project, body, relationMapId, mapContext);
        Set<String> filter = normalizedFilter(ArtifactSupport.optStrList(body, "relationship_types"));
        String direction = normalizeDirection(ArtifactSupport.optStr(body, "direction"));
        int maxDepth = ArtifactSupport.clamp(ArtifactSupport.optInt(body, "max_depth"), 1, 12, 3);
        int maxNodes = ArtifactSupport.clamp(ArtifactSupport.optInt(body, "max_nodes"), 1, 1000, 250);

        JsonArray nodes = new JsonArray();
        JsonArray edges = new JsonArray();
        Set<String> nodeIds = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        // Queue entries are {Element, Integer depth}.
        Queue<Object[]> frontier = new ArrayDeque<>();

        // Roots are recorded at depth 0 and seed the BFS frontier.
        for (Element root : roots) {
            addNode(nodes, nodeIds, root, 0, true);
            frontier.add(new Object[]{root, 0});
        }

        boolean truncated = false;
        while (!frontier.isEmpty() && !truncated) {
            Object[] item = frontier.remove();
            Element element = (Element) item[0];
            int depth = (Integer) item[1];
            // Skip elements at the depth limit or already expanded at this depth.
            if (depth >= maxDepth || !expanded.add(element.getID() + "@" + depth)) {
                continue;
            }
            for (Edge edge : adjacentEdges(element, direction, filter)) {
                String relId = edge.relationship.getID();
                // Relationships without an id are skipped; a relationship reached from both ends is listed once.
                if (relId == null || !edgeIds.add(relId)) {
                    continue;
                }
                // The edge is recorded even if its far endpoint ends up cut off by max_nodes below.
                edges.add(edge.toJson(depth + 1));
                for (Element endpoint : edge.traversalTargets) {
                    if (endpoint == null || endpoint.getID() == null) {
                        continue;
                    }
                    boolean added = addNode(nodes, nodeIds, endpoint, depth + 1, false);
                    // Once the node budget is reached, stop the whole traversal and report truncated=true.
                    if (nodeIds.size() >= maxNodes) {
                        truncated = true;
                        break;
                    }
                    // Only newly seen nodes are expanded, so cycles do not re-enter the frontier.
                    if (added && depth + 1 < maxDepth) {
                        frontier.add(new Object[]{endpoint, depth + 1});
                    }
                }
                if (truncated) {
                    break;
                }
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("rootCount", roots.size());
        response.add("rootElements", ArtifactSupport.compactArray(roots));
        response.addProperty("nodeCount", nodes.size());
        response.addProperty("edgeCount", edges.size());
        response.addProperty("maxDepth", maxDepth);
        response.addProperty("maxNodes", maxNodes);
        response.addProperty("direction", direction);
        response.addProperty("truncated", truncated);
        response.add("nodes", nodes);
        response.add("edges", edges);
        if (relationMapId != null) {
            response.addProperty("relationMapId", relationMapId);
        }
        return response;
    }

    /** True when the request names explicit roots (so no relation-map context lookup is needed). */
    public static boolean hasExplicitRoots(JsonObject body) {
        List<String> ids = ArtifactSupport.optStrList(body, "root_element_ids");
        return (ids != null && !ids.isEmpty()) || ArtifactSupport.optStr(body, "context_element_id") != null;
    }

    /**
     * Roots in priority order: root_element_ids, then context_element_id, then the Relation Map's
     * context element; otherwise an invalid-params error worded for the map or plain-graph case.
     */
    private static List<Element> resolveRoots(Project project, JsonObject body, String relationMapId,
                                              Element mapContext) {
        List<String> rootIds = ArtifactSupport.optStrList(body, "root_element_ids");
        String contextId = ArtifactSupport.optStr(body, "context_element_id");
        if ((rootIds == null || rootIds.isEmpty()) && contextId != null) {
            rootIds = Collections.singletonList(contextId);
        }
        if (rootIds != null && !rootIds.isEmpty()) {
            return ArtifactSupport.elements(project, rootIds, "Root element");
        }
        if (mapContext != null) {
            return Collections.singletonList(mapContext);
        }
        if (relationMapId != null) {
            throw ToolException.invalid("Relation Map '" + relationMapId + "' has no context element. Pass "
                    + "root_element_ids or context_element_id, or set one with cameo_configure_relation_map.");
        }
        throw ToolException.invalid("Provide root_element_ids or context_element_id, or a relation_map_id whose "
                + "map has a context element.");
    }

    /**
     * Relationships touching element in the requested direction, filtered by type. Covers directed
     * relationships, activity flows, and (only for "both") undirected relationships and connectors.
     */
    static List<Edge> adjacentEdges(Element element, String direction, Set<String> filter) {
        List<Edge> edges = new ArrayList<>();
        String selfId = element.getID();
        boolean outgoing = "both".equals(direction) || "outgoing".equals(direction);
        boolean incoming = "both".equals(direction) || "incoming".equals(direction);

        if (outgoing) {
            Collection<DirectedRelationship> rels = element.get_directedRelationshipOfSource();
            if (rels != null) {
                for (DirectedRelationship rel : rels) {
                    if (matches(rel, filter)) {
                        edges.add(Edge.directed(rel, "outgoing", rel.getSource(), rel.getTarget()));
                    }
                }
            }
            if (element instanceof ActivityNode) {
                addActivityEdges(edges, ((ActivityNode) element).getOutgoing(), "outgoing", filter);
            }
        }
        if (incoming) {
            Collection<DirectedRelationship> rels = element.get_directedRelationshipOfTarget();
            if (rels != null) {
                for (DirectedRelationship rel : rels) {
                    if (matches(rel, filter)) {
                        edges.add(Edge.directed(rel, "incoming", rel.getSource(), rel.getTarget()));
                    }
                }
            }
            if (element instanceof ActivityNode) {
                addActivityEdges(edges, ((ActivityNode) element).getIncoming(), "incoming", filter);
            }
        }
        // Undirected relationships (e.g. Association) and connectors have no source/target side,
        // so they are only followed when direction is "both".
        if ("both".equals(direction)) {
            Collection<Relationship> rels = element.get_relationshipOfRelatedElement();
            if (rels != null) {
                for (Relationship rel : rels) {
                    if (!(rel instanceof DirectedRelationship) && matches(rel, filter)) {
                        edges.add(Edge.undirected(rel, selfId, rel.getRelatedElement()));
                    }
                }
            }
            if (element instanceof ConnectableElement) {
                Collection<Connector> connectors;
                // Connectors helper may be missing or fail on some element kinds; treat as no connectors.
                try {
                    connectors = Connectors.collectDirectConnectors((ConnectableElement) element);
                } catch (RuntimeException | LinkageError e) {
                    connectors = null;
                }
                if (connectors != null) {
                    for (Connector c : connectors) {
                        if (matches(c, filter)) {
                            edges.add(Edge.connector(c, selfId));
                        }
                    }
                }
            }
        }
        return edges;
    }

    /** Adds an Edge for each ActivityEdge (control/object flow) that passes the type filter. */
    private static void addActivityEdges(List<Edge> edges, Collection<ActivityEdge> flows, String dir,
                                         Set<String> filter) {
        if (flows == null) {
            return;
        }
        for (ActivityEdge edge : flows) {
            if (matches(edge, filter)) {
                List<Element> sources = new ArrayList<>();
                List<Element> targets = new ArrayList<>();
                if (edge.getSource() != null) {
                    sources.add(edge.getSource());
                }
                if (edge.getTarget() != null) {
                    targets.add(edge.getTarget());
                }
                edges.add(Edge.directed(edge, dir, sources, targets));
            }
        }
    }

    /** Appends a node reference once per element id; returns false when already present or id-less. */
    private static boolean addNode(JsonArray nodes, Set<String> ids, Element element, int depth, boolean root) {
        if (element == null || element.getID() == null || !ids.add(element.getID())) {
            return false;
        }
        JsonObject node = ElementJson.toJsonReference(element);
        node.addProperty("depth", depth);
        node.addProperty("root", root);
        nodes.add(node);
        return true;
    }

    /** True when the filter is empty or any type token of the relationship (see tokens) is in it. */
    static boolean matches(Element relationship, Set<String> filter) {
        if (filter.isEmpty()) {
            return true;
        }
        for (String token : tokens(relationship)) {
            if (filter.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalized tokens a relationship can be matched by: human type, metaclass short name, its own
     * name and applied stereotype names. Each source is read defensively so one failure loses only that token.
     */
    private static Set<String> tokens(Element relationship) {
        Set<String> tokens = new HashSet<>();
        try {
            addToken(tokens, relationship.getHumanType());
        } catch (RuntimeException ignored) {
            // other tokens remain
        }
        try {
            addToken(tokens, ClassTypes.getShortName(relationship.getClassType()));
        } catch (RuntimeException | LinkageError ignored) {
            // human type is enough when the metaclass lookup is unavailable
        }
        if (relationship instanceof NamedElement) {
            addToken(tokens, ((NamedElement) relationship).getName());
        }
        try {
            List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(relationship);
            if (stereotypes != null) {
                for (Stereotype s : stereotypes) {
                    if (s != null) {
                        addToken(tokens, s.getName());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // metaclass/human type matching remains available
        }
        return tokens;
    }

    private static void addToken(Set<String> tokens, String value) {
        String n = normalizeToken(value);
        if (!n.isEmpty()) {
            tokens.add(n);
        }
    }

    /** Relationship-type filter tokens: lower-case alphanumerics ("Derive Reqt" and "deriveReqt" match). */
    public static Set<String> normalizedFilter(List<String> values) {
        Set<String> filter = new HashSet<>();
        if (values != null) {
            for (String v : values) {
                addToken(filter, v);
            }
        }
        return filter;
    }

    /** Lower-cases and strips everything but ASCII letters and digits; null becomes "". */
    public static String normalizeToken(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Validates direction; blank or null means "both". Throws invalid-params for anything else. */
    public static String normalizeDirection(String value) {
        if (value == null || value.trim().isEmpty() || "both".equalsIgnoreCase(value.trim())) {
            return "both";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("incoming".equals(v) || "outgoing".equals(v)) {
            return v;
        }
        throw ToolException.invalid("direction must be incoming, outgoing, or both (got '" + value + "').");
    }

    /** One traversed relationship. */
    static final class Edge {
        final Element relationship;
        final String direction;
        final List<Element> sources;
        final List<Element> targets;
        final List<Element> related;
        // The elements the BFS continues to from this edge (the far side relative to the current node).
        final List<Element> traversalTargets;

        private Edge(Element relationship, String direction, Collection<? extends Element> sources,
                     Collection<? extends Element> targets, Collection<? extends Element> related,
                     Collection<? extends Element> traversalTargets) {
            this.relationship = relationship;
            this.direction = direction;
            this.sources = nonNull(sources);
            this.targets = nonNull(targets);
            this.related = nonNull(related);
            this.traversalTargets = nonNull(traversalTargets);
        }

        // For a directed edge, the far side is the sources when walking incoming, else the targets.
        static Edge directed(Element rel, String dir, Collection<? extends Element> sources,
                             Collection<? extends Element> targets) {
            return new Edge(rel, dir, sources, targets, Collections.<Element>emptyList(),
                    "incoming".equals(dir) ? sources : targets);
        }

        // Undirected: the far side is every related element other than the current one (selfId).
        static Edge undirected(Element rel, String selfId, Collection<? extends Element> related) {
            List<Element> next = new ArrayList<>();
            if (related != null) {
                for (Element e : related) {
                    if (e != null && e.getID() != null && !e.getID().equals(selfId)) {
                        next.add(e);
                    }
                }
            }
            return new Edge(rel, "undirected", Collections.<Element>emptyList(), Collections.<Element>emptyList(),
                    related, next);
        }

        // A connector's ends are treated as related elements: the role and, if set, the part owning the port.
        static Edge connector(Connector connector, String selfId) {
            List<Element> related = new ArrayList<>();
            Collection<ConnectorEnd> ends = connector.getEnd();
            if (ends != null) {
                for (ConnectorEnd end : ends) {
                    if (end == null) {
                        continue;
                    }
                    if (end.getRole() != null) {
                        related.add(end.getRole());
                    }
                    if (end.getPartWithPort() != null) {
                        related.add(end.getPartWithPort());
                    }
                }
            }
            return undirected(connector, selfId, related);
        }

        /** Edge as an element reference plus direction, depth and compact sources/targets/related lists. */
        JsonObject toJson(int depth) {
            JsonObject json = ElementJson.toJsonReference(relationship);
            json.addProperty("relationshipId", relationship.getID());
            json.addProperty("id", relationship.getID());
            json.addProperty("direction", direction);
            json.addProperty("depth", depth);
            json.add("sources", ArtifactSupport.compactArray(sources));
            json.add("targets", ArtifactSupport.compactArray(targets));
            json.add("relatedElements", ArtifactSupport.compactArray(related));
            json.add("traversalTargets", ArtifactSupport.compactArray(traversalTargets));
            return json;
        }

        private static List<Element> nonNull(Collection<? extends Element> in) {
            List<Element> out = new ArrayList<>();
            if (in != null) {
                for (Element e : in) {
                    if (e != null) {
                        out.add(e);
                    }
                }
            }
            return out;
        }
    }
}
