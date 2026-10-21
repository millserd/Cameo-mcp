package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

import java.util.Collection;

/**
 * Listings of matrices, Generic Tables and Relation Maps. They only read diagram types, so they
 * work (and return empty lists) even when the optional Cameo plugins are not installed.
 */
public final class ArtifactListings {

    /** Not instantiable; all members are static. */
    private ArtifactListings() {
    }

    /**
     * cameo_list_matrices: every diagram whose native type is a supported matrix kind, optionally
     * filtered by kind (validated before the model read) and by owning element id.
     *
     * @param p the tool arguments: optional {@code kind} and {@code owner_id}
     * @return {@code count} and {@code matrices}, one {@link #matrixSummary} each
     * @throws com.cameomcp.mcp.ToolException INVALID_PARAMS for an unknown kind
     * @throws Exception when no project is open or the hand-off to the EDT fails
     */
    public static JsonObject listMatrices(JsonObject p) throws Exception {
        String rawKind = ArtifactSupport.optStr(p, "kind");
        MatrixKinds filterKind = rawKind == null ? null : MatrixKinds.require(rawKind);
        String ownerId = ArtifactSupport.optStr(p, "owner_id");
        return Edt.read(project -> {
            JsonArray matrices = new JsonArray();
            Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
            if (diagrams != null) {
                for (DiagramPresentationElement dpe : diagrams) {
                    Diagram diagram = dpe == null ? null : dpe.getDiagram();
                    if (diagram == null) {
                        continue;
                    }
                    // Non-matrix diagrams map to null and are skipped; matching is by exact native type name.
                    MatrixKinds kind = MatrixKinds.fromDiagramType(ArtifactSupport.diagramType(dpe));
                    if (kind == null || (filterKind != null && kind != filterKind)) {
                        continue;
                    }
                    if (ownerId != null) {
                        Element owner = diagram.getOwner();
                        if (owner == null || !ownerId.equals(owner.getID())) {
                            continue;
                        }
                    }
                    matrices.add(matrixSummary(kind, dpe));
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("count", matrices.size());
            if (filterKind != null) {
                response.addProperty("kind", filterKind.apiName);
            }
            if (ownerId != null) {
                response.addProperty("ownerId", ownerId);
            }
            response.add("matrices", matrices);
            return response;
        });
    }

    /**
     * id, name, kind, matrixType, ownerId, ownerName.
     *
     * @param kind the matrix kind
     * @param dpe the matrix diagram
     * @return the summary
     */
    public static JsonObject matrixSummary(MatrixKinds kind, DiagramPresentationElement dpe) {
        JsonObject summary = new JsonObject();
        Diagram diagram = dpe.getDiagram();
        summary.addProperty("id", diagram.getID());
        summary.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
        summary.addProperty("kind", kind.apiName);
        summary.addProperty("matrixType", kind.diagramType);
        Element owner = diagram.getOwner();
        if (owner != null) {
            summary.addProperty("ownerId", owner.getID());
            if (owner instanceof NamedElement) {
                summary.addProperty("ownerName", ((NamedElement) owner).getName());
            }
        }
        return summary;
    }

    /**
     * cameo_list_generic_tables: all diagrams of type "Generic Table".
     *
     * @return {@code count} and {@code tables}
     * @throws Exception when no project is open or the hand-off to the EDT fails
     */
    public static JsonObject listGenericTables() throws Exception {
        return listByType(ArtifactSupport.GENERIC_TABLE_TYPE, "tables");
    }

    /**
     * cameo_list_relation_maps: all diagrams of type "Relation Map Diagram".
     *
     * @return {@code count} and {@code relationMaps}
     * @throws Exception when no project is open or the hand-off to the EDT fails
     */
    public static JsonObject listRelationMaps() throws Exception {
        return listByType(ArtifactSupport.RELATION_MAP_TYPE, "relationMaps");
    }

    /** Scans the project's diagrams in an Edt.read and returns {count, key: [diagramSummary...]} for one type. */
    private static JsonObject listByType(String type, String key) throws Exception {
        return Edt.read(project -> {
            JsonArray items = new JsonArray();
            Collection<DiagramPresentationElement> diagrams = project.getDiagrams();
            if (diagrams != null) {
                for (DiagramPresentationElement dpe : diagrams) {
                    if (dpe != null && type.equals(ArtifactSupport.diagramType(dpe))) {
                        items.add(ArtifactSupport.diagramSummary(dpe, type));
                    }
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("count", items.size());
            response.add(key, items);
            return response;
        });
    }
}
