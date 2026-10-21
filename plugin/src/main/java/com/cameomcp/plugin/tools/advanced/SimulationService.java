package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Simulation Toolkit probe-first contract (reference SimulationHandler): execution is refused. */
final class SimulationService {

    /** Simulation Toolkit manager class; must load for simulation to count as available. */
    private static final String SIMULATION_MANAGER = "com.nomagic.magicdraw.simulation.SimulationManager";
    /** Simulation Toolkit result class; must load for simulation to count as available. */
    private static final String SIMULATION_RESULT = "com.nomagic.magicdraw.simulation.execution.SimulationResult";
    /** Simulation Toolkit session class; must load for simulation to count as available. */
    private static final String SIMULATION_SESSION = "com.nomagic.magicdraw.simulation.execution.SimulationSession";
    /** Most configurations returned by configuration discovery. */
    private static final int MAX_CONFIGURATIONS = 500;
    /** Most elements walked by configuration discovery. */
    private static final int SCAN_LIMIT = 250_000;

    /** Not instantiable; all members are static. */
    private SimulationService() {
    }

    /**
     * Implements cameo_get_simulation_capabilities: available only when all three Simulation Toolkit classes
     * load. Execution is always off.
     *
     * @return the capability answer
     */
    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("simulation", "probe-first");
        JsonObject probe = Support.classProbe(SIMULATION_MANAGER, SIMULATION_RESULT, SIMULATION_SESSION);
        boolean available = probe.get("allFound").getAsBoolean();
        response.addProperty("available", available);
        response.addProperty("status", available ? "available" : "missing-plugin");
        response.add("classProbe", probe);
        response.add("pluginDirectoriesFound", Support.pluginDirectories("simulation"));
        response.addProperty("executionEnabled", false);
        response.addProperty("safeDefault", "No simulation execution without explicit allow_execute and live runtime proof.");
        return response;
    }

    /**
     * Implements cameo_list_simulation_configurations: a heuristic list of elements that look like simulation
     * configurations. Without the toolkit classes it returns an unsupported answer rather than an error, so
     * agents can branch on it.
     *
     * @return the configurations with truncation flags, or the unsupported answer
     * @throws Exception when no project is open or the hand-off to the EDT fails
     */
    static JsonObject configurations() throws Exception {
        JsonObject caps = capabilities();
        if (!caps.get("available").getAsBoolean()) {
            JsonObject response = Support.unsupported("simulation", "Simulation Toolkit runtime classes were not found.",
                    "Install/enable Cameo Simulation Toolkit, then call cameo_get_simulation_capabilities again.");
            response.add("capabilities", caps);
            return response;
        }
        return Edt.read(project -> {
            JsonArray configurations = new JsonArray();
            boolean truncated = scan(project, configurations);
            JsonObject response = capabilities();
            response.addProperty("count", configurations.size());
            response.addProperty("truncated", truncated);
            response.add("configurations", configurations);
            return response;
        });
    }

    /**
     * Depth-first walk of the primary model, collecting candidate configurations.
     *
     * @param project the active project
     * @param out receives compact JSON for each candidate
     * @return true when the walk or the result list hit its cap
     */
    private static boolean scan(Project project, JsonArray out) {
        Element root = project.getPrimaryModel();
        if (root == null) {
            return false;
        }
        Deque<Element> stack = new ArrayDeque<>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty()) {
            Element element = stack.pop();
            if (++visited > SCAN_LIMIT) {
                return true;
            }
            if (element != root && looksSimulationConfiguration(element)) {
                if (out.size() >= MAX_CONFIGURATIONS) {
                    return true;
                }
                out.add(Support.ref(element));
            }
            Collection<Element> children = element.getOwnedElement();
            if (children != null) {
                List<Element> list = new ArrayList<>(children);
                for (int i = list.size() - 1; i >= 0; i--) {
                    stack.push(list.get(i));
                }
            }
        }
        return false;
    }

    /**
     * Keyword heuristic over the class, name and stereotypes ("simulation", "config", "parametric"). It
     * deliberately matches too much: the toolkit's configuration stereotype API is not used, so this is
     * discovery, not classification.
     *
     * @param element the element
     * @return true when any keyword appears
     */
    private static boolean looksSimulationConfiguration(Element element) {
        StringBuilder text = new StringBuilder(element.getClass().getSimpleName()).append(' ');
        if (element instanceof NamedElement) {
            String name = ((NamedElement) element).getName();
            if (name != null) {
                text.append(name).append(' ');
            }
        }
        try {
            // StereotypesHelper can throw on elements in a half-loaded state; a failure just means no stereotype text.
            List<Stereotype> stereotypes = StereotypesHelper.getStereotypes(element);
            if (stereotypes != null) {
                for (Stereotype s : stereotypes) {
                    if (s.getName() != null) {
                        text.append(s.getName()).append(' ');
                    }
                }
            }
        } catch (Exception ignored) {
            // best-effort discovery
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        return lower.contains("simulation") || lower.contains("config") || lower.contains("parametric");
    }

    /**
     * Implements cameo_run_simulation_preview: echoes the request and resolves configuration_id. Nothing is
     * executed.
     *
     * @param p the tool arguments
     * @return a preview-accepted answer with the capabilities and the resolved configuration
     */
    static JsonObject preview(JsonObject p) {
        JsonObject response = Support.previewAccepted("simulation", Support.request(p),
                "Simulation run preview recorded. No executable model was run.");
        response.add("capabilities", capabilities());
        response.addProperty("requiresAllowExecute", true);
        response.addProperty("requiresRuntimeClasses", true);
        String configId = Gs.str(p, "configuration_id");
        Project project = Support.currentProject();
        if (configId != null && project != null) {
            Element e = Support.element(project, configId);
            if (e == null) {
                response.addProperty("configurationProblem", "Configuration element '" + configId + "' not found.");
            } else {
                response.add("configuration", Support.ref(e));
            }
        }
        return response;
    }

    /**
     * Implements cameo_run_simulation, which is always refused. The refusal payload tells the agent to use
     * the UI.
     *
     * @param p the tool arguments, echoed in the refusal
     * @return never returns normally
     * @throws com.cameomcp.mcp.ToolException UNSUPPORTED, always
     */
    static JsonObject run(JsonObject p) {
        JsonObject response = Support.unsupported("simulation",
                "Simulation execution is disabled until runtime classes and a disposable executable model are validated.",
                "Use cameo_run_simulation_preview, then run the simulation from the Cameo UI (Simulation > Run).");
        response.add("request", Support.request(p));
        response.add("capabilities", capabilities());
        throw Support.refusal("UNSUPPORTED", response);
    }

    /**
     * Implements cameo_get_simulation_result. There is no job store, so every run_id is NOT_FOUND.
     *
     * @param p the tool arguments: {@code run_id}
     * @return never returns normally
     * @throws com.cameomcp.mcp.ToolException NOT_FOUND, always (INVALID_PARAMS when run_id is missing)
     */
    static JsonObject result(JsonObject p) {
        String runId = Gs.reqStr(p, "run_id");
        JsonObject response = new JsonObject();
        response.addProperty("runId", runId);
        response.addProperty("status", "not-found");
        response.addProperty("message", "Simulation jobs are not persisted until execution support is enabled.");
        throw Support.refusal("NOT_FOUND", response);
    }

    /**
     * Implements cameo_terminate_simulation. Nothing can be running, so this is NOT_FOUND too.
     *
     * @param p the tool arguments: {@code run_id}
     * @return never returns normally
     * @throws com.cameomcp.mcp.ToolException NOT_FOUND, always (INVALID_PARAMS when run_id is missing)
     */
    static JsonObject terminate(JsonObject p) {
        String runId = Gs.reqStr(p, "run_id");
        JsonObject response = Support.unsupported("simulation",
                "No active simulation job store exists in probe-first mode.",
                "Enable execution only after live simulation validation.");
        response.addProperty("runId", runId);
        throw Support.refusal("NOT_FOUND", response);
    }
}
