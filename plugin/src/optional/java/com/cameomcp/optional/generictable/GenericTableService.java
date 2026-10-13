package com.cameomcp.optional.generictable;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.plugin.tools.GsonService;
import com.cameomcp.plugin.tools.artifacts.ArtifactSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.generictable.GenericTableManager;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.properties.Property;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Native Generic Tables through the Generic Table plugin API. */
public final class GenericTableService implements GsonService {

    public GenericTableService() {
    }

    // A LinkageError means the installed Generic Table API differs from the one compiled against;
    // surface it as UNSUPPORTED rather than an internal error.
    @Override
    public JsonObject call(String operation, JsonObject params) throws Exception {
        try {
            return dispatch(operation, params);
        } catch (LinkageError e) {
            throw new ToolException("UNSUPPORTED", "The Generic Table API of this Cameo version does not match what "
                    + "this tool expects (" + e + ").");
        }
    }

    /** Routes "get", "columns" and "create" (bound in ArtifactBindings) to their implementations. */
    private JsonObject dispatch(String operation, JsonObject params) throws Exception {
        switch (operation) {
            case "get": {
                String id = Gs.reqStr(params, "table_id").trim();
                return Edt.read(project -> serializeTable(project, id));
            }
            case "columns":
                return columns(params);
            case "create":
                return create(params);
            default:
                throw new ToolException("NOT_IMPLEMENTED", "Unknown generic table operation " + operation);
        }
    }

    /**
     * cameo_list_generic_table_columns: column ids the Generic Table plugin offers for a basis, given
     * either a concrete element or a type token (resolved to its stereotype or metaclass).
     */
    private JsonObject columns(JsonObject p) throws Exception {
        String elementId = ArtifactSupport.optStr(p, "element_id");
        String elementType = ArtifactSupport.optStr(p, "element_type");
        if (elementId == null && elementType == null) {
            throw ToolException.invalid("Provide element_id or element_type (for example element_type='Block').");
        }
        return Edt.read(project -> {
            Element basis = elementId != null
                    ? ArtifactSupport.element(project, elementId, "Column basis element")
                    : ArtifactSupport.resolveTypeReference(project, elementType, "Generic Table element");
            JsonArray columns = new JsonArray();
            Collection<String> ids = GenericTableManager.getPossibleColumnIDs(basis);
            if (ids != null) {
                for (String id : ids) {
                    // The API only returns ids, so the id doubles as the display name here.
                    JsonObject column = new JsonObject();
                    column.addProperty("id", id);
                    column.addProperty("name", id);
                    columns.add(column);
                }
            }
            JsonObject response = new JsonObject();
            response.add("basis", ElementJson.toJsonCompact(basis));
            response.addProperty("count", columns.size());
            response.add("columns", columns);
            return response;
        });
    }

    /**
     * cameo_create_generic_table: creates the table under a Namespace parent, then applies the optional
     * element types, scope, explicit rows and columns, and returns it in the cameo_get_generic_table shape.
     */
    private JsonObject create(JsonObject p) throws Exception {
        String parentId = Gs.reqStr(p, "parent_id").trim();
        String name = ArtifactSupport.optStr(p, "name");
        String finalName = name == null ? ArtifactSupport.GENERIC_TABLE_TYPE : name;
        List<String> elementTypes = ArtifactSupport.optStrList(p, "element_types");
        List<String> scopeIds = ArtifactSupport.optStrList(p, "scope_ids");
        List<String> rowIds = ArtifactSupport.optStrList(p, "row_element_ids");
        List<String> columnIds = ArtifactSupport.optStrList(p, "column_ids");
        return Edt.write("Create Generic Table", project -> {
            Element parent = ArtifactSupport.element(project, parentId, "Parent element");
            if (!(parent instanceof Namespace)) {
                throw ToolException.invalid("Parent element '" + parentId + "' is not a Namespace; pick a package.");
            }
            Diagram diagram = ModelElementsManager.getInstance()
                    .createDiagram(ArtifactSupport.GENERIC_TABLE_TYPE, (Namespace) parent);
            if (diagram == null) {
                throw new ToolException("TOOL_FAILED", "Cameo did not create the Generic Table.");
            }
            diagram.setName(finalName);
            if (elementTypes != null && !elementTypes.isEmpty()) {
                GenericTableManager.setTableElementTypes(diagram,
                        ArtifactSupport.resolveTypes(project, elementTypes, "Generic Table element"));
            }
            if (scopeIds != null && !scopeIds.isEmpty()) {
                GenericTableManager.setScope(diagram, ArtifactSupport.elements(project, scopeIds, "Scope element"));
            }
            if (rowIds != null && !rowIds.isEmpty()) {
                for (Element row : ArtifactSupport.elements(project, rowIds, "Row element")) {
                    GenericTableManager.addRowElement(diagram, row);
                }
            }
            if (columnIds != null && !columnIds.isEmpty()) {
                GenericTableManager.addColumnsById(diagram, columnIds);
            }
            refresh(diagram);
            JsonObject response = new JsonObject();
            response.addProperty("created", true);
            response.add("table", serializeTable(project, diagram.getID()));
            return response;
        }, 120);
    }

    /**
     * cameo_get_generic_table: scope, rows, columns and every cell as display text. A diagram of another
     * type is rejected; the table is refreshed first so the cell values are current.
     */
    private JsonObject serializeTable(Project project, String tableId) {
        Object o = project.getElementByID(tableId);
        if (!(o instanceof Diagram)) {
            throw new ToolException("NOT_FOUND", "Generic Table '" + tableId + "' not found. Use "
                    + "cameo_list_generic_tables.");
        }
        Diagram diagram = (Diagram) o;
        DiagramPresentationElement dpe = project.getDiagram(diagram);
        if (dpe != null && !ArtifactSupport.GENERIC_TABLE_TYPE.equals(ArtifactSupport.diagramType(dpe))) {
            throw ToolException.invalid("Diagram '" + tableId + "' is a " + ArtifactSupport.diagramType(dpe)
                    + ", not a Generic Table. Use cameo_list_generic_tables.");
        }
        refresh(diagram);
        JsonObject response = ArtifactSupport.diagramSummary(dpe, ArtifactSupport.GENERIC_TABLE_TYPE);
        response.add("scope", ArtifactSupport.compactArray(GenericTableManager.getScope(diagram)));
        JsonArray rows = ArtifactSupport.compactArray(GenericTableManager.getRowElements(diagram));
        response.add("rows", rows);
        response.addProperty("rowCount", rows.size());

        List<String> columnIds = GenericTableManager.getColumnIds(diagram);
        List<String> columnNames = GenericTableManager.getColumnNames(diagram);
        JsonArray columns = new JsonArray();
        if (columnIds != null) {
            for (int i = 0; i < columnIds.size(); i++) {
                JsonObject column = new JsonObject();
                column.addProperty("id", columnIds.get(i));
                // Column names may be missing or shorter than the id list; fall back to the id.
                column.addProperty("name", columnNames != null && i < columnNames.size()
                        ? columnNames.get(i) : columnIds.get(i));
                columns.add(column);
            }
        }
        response.add("columns", columns);
        response.addProperty("columnCount", columns.size());

        JsonArray cells = new JsonArray();
        Map<Element, Map<String, Property>> values = GenericTableManager.getCellValues(diagram);
        if (values != null) {
            for (Map.Entry<Element, Map<String, Property>> rowEntry : values.entrySet()) {
                if (rowEntry.getKey() == null || rowEntry.getValue() == null) {
                    continue;
                }
                JsonObject rowJson = ElementJson.toJsonCompact(rowEntry.getKey());
                for (Map.Entry<String, Property> cellEntry : rowEntry.getValue().entrySet()) {
                    JsonObject cell = new JsonObject();
                    cell.add("row", rowJson.deepCopy());
                    cell.addProperty("columnId", cellEntry.getKey());
                    Property value = cellEntry.getValue();
                    String text;
                    // Prefer the property's display text; some property kinds throw, so fall back to the raw value.
                    try {
                        text = value != null ? value.getValueStringRepresentation() : "";
                    } catch (RuntimeException e) {
                        text = value != null ? String.valueOf(value.getValue()) : "";
                    }
                    cell.addProperty("value", text);
                    cells.add(cell);
                }
            }
        }
        response.add("cells", cells);
        response.addProperty("cellCount", cells.size());
        return response;
    }

    /**
     * Recomputes the table and waits for its cell values. Values still loading after the API's
     * timeout are serialized as they are; an interrupt is re-flagged rather than swallowed.
     */
    private static void refresh(Diagram diagram) {
        GenericTableManager.refreshTable(diagram);
        try {
            GenericTableManager.waitForCellValuesLoad(diagram);
        } catch (TimeoutException e) {
            // values that are not loaded yet are reported as-is
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
