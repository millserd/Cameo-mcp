package com.cameomcp.optional.matrix;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.TreeService;
import com.cameomcp.plugin.tools.artifacts.ArtifactListings;
import com.cameomcp.plugin.tools.artifacts.ArtifactSupport;
import com.cameomcp.plugin.tools.artifacts.MatrixKinds;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.dependencymatrix.DependencyMatrix;
import com.nomagic.magicdraw.dependencymatrix.MatrixManager;
import com.nomagic.magicdraw.dependencymatrix.configuration.MatrixDataHelper;
import com.nomagic.magicdraw.dependencymatrix.datamodel.MatrixData;
import com.nomagic.magicdraw.dependencymatrix.datamodel.cell.AbstractMatrixCell;
import com.nomagic.magicdraw.dependencymatrix.datamodel.cell.DependencyEntry;
import com.nomagic.magicdraw.dependencymatrix.persistence.FilterSettings;
import com.nomagic.magicdraw.dependencymatrix.persistence.MatrixSettings;
import com.nomagic.magicdraw.dependencymatrix.persistence.PersistenceManager;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Native matrices through the Dependency Matrix plugin API (cameo_get_matrix, cameo_create_matrix). */
public final class MatrixService implements TreeService {

    /** Criterion that makes a plain Dependency Matrix show UML Dependency relationships. */
    static final String UML_DEPENDENCY_CRITERIA = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <callExpressionSpecification xmlns="http://www.nomagic.com/schemas/MagicDraw/StructuredExpression/2013">
                <taggedValues>
                    <entry key="name">
                        <value>Dependency</value>
                    </entry>
                </taggedValues>
                <argument xsi:type="lookupExpressionSpecification" symbol="THIS" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
                <expression xsi:type="relationExpressionSpecification" metaclass="Dependency" includeSubtypes="true" direction="DIRECT" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <taggedValues>
                        <entry key="name">
                            <value>Dependency</value>
                        </entry>
                    </taggedValues>
                </expression>
            </callExpressionSpecification>
            """;

    public MatrixService() {
    }

    // Any LinkageError means the installed Dependency Matrix API differs from the one compiled against;
    // report it as UNSUPPORTED instead of letting the error escape to the tool dispatcher.
    @Override
    public JsonObject call(String operation, JsonObject params) throws Exception {
        try {
            return dispatch(operation, params);
        } catch (LinkageError e) {
            throw new ToolException("UNSUPPORTED", "The Dependency Matrix API of this Cameo version does not match what "
                    + "this tool expects (" + e + ").");
        }
    }

    /** Routes the operations bound in ArtifactBindings ("get", "create") for cameo_get_matrix / cameo_create_matrix. */
    private JsonObject dispatch(String operation, JsonObject params) throws Exception {
        switch (operation) {
            case "get":
                String id = Gs.reqStr(params, "matrix_id").trim();
                return Edt.read(project -> serializeMatrix(project, id));
            case "create":
                return create(params);
            default:
                throw new ToolException("NOT_IMPLEMENTED", "Unknown matrix operation " + operation);
        }
    }

    /**
     * cameo_create_matrix: creates the native matrix diagram under a Namespace parent and configures its
     * row/column scopes and types in one undoable session. Scopes fall back to scope_id, then parent_id.
     */
    private JsonObject create(JsonObject p) throws Exception {
        MatrixKinds kind = MatrixKinds.require(Gs.reqStr(p, "kind"));
        String parentId = Gs.reqStr(p, "parent_id").trim();
        String scopeId = ArtifactSupport.optStr(p, "scope_id");
        String rowScopeId = ArtifactSupport.optStr(p, "row_scope_id");
        String columnScopeId = ArtifactSupport.optStr(p, "column_scope_id");
        List<String> rowTypes = ArtifactSupport.optStrList(p, "row_types");
        List<String> columnTypes = ArtifactSupport.optStrList(p, "column_types");
        String name = ArtifactSupport.optStr(p, "name");
        // An explicitly empty list is rejected rather than silently replaced by the defaults.
        if (rowTypes != null && rowTypes.isEmpty()) {
            throw ToolException.invalid("row_types must not be empty when provided; omit it for the kind's defaults.");
        }
        if (columnTypes != null && columnTypes.isEmpty()) {
            throw ToolException.invalid("column_types must not be empty when provided; omit it for the kind's "
                    + "defaults.");
        }
        // Edt.write with a 120 s timeout: diagram creation plus the first matrix build can be slow.
        return Edt.write("Create " + kind.diagramType, project -> {
            Element parent = ArtifactSupport.element(project, parentId, "Parent element");
            if (!(parent instanceof Namespace)) {
                throw ToolException.invalid("Parent element '" + parentId + "' is not a Namespace (package, model, "
                        + "class...). Pick a package as parent_id.");
            }
            Element rowScope = ArtifactSupport.element(project, firstNonEmpty(rowScopeId, scopeId, parentId),
                    "Row scope element");
            Element columnScope = ArtifactSupport.element(project, firstNonEmpty(columnScopeId, scopeId, parentId),
                    "Column scope element");
            // Creating by native diagram type name lets Cameo apply the kind's built-in template.
            Diagram diagram = ModelElementsManager.getInstance().createDiagram(kind.diagramType, (Namespace) parent);
            if (diagram == null) {
                throw new ToolException("TOOL_FAILED", "Cameo did not create the matrix diagram '" + kind.diagramType
                        + "'. Check that the SysML/Dependency Matrix plugins are installed.");
            }
            if (name != null) {
                diagram.setName(name);
            }
            configure(project, diagram, kind, rowScope, columnScope, rowTypes, columnTypes);

            JsonObject response = new JsonObject();
            response.addProperty("created", true);
            // Read the new matrix back so the caller gets the same shape as cameo_get_matrix.
            response.add("matrix", serializeMatrix(project, diagram.getID()));
            JsonObject receipt = new JsonObject();
            receipt.addProperty("operation", "createMatrix");
            receipt.addProperty("kind", kind.apiName);
            receipt.addProperty("matrixId", diagram.getID());
            receipt.addProperty("status", "created");
            response.add("receipt", receipt);
            return response;
        }, 120);
    }

    /**
     * Writes matrix settings through the Dependency Matrix PersistenceManager: row-to-column direction,
     * show all elements, scope and element types for each axis (subtypes and custom types included).
     */
    private void configure(Project project, Diagram diagram, MatrixKinds kind, Element rowScope, Element columnScope,
                           List<String> rowTypes, List<String> columnTypes) {
        PersistenceManager pm = new PersistenceManager(diagram);
        MatrixSettings settings = pm.getMatrixSettings();
        settings.setDirection(MatrixSettings.Direction.ROW_TO_COLUMN);
        settings.setShowElementsOption(MatrixSettings.RelationOption.ALL);
        // Only the generic Dependency Matrix needs a criterion; the other kinds use the one their native type defines.
        if (kind == MatrixKinds.DEPENDENCY) {
            settings.setDependencyCriteria(Collections.singletonList(UML_DEPENDENCY_CRITERIA));
        }
        FilterSettings rows = pm.getRowSettings();
        rows.setScope(Collections.singletonList(rowScope));
        rows.setScopeDefined(true);
        rows.setTypesIncludeSubtypes(true);
        rows.setTypesIncludeCustomTypes(true);
        // Type tokens resolve to stereotypes or metaclasses; an unknown token fails the whole write.
        rows.setConvertedElementTypes(ArtifactSupport.resolveTypes(project,
                rowTypes != null ? rowTypes : kind.defaultRowTypes, "matrix row"));
        FilterSettings columns = pm.getColumnSettings();
        columns.setScope(Collections.singletonList(columnScope));
        columns.setScopeDefined(true);
        columns.setTypesIncludeSubtypes(true);
        columns.setTypesIncludeCustomTypes(true);
        columns.setConvertedElementTypes(ArtifactSupport.resolveTypes(project,
                columnTypes != null ? columnTypes : kind.defaultColumnTypes, "matrix column"));
    }

    /**
     * cameo_get_matrix (also used after create): scopes, types, rows, columns and only the populated
     * cells with their dependency entries. Fails with invalid-params for diagrams that are not a supported matrix.
     */
    private JsonObject serializeMatrix(Project project, String matrixId) {
        DiagramPresentationElement dpe = ArtifactSupport.diagram(project, matrixId, "Matrix");
        MatrixKinds kind = MatrixKinds.fromDiagramType(ArtifactSupport.diagramType(dpe));
        if (kind == null) {
            throw ToolException.invalid("Diagram '" + matrixId + "' (" + ArtifactSupport.diagramType(dpe)
                    + ") is not a supported matrix kind. Use cameo_list_matrices.");
        }
        dpe.ensureLoaded();
        Diagram diagram = dpe.getDiagram();
        // The matrix data is only computed for an active matrix view, so activate it as an open view would,
        // and undo that in the finally block.
        DependencyMatrix matrix = MatrixManager.getExistingOrCreate(diagram, dpe);
        matrix.viewCreated();
        matrix.setActive(true);
        try {
            MatrixData data = MatrixDataHelper.buildMatrix(diagram);
            PersistenceManager pm = new PersistenceManager(diagram);

            JsonObject response = ArtifactListings.matrixSummary(kind, dpe);
            response.add("rowScopes", ArtifactSupport.compactArray(pm.getRowSettings().getScope()));
            response.add("columnScopes", ArtifactSupport.compactArray(pm.getColumnSettings().getScope()));
            response.add("rowTypes", ArtifactSupport.compactArray(pm.getRowSettings().getElementTypes()));
            response.add("columnTypes", ArtifactSupport.compactArray(pm.getColumnSettings().getElementTypes()));

            Collection<Element> rowElements = data.getRowElements();
            Collection<Element> columnElements = data.getColumnElements();
            JsonArray rows = ArtifactSupport.compactArray(rowElements);
            JsonArray columns = ArtifactSupport.compactArray(columnElements);
            JsonArray cells = new JsonArray();
            // Row x column scan; empty cells are omitted, totalCellCount still reports the full grid size.
            if (rowElements != null && columnElements != null) {
                for (Element row : rowElements) {
                    for (Element column : columnElements) {
                        AbstractMatrixCell cell = data.getValue(row, column);
                        if (cell == null || cell.getDependencies() == null || cell.getDependencies().isEmpty()) {
                            continue;
                        }
                        JsonObject cellJson = new JsonObject();
                        cellJson.add("row", ElementJson.toJsonCompact(row));
                        cellJson.add("column", ElementJson.toJsonCompact(column));
                        JsonArray deps = new JsonArray();
                        for (DependencyEntry entry : cell.getDependencies()) {
                            JsonObject dep = new JsonObject();
                            dep.addProperty("type", String.valueOf(entry.getType()));
                            dep.addProperty("name", entry.getName());
                            dep.add("cause", ArtifactSupport.compactArray(entry.getCause()));
                            deps.add(dep);
                        }
                        cellJson.addProperty("dependencyCount", deps.size());
                        cellJson.add("dependencies", deps);
                        cells.add(cellJson);
                    }
                }
            }
            response.addProperty("rowCount", rows.size());
            response.addProperty("columnCount", columns.size());
            response.addProperty("totalCellCount", rows.size() * columns.size());
            response.addProperty("populatedCellCount", cells.size());
            response.addProperty("empty", data.isEmpty());
            response.add("rows", rows);
            response.add("columns", columns);
            response.add("populatedCells", cells);
            return response;
        } finally {
            matrix.setActive(false);
            matrix.viewDisposed();
        }
    }

    /** First non-null, non-empty candidate, or null. */
    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) {
                return c;
            }
        }
        return null;
    }
}
