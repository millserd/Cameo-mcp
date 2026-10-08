package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.ProjectKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.core.project.ProjectDescriptor;
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory;
import com.nomagic.magicdraw.core.project.ProjectsManager;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** cameo_get_project, cameo_save_project and the live UI context tools. */
final class ProjectUiService {

    private ProjectUiService() {
    }

    // ------------------------------------------------------------------ project

    static JsonObject getProject() throws Exception {
        return Edt.read(project -> {
            JsonObject json = new JsonObject();
            json.addProperty("name", project.getName());
            String fileName = project.getFileName();
            json.addProperty("filePath", fileName != null ? fileName : "");
            json.addProperty("isRemote", project.isRemote());
            json.addProperty("isDirty", project.isDirty());
            String kind = ProjectKind.of(project);
            json.addProperty("projectKind", kind);
            Package primary = project.getPrimaryModel();
            if (primary != null) {
                json.addProperty("primaryModelId", primary.getID());
                json.addProperty("primaryModelName", primary.getName() != null ? primary.getName() : "");
            }
            if (ProjectKind.SYSML2.equals(kind)) {
                json.addProperty("hint", "SysML v2 project: use the cameo_sysml2_* tools (cameo_sysml2_list_roots, "
                        + "cameo_sysml2_get_text, cameo_sysml2_create_from_text). UML / SysML v1 tools do not apply.");
            }
            return json;
        });
    }

    /** Saving runs on the EDT without a model session (a session would block the save). */
    static JsonObject saveProject() throws Exception {
        return Edt.onEdt("Save project", project -> {
            if (SessionManager.getInstance().isSessionCreated(project)) {
                throw new ToolException("SESSION_OPEN", "An editing session is open, so the project cannot be saved "
                        + "consistently. Call cameo_reset_session (or wait for the running change), then retry.");
            }
            ProjectsManager pm = Application.getInstance().getProjectsManager();
            ProjectDescriptor descriptor = ProjectDescriptorsFactory.getDescriptorForProject(project);
            if (descriptor == null) {
                throw new ToolException("PRECONDITION_FAILED", "Cannot save: Cameo has no file location for this "
                        + "project (it was never saved). Ask the user to use File > Save As once, then retry.");
            }
            boolean success = pm.saveProject(descriptor, true);
            JsonObject json = new JsonObject();
            json.addProperty("saved", success);
            json.addProperty("filePath", project.getFileName() != null ? project.getFileName() : "");
            json.addProperty("isDirty", project.isDirty());
            if (!success) {
                json.addProperty("hint", "Cameo reported the save as unsuccessful. Check the Cameo window for a "
                        + "dialog (read-only file, Teamwork lock, validation) and the Notification window.");
            }
            return json;
        }, 300);
    }

    // ------------------------------------------------------------------ UI state

    static JsonObject uiState(boolean summaryOnly) throws Exception {
        return Edt.onEdt("Read UI state", project -> buildState(project, summaryOnly), 20);
    }

    static JsonObject activeDiagram() throws Exception {
        JsonObject state = uiState(false);
        JsonObject out = new JsonObject();
        out.add("activeDiagram", state.get("activeDiagram"));
        out.add("warnings", state.get("warnings"));
        return out;
    }

    static JsonObject selection() throws Exception {
        JsonObject state = uiState(false);
        JsonObject out = new JsonObject();
        for (String k : new String[]{"selection", "selectedElements", "selectedPresentations", "browserSelection",
                "warnings"}) {
            out.add(k, state.get(k));
        }
        return out;
    }

    private static JsonObject buildState(Project project, boolean summaryOnly) {
        JsonArray warnings = new JsonArray();
        JsonObject response = new JsonObject();
        response.add("project", projectJson(project, warnings));

        DiagramPresentationElement active = findActiveDiagram(project, warnings);
        response.add("activeDiagram", active != null ? diagramSummary(active) : new JsonObject());

        JsonArray selectedPresentations = selectedPresentations(active, warnings);
        JsonArray browserSelection = browserSelection(project, warnings);
        JsonArray selectedElements = selectedElements(selectedPresentations, browserSelection);

        JsonObject selection = new JsonObject();
        selection.addProperty("selectedElementCount", selectedElements.size());
        selection.addProperty("selectedPresentationCount", selectedPresentations.size());
        selection.addProperty("browserSelectionCount", browserSelection.size());
        response.add("selection", selection);
        response.add("selectedElements", selectedElements);
        response.add("selectedPresentations", summaryOnly ? limit(selectedPresentations, 50) : selectedPresentations);
        response.add("browserSelection", browserSelection);
        response.add("warnings", warnings);
        return response;
    }

    private static JsonObject projectJson(Project project, JsonArray warnings) {
        JsonObject json = new JsonObject();
        json.addProperty("name", project.getName() != null ? project.getName() : "");
        String file = null;
        Object f = invoke(project, "getFile", null);
        if (f != null) {
            Object path = invoke(f, "getAbsolutePath", null);
            file = String.valueOf(path != null ? path : f);
        }
        if (file == null) {
            try {
                file = project.getFileName();
            } catch (Exception ignored) {
                // reported below
            }
        }
        if (file != null && !file.isEmpty()) {
            json.addProperty("filePath", file);
        } else {
            warnings.add("Project file path unavailable");
        }
        try {
            json.addProperty("projectKind", ProjectKind.of(project));
            Element primary = project.getPrimaryModel();
            if (primary != null) {
                json.addProperty("primaryModelId", primary.getID());
            }
        } catch (Exception e) {
            warnings.add("Primary model unavailable: " + e.getMessage());
        }
        try {
            json.addProperty("isDirty", project.isDirty());
        } catch (Exception e) {
            warnings.add("Project dirty state unavailable: " + e.getMessage());
        }
        return json;
    }

    private static DiagramPresentationElement findActiveDiagram(Project project, JsonArray warnings) {
        try {
            DiagramPresentationElement d = project.getActiveDiagram();
            if (d != null) {
                return d;
            }
        } catch (Throwable t) {
            warnings.add("Project.getActiveDiagram unavailable: " + t);
        }
        Object app = Application.getInstance();
        Object candidate = invoke(app, "getActiveDiagram", null);
        if (candidate instanceof DiagramPresentationElement) {
            return (DiagramPresentationElement) candidate;
        }
        return null;
    }

    static JsonObject diagramSummary(DiagramPresentationElement dpe) {
        JsonObject json = new JsonObject();
        Diagram diagram = dpe.getDiagram();
        if (diagram != null) {
            json.addProperty("id", diagram.getID());
        }
        json.addProperty("name", dpe.getName() != null ? dpe.getName() : "");
        String type = "";
        try {
            type = dpe.getDiagramType() != null ? dpe.getDiagramType().getType() : "";
        } catch (Exception ignored) {
            // leave blank
        }
        json.addProperty("type", type);
        if (diagram != null && diagram.getOwner() != null) {
            json.add("owner", ElementJson.toJsonCompact(diagram.getOwner()));
        }
        return json;
    }

    static JsonObject presentationSummary(PresentationElement pe) {
        JsonObject json = new JsonObject();
        json.addProperty("presentationId", pe.getID());
        json.addProperty("presentationClassName", pe.getClass().getName());
        json.addProperty("shapeType", pe.getClass().getSimpleName());
        try {
            Element element = pe.getElement();
            if (element != null) {
                json.add("element", ElementJson.toJsonCompact(element));
                json.addProperty("elementId", element.getID());
                if (element instanceof NamedElement) {
                    json.addProperty("elementName", ((NamedElement) element).getName());
                }
            }
        } catch (Exception e) {
            json.addProperty("elementWarning", e.getMessage());
        }
        try {
            Rectangle b = pe.getBounds();
            if (b != null) {
                JsonObject bounds = new JsonObject();
                bounds.addProperty("x", b.x);
                bounds.addProperty("y", b.y);
                bounds.addProperty("width", b.width);
                bounds.addProperty("height", b.height);
                json.add("bounds", bounds);
            }
        } catch (Exception e) {
            json.addProperty("boundsWarning", e.getMessage());
        }
        List<PresentationElement> children = pe.getPresentationElements();
        json.addProperty("childCount", children != null ? children.size() : 0);
        return json;
    }

    private static JsonArray selectedPresentations(DiagramPresentationElement active, JsonArray warnings) {
        JsonArray array = new JsonArray();
        if (active == null) {
            return array;
        }
        Object selected = invoke(active, "getSelected", null);
        if (selected == null) {
            selected = invoke(active, "getSelectedPresentationElements", null);
        }
        if (selected instanceof Collection<?>) {
            for (Object item : (Collection<?>) selected) {
                if (item instanceof PresentationElement) {
                    array.add(presentationSummary((PresentationElement) item));
                }
            }
        } else if (selected instanceof PresentationElement) {
            array.add(presentationSummary((PresentationElement) selected));
        } else {
            warnings.add("Selected presentation API was unavailable or returned no presentation collection");
        }
        return array;
    }

    private static JsonArray browserSelection(Project project, JsonArray warnings) {
        JsonArray array = new JsonArray();
        Object browser = null;
        try {
            browser = project.getBrowser();
        } catch (Throwable ignored) {
            // try the main frame below
        }
        if (browser == null) {
            Object mainFrame = invoke(Application.getInstance(), "getMainFrame", null);
            browser = invoke(mainFrame, "getBrowser", null);
        }
        if (browser == null) {
            warnings.add("Model browser unavailable");
            return array;
        }
        Object tree = invoke(browser, "getContainmentTree", null);
        if (tree == null) {
            tree = invoke(browser, "getActiveTree", null);
        }
        Object nodes = invoke(tree, "getSelectedNodes", null);
        if (nodes == null) {
            nodes = invoke(tree, "getSelectedNode", null);
        }
        if (nodes instanceof Object[]) {
            for (Object node : (Object[]) nodes) {
                addBrowserNode(array, node);
            }
        } else if (nodes instanceof Collection<?>) {
            for (Object node : (Collection<?>) nodes) {
                addBrowserNode(array, node);
            }
        } else if (nodes != null) {
            addBrowserNode(array, nodes);
        } else if (tree == null) {
            warnings.add("Containment tree selection API unavailable");
        }
        return array;
    }

    private static void addBrowserNode(JsonArray array, Object node) {
        if (node == null) {
            return;
        }
        Object user = invoke(node, "getUserObject", null);
        Object element = user instanceof Element ? user : invoke(node, "getElement", null);
        if (element instanceof Element) {
            array.add(ElementJson.toJsonCompact((Element) element));
        }
    }

    private static JsonArray selectedElements(JsonArray presentations, JsonArray browser) {
        JsonArray array = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < presentations.size(); i++) {
            JsonObject p = presentations.get(i).getAsJsonObject();
            if (p.has("element")) {
                JsonObject e = p.getAsJsonObject("element");
                if (e.has("id") && seen.add(e.get("id").getAsString())) {
                    array.add(e);
                }
            }
        }
        for (int i = 0; i < browser.size(); i++) {
            JsonObject e = browser.get(i).getAsJsonObject();
            if (e.has("id") && seen.add(e.get("id").getAsString())) {
                array.add(e);
            }
        }
        return array;
    }

    private static JsonArray limit(JsonArray array, int max) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < array.size() && i < max; i++) {
            out.add(array.get(i));
        }
        return out;
    }

    /** Reflective zero-arg call; null when the method is missing or fails (UI APIs vary by release). */
    static Object invoke(Object target, String method, JsonArray warnings) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Exception e) {
            if (warnings != null) {
                warnings.add(target.getClass().getSimpleName() + "." + method + " unavailable");
            }
            return null;
        }
    }
}
