package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.CameoLog;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** cameo_execute_macro and cameo_list_macro_engines (javax.script, Groovy first). */
final class MacroService {

    /** Engines tried after the requested one, in order. */
    static final String[] FALLBACK_ENGINES = {"groovy", "Groovy", "javascript", "js", "nashorn", "graal.js"};

    /** Not instantiable; all members are static. */
    private MacroService() {
    }

    /**
     * Script engine managers: this plugin's class loader first, then the thread context class loader.
     *
     * @return one or two managers; the second is present only when the context class loader differs
     */
    static List<ScriptEngineManager> managers() {
        List<ScriptEngineManager> out = new ArrayList<>();
        ClassLoader own = MacroService.class.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        out.add(new ScriptEngineManager(own));
        if (context != null && context != own) {
            out.add(new ScriptEngineManager(context));
        }
        return out;
    }

    /**
     * Ordered, de-duplicated engine names to try for a request.
     *
     * @param requested the engine name the agent asked for, or null or blank for none
     * @return the requested name first (trimmed), then the {@link #FALLBACK_ENGINES} not already listed
     */
    static List<String> candidates(String requested) {
        Set<String> names = new LinkedHashSet<>();
        if (requested != null && !requested.trim().isEmpty()) {
            names.add(requested.trim());
        }
        for (String f : FALLBACK_ENGINES) {
            names.add(f);
        }
        return new ArrayList<>(names);
    }

    /**
     * Implements cameo_list_macro_engines: every javax.script engine visible to either class loader,
     * de-duplicated by engine name and version. Needs no project and does not touch the EDT.
     *
     * @return {@code count}, {@code engines} (name, version, language, aliases, source) and a {@code hint}
     *     when none
     * are found
     */
    static JsonObject listEngines() {
        JsonArray engines = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        String[] sources = {"pluginClassLoader", "contextClassLoader"};
        List<ScriptEngineManager> managers = managers();
        for (int i = 0; i < managers.size(); i++) {
            for (ScriptEngineFactory f : managers.get(i).getEngineFactories()) {
                String key = f.getEngineName() + "|" + f.getEngineVersion();
                if (!seen.add(key)) {
                    continue;
                }
                JsonObject e = new JsonObject();
                e.addProperty("name", f.getEngineName());
                e.addProperty("version", f.getEngineVersion());
                e.addProperty("language", f.getLanguageName());
                e.addProperty("languageVersion", f.getLanguageVersion());
                JsonArray aliases = new JsonArray();
                for (String n : f.getNames()) {
                    aliases.add(n);
                }
                e.add("aliases", aliases);
                e.addProperty("source", sources[Math.min(i, sources.length - 1)]);
                engines.add(e);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", engines.size());
        out.add("engines", engines);
        if (engines.size() == 0) {
            out.addProperty("hint", "No javax.script engines are visible. Groovy ships with Cameo's macro support; "
                    + "check that the Macros plugin is installed and enabled.");
        }
        return out;
    }

    /**
     * Implements cameo_execute_macro: finds an engine (the requested one, then the fallbacks) and runs the
     * script on the EDT. The script manages its own sessions, so this method does not open one; see {@link
     * #run}.
     *
     * <p>Macro scripts are off by default; {@code McpRuntime} refuses this call unless "Allow macro scripts"
     * is on in Settings.
     *
     * @param a tool arguments: {@code script} (required), {@code engine} (default groovy) and {@code timeout}
     *     in
     * seconds (default 60, 5 to 600)
     *
     * @return the result object from {@link #run}
     * @throws ToolException INVALID_PARAMS for an empty script, UNSUPPORTED when no engine is found, or a
     *     timeout or
     * no-project error from {@code Edt.onEdt}
     *
     * @throws Exception any other failure while handing the work to the EDT
     */
    static JsonObject execute(Args a) throws Exception {
        String script = a.str("script");
        if (script == null || script.isEmpty()) {
            throw ToolException.invalid("script must be a non-empty string.");
        }
        String requested = a.str("engine", "groovy");
        int timeout = a.intIn("timeout", 60, 5, 600);

        ScriptEngine engine = null;
        String used = null;
        for (String name : candidates(requested)) {
            for (ScriptEngineManager m : managers()) {
                try {
                    engine = m.getEngineByName(name);
                } catch (Throwable ignored) {
                    engine = null;
                }
                if (engine != null) {
                    used = name;
                    break;
                }
            }
            if (engine != null) {
                break;
            }
        }
        if (engine == null) {
            List<String> available = new ArrayList<>();
            for (ScriptEngineManager m : managers()) {
                for (ScriptEngineFactory f : m.getEngineFactories()) {
                    available.addAll(f.getNames());
                }
            }
            throw new ToolException("UNSUPPORTED", "No script engine found for '" + requested + "'. Available "
                    + "engines: " + available + ". Groovy comes with Cameo's Macros plugin; make sure it is enabled.");
        }
        final ScriptEngine finalEngine = engine;
        final String engineUsed = used;
        return Edt.onEdt("Execute macro", project -> run(project, finalEngine, engineUsed, script), timeout);
    }

    /**
     * Runs the script with {@code project}, {@code application}, {@code primaryModel}, {@code projectKind}
     * and {@code ef} (the ElementsFactory) bound as variables, capturing stdout and stderr. A session left
     * open before the run is cancelled. A session the script leaves open is closed on success or cancelled on
     * failure, so the next tool call is not blocked.
     *
     * <p>Threading: runs on the EDT. Script errors never escape; they are reported in the result.
     *
     * @param project the active project
     * @param engine the engine to evaluate with
     * @param engineUsed the engine name that matched, echoed in the result
     * @param script the script source
     * @return {@code success}, {@code result} or {@code error} and {@code stackTrace} (cut to 8000
     *     characters),
     * {@code output}, {@code errors}, {@code engine} and any {@code warnings}
     */
    private static JsonObject run(Project project, ScriptEngine engine, String engineUsed, String script) {
        JsonArray warnings = new JsonArray();
        SessionManager sm = SessionManager.getInstance();
        if (sm.isSessionCreated(project)) {
            CameoLog.warn("Stale editing session found before macro execution; cancelling it.");
            warnings.add("A stale editing session was open before the macro and was cancelled.");
            try {
                sm.cancelSession(project);
            } catch (Exception e) {
                try {
                    sm.closeSession(project);
                } catch (Exception ignored) {
                    warnings.add("The stale session could not be closed: " + e.getMessage());
                }
            }
        }
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        ScriptContext ctx = new SimpleScriptContext();
        ctx.setWriter(new PrintWriter(output, true));
        ctx.setErrorWriter(new PrintWriter(errors, true));
        ctx.setAttribute("project", project, ScriptContext.ENGINE_SCOPE);
        ctx.setAttribute("application", Application.getInstance(), ScriptContext.ENGINE_SCOPE);
        ctx.setAttribute("primaryModel", project.getPrimaryModel(), ScriptContext.ENGINE_SCOPE);
        ctx.setAttribute("projectKind", com.cameomcp.plugin.cameo.ProjectKind.of(project), ScriptContext.ENGINE_SCOPE);
        ctx.setAttribute("ef", project.getElementsFactory(), ScriptContext.ENGINE_SCOPE);

        JsonObject response = new JsonObject();
        // Groovy resolves classes through the context class loader; point it at the engine's loader while it runs.
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        boolean success;
        try {
            Thread.currentThread().setContextClassLoader(engine.getClass().getClassLoader());
            Object result = engine.eval(script, ctx);
            success = true;
            response.addProperty("success", true);
            response.addProperty("result", result != null ? result.toString() : "null");
        } catch (Throwable e) {
            success = false;
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage() != null ? e.getMessage() : e.toString());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            response.addProperty("stackTrace", trace.length() > 8000 ? trace.substring(0, 8000) + "..." : trace);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        if (sm.isSessionCreated(project)) {
            try {
                if (success) {
                    sm.closeSession(project);
                    warnings.add("The script left an editing session open; it was closed (changes kept). Close "
                            + "sessions in the script.");
                } else {
                    sm.cancelSession(project);
                    warnings.add("The script failed with an editing session open; it was cancelled (changes in "
                            + "that session discarded).");
                }
            } catch (Exception e) {
                warnings.add("An editing session is still open: " + e.getMessage() + ". Call cameo_reset_session.");
            }
        }
        response.addProperty("output", output.toString());
        if (errors.getBuffer().length() > 0) {
            response.addProperty("errors", errors.toString());
        }
        response.addProperty("engine", engineUsed);
        if (warnings.size() > 0) {
            response.add("warnings", warnings);
        }
        return response;
    }
}
