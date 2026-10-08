package com.cameomcp.plugin.tools.artifacts;

import com.cameomcp.plugin.cameo.Edt;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    private ArtifactListings() {
    }

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

    /** id, name, kind, matrixType, ownerId, ownerName. */
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

    public static JsonObject listGenericTables() throws Exception {
        return listByType(ArtifactSupport.GENERIC_TABLE_TYPE, "tables");
    }

    public static JsonObject listRelationMaps() throws Exception {
        return listByType(ArtifactSupport.RELATION_MAP_TYPE, "relationMaps");
    }

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
