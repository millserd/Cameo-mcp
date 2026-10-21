package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonElement;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Report Wizard templates and generation (reference ReportWizardHandler). The Report Wizard is
 * an optional plugin, so every call goes through reflection.
 */
final class ReportService {

    /** Report Wizard's template registry; its static listTemplates() returns the installed templates. */
    private static final String TEMPLATE_HELPER = "com.nomagic.magicdraw.magicreport.helper.TemplateHelper";
    /** Report Wizard task that renders one template bean to a file. */
    private static final String GENERATE_TASK = "com.nomagic.magicdraw.magicreport.GenerateTask";
    /** Report Wizard command-line entry point; only probed, never called. */
    private static final String REPORT_COMMAND_LINE = "com.nomagic.magicdraw.magicreport.commandline.ReportCommandLine";
    /** Bean type of one template, the constructor argument of {@link #GENERATE_TASK}. */
    private static final String TEMPLATE_BEAN = "com.nomagic.magicdraw.magicreport.ui.bean.TemplateBean";
    /** Bean holding a report's output file, format and viewer settings. */
    private static final String REPORT_PROPERTY_BEAN = "com.nomagic.magicdraw.magicreport.ui.bean.ReportPropertyBean";
    /** Bean holding a report's scope: package IDs and recursion flags. */
    private static final String PACKAGE_SELECTION_BEAN = "com.nomagic.magicdraw.magicreport.ui.bean.PackageSelectionBean";
    /** Generic Cameo task class whose static executeTaskOnSameThread runs a task synchronously. */
    private static final String TASK = "com.nomagic.task.Task";

    /**
     * Completed generations by jobId. Generation is synchronous, so only finished jobs exist. The last 50 are
     * kept, in memory only, and are lost when Cameo restarts. Access is synchronized.
     */
    private static final Map<String, JsonObject> JOBS = Collections.synchronizedMap(
            new LinkedHashMap<String, JsonObject>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
                    return size() > 50;
                }
            });

    /** Static helpers only. */
    private ReportService() {
    }

    /**
     * cameo_get_report_capabilities. Report Wizard counts as available when at least two of the three probed
     * classes load (ReportCommandLine is not used for generation, so it alone may be missing).
     *
     * @return the capability object; safe to call with no project open
     */
    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("reportWizard", "native-direct");
        JsonObject classProbe = Support.classProbe(TEMPLATE_HELPER, GENERATE_TASK, REPORT_COMMAND_LINE);
        boolean available = classProbe.get("classesFound").getAsJsonArray().size() >= 2;
        response.addProperty("available", available);
        response.addProperty("status", available ? "available" : "missing-plugin");
        response.add("classProbe", classProbe);
        response.add("pluginDirectoriesFound", Support.pluginDirectories("reportwizard", "report"));
        JsonArray formats = new JsonArray();
        for (String f : new String[]{"docx", "pdf", "html", "xlsx", "pptx"}) {
            formats.add(f);
        }
        response.add("knownOutputFormats", formats);
        response.addProperty("generationMode", available ? "native-generate-task" : "unavailable");
        response.addProperty("generationWriteEnabled", available);
        response.addProperty("requiresOutputPath", true);
        return response;
    }

    /**
     * cameo_list_report_templates. Lists templates via the native TemplateHelper.listTemplates(); a
     * template's list index is its stable "id". Failures do not throw: templateDiscovery becomes
     * "unavailable" or "failed" with a reason.
     *
     * @return the capabilities plus templates, count and templateDiscovery
     */
    static JsonObject templates() {
        JsonObject response = capabilities();
        JsonArray templates = new JsonArray();
        try {
            int index = 0;
            for (Object template : listTemplates()) {
                templates.add(serializeTemplate(template, index++));
            }
            response.addProperty("templateDiscovery", "native");
        } catch (ClassNotFoundException e) {
            response.addProperty("templateDiscovery", "unavailable");
            response.addProperty("reason", "Report Wizard TemplateHelper class is not available (Report Wizard plugin missing or disabled).");
        } catch (Exception e) {
            response.addProperty("templateDiscovery", "failed");
            response.addProperty("reason", rootMessage(e));
        }
        response.addProperty("count", templates.size());
        response.add("templates", templates);
        return response;
    }

    /** Client-shaped request body (camelCase keys, as the reference route received it). */
    private static JsonObject body(JsonObject p) {
        JsonObject b = new JsonObject();
        copy(p, "template_id", b, "templateId");
        copy(p, "template_name", b, "templateName");
        copy(p, "report_name", b, "reportName");
        copy(p, "output_path", b, "outputPath");
        copy(p, "output_format", b, "format");
        copy(p, "scope_element_ids", b, "scopeElementIds");
        copy(p, "recursive", b, "recursive");
        copy(p, "display_in_viewer", b, "displayInViewer");
        copy(p, "allow_write", b, "allowWrite");
        copy(p, "timeout_seconds", b, "timeoutSeconds");
        JsonObject params = Gs.object(p, "parameters");
        if (params != null) {
            b.add("parameters", params);
        }
        return b;
    }

    /**
     * Copies one member under a new name when it is present.
     *
     * @param from source object
     * @param key member name in from
     * @param to target object
     * @param as member name in to
     */
    private static void copy(JsonObject from, String key, JsonObject to, String as) {
        if (Gs.has(from, key)) {
            to.add(as, from.get(key));
        }
    }

    /**
     * cameo_generate_report_preview. Checks the output path and, if Report Wizard is present, resolves the
     * template exactly as generation would, so a bad template id is reported before anything is written.
     * Never throws for a bad template: the problem is returned as templateProblem.
     *
     * @param p tool arguments
     * @return a preview envelope with outputPathCheck, templateResolved or templateProblem, and nextStep
     */
    static JsonObject preview(JsonObject p) {
        JsonObject body = body(p);
        JsonObject response = Support.previewAccepted("reportWizard", body,
                "Report preview accepted. Native generation uses the same template/output selection without mutating the model.");
        JsonObject caps = capabilities();
        response.add("capabilities", caps);
        JsonObject parameters = Gs.object(body, "parameters");
        String outputPath = firstString(body, parameters, "outputPath", "outputFile");
        response.addProperty("outputPathAccepted", outputPath != null);
        response.addProperty("templateSpecified", firstString(body, parameters, "templateId", "templateName") != null);
        if (outputPath != null) {
            response.add("outputPathCheck", RequirementsCodec.describeOutputPath(outputPath));
        }
        if (caps.get("available").getAsBoolean()) {
            try {
                Object template = selectTemplate(listTemplates(), body, parameters);
                response.add("templateResolved", serializeTemplate(template, -1));
            } catch (Exception e) {
                response.addProperty("templateProblem", rootMessage(e));
            }
        }
        response.addProperty("nextStep", "Call cameo_generate_report with the same arguments plus allow_write=true.");
        return response;
    }

    /**
     * cameo_generate_report. Requires allow_write=true and an absolute output file path. Generation runs on
     * the Swing event thread via {@link Edt#onEdt} with a time limit of timeout_seconds clamped to 10..600,
     * default 120. Missing parent folders are created.
     *
     * @param p tool arguments
     * @return the completed job record plus capabilities; the record is also stored for {@link #job}
     * @throws ToolException PRECONDITION_FAILED without allow_write, INVALID_PARAMS for a missing, relative
     *     or directory output_path, UNSUPPORTED when Report Wizard is missing, TOOL_FAILED when generation
     *     fails, or NO_PROJECT and TIMEOUT from the EDT call
     * @throws Exception any other reflective failure
     */
    static JsonObject generate(JsonObject p) throws Exception {
        JsonObject body = body(p);
        JsonObject parameters = Gs.object(body, "parameters");
        if (!firstBoolean(body, parameters, "allowWrite", false)) {
            JsonObject response = Support.unsupported("reportWizard",
                    "Report generation requires allow_write=true because it writes an output file.",
                    "Call cameo_generate_report_preview first, then repeat with allow_write=true and an explicit absolute output_path.");
            response.add("request", body);
            throw Support.refusal("PRECONDITION_FAILED", response);
        }
        String outputPath = firstString(body, parameters, "outputPath", "outputFile");
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw ToolException.invalid("output_path is required (an absolute file path such as C:/reports/model.docx).");
        }
        File outputFile = new File(outputPath.trim());
        if (!outputFile.isAbsolute()) {
            throw ToolException.invalid("output_path must be absolute (got '" + outputPath + "').");
        }
        if (outputFile.isDirectory()) {
            throw ToolException.invalid("output_path '" + outputFile + "' is a directory; give a file name.");
        }
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new ToolException("TOOL_FAILED", "Could not create output directory: " + parent.getAbsolutePath());
        }
        if (!Support.classAvailable(TEMPLATE_HELPER) || !Support.classAvailable(GENERATE_TASK)) {
            JsonObject response = Support.unsupported("reportWizard", "Report Wizard API classes are not available.",
                    "Install/enable the Report Wizard plugin and check cameo_get_report_capabilities.");
            response.add("capabilities", capabilities());
            throw Support.refusal("UNSUPPORTED", response);
        }
        int timeoutSeconds = Math.max(10, Math.min(600, Gs.integer(p, "timeout_seconds", 120)));
        return Edt.onEdt("Generate Report Wizard output", project -> generateReport(project, body, parameters, outputFile),
                timeoutSeconds);
    }

    /**
     * Configures a clone of the selected template and report and runs GenerateTask synchronously via
     * Task.executeTaskOnSameThread. Success is judged by the output file, since the task reports failure
     * through getException() rather than by throwing. Runs on the EDT.
     *
     * @param project the open project
     * @param body client-shaped request from {@link #body}
     * @param parameters the nested "parameters" object, or null
     * @param outputFile absolute output file
     * @return the job record
     * @throws ToolException NOT_FOUND for an unknown template or scope element, TOOL_FAILED when the task
     *     fails or the file is not written or updated
     * @throws Exception reflective failures from the Report Wizard API
     */
    private static JsonObject generateReport(Project project, JsonObject body, JsonObject parameters, File outputFile)
            throws Exception {
        boolean existed = outputFile.isFile();
        long beforeModified = existed ? outputFile.lastModified() : 0L;
        long beforeLength = existed ? outputFile.length() : 0L;

        List<Object> templates = listTemplates();
        Object selectedTemplate = selectTemplate(templates, body, parameters);
        // Work on clones so the scope/output changes below do not leak into the Report Wizard's shared template beans.
        Object workingTemplate = cloneIfPossible(selectedTemplate);
        Object report = selectReport(workingTemplate, firstString(body, parameters, "reportName", "reportId"));
        configureReport(project, report, body, parameters, outputFile);
        invokeIfPresent(workingTemplate, "setSelectedReport", report);

        Class<?> generateTaskClass = Support.loadClass(GENERATE_TASK);
        Class<?> templateBeanClass = Support.loadClass(TEMPLATE_BEAN);
        Constructor<?> constructor = generateTaskClass.getConstructor(templateBeanClass);
        Object task = constructor.newInstance(workingTemplate);
        Class<?> taskClass = Support.loadClass(TASK);
        Method executeTask = taskClass.getMethod("executeTaskOnSameThread", taskClass);
        try {
            executeTask.invoke(null, task);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new ToolException("TOOL_FAILED", "Report Wizard generation threw " + rootMessage(e));
        }

        Object taskException = ElementJson.invokeZeroArg(task, "getException");
        boolean exists = outputFile.isFile();
        long length = exists ? outputFile.length() : 0L;
        // An existing file must have a new timestamp or size, otherwise a silent failure would look like success.
        boolean changed = !existed || outputFile.lastModified() != beforeModified || length != beforeLength;
        if (taskException != null || !exists || length <= 0L || !changed) {
            throw new ToolException("TOOL_FAILED", "Report generation failed for " + outputFile.getAbsolutePath()
                    + (taskException != null ? ": " + taskException
                    : (!changed ? ": the output file was not updated" : ": no output file was produced")));
        }
        String jobId = "inline-" + System.currentTimeMillis();
        JsonObject response = new JsonObject();
        response.addProperty("family", "reportWizard");
        response.addProperty("generated", true);
        response.addProperty("writePerformed", true);
        response.addProperty("jobId", jobId);
        response.addProperty("status", "completed");
        response.addProperty("templateName", Support.stringGetter(workingTemplate, "getName"));
        response.addProperty("reportName", Support.stringGetter(report, "getName"));
        response.addProperty("outputPath", outputFile.getAbsolutePath());
        response.addProperty("outputFormat", normalizeFormat(firstString(body, parameters, "format", "outputFormat"), outputFile));
        response.addProperty("outputBytes", length);
        response.addProperty("overwroteExisting", existed);
        response.add("request", body);
        JOBS.put(jobId, response.deepCopy());
        response.add("capabilities", capabilities());
        return response;
    }

    /**
     * cameo_get_report_job. Returns a stored completed job.
     *
     * @param p tool arguments; job_id is required
     * @return a copy of the stored job record
     * @throws ToolException INVALID_PARAMS without job_id, NOT_FOUND for an unknown or evicted job
     */
    static JsonObject job(JsonObject p) {
        String jobId = Gs.reqStr(p, "job_id").trim();
        JsonObject job = JOBS.get(jobId);
        if (job == null) {
            JsonObject details = new JsonObject();
            details.addProperty("jobId", jobId);
            details.addProperty("status", "not-found");
            details.addProperty("message", "Report generation runs inline; only jobs completed by cameo_generate_report "
                    + "since Cameo started (last 50) can be looked up.");
            throw Support.refusal("NOT_FOUND", details);
        }
        return job.deepCopy();
    }

    /**
     * Fills the report's package selection and output properties. The scope defaults to the primary model.
     * Beans are created when the template has none, and setters are called only if this Report Wizard version
     * has them.
     *
     * @param project the open project
     * @param report the report bean to configure (a clone)
     * @param body client-shaped request
     * @param parameters the nested "parameters" object, or null
     * @param outputFile absolute output file
     * @throws ToolException NOT_FOUND or SYSML2_ELEMENT for a scope ID that does not resolve
     * @throws Exception reflective failures
     */
    private static void configureReport(Project project, Object report, JsonObject body, JsonObject parameters, File outputFile)
            throws Exception {
        Object selection = ElementJson.invokeZeroArg(report, "getSelectedPackage");
        if (selection == null) {
            selection = Support.loadClass(PACKAGE_SELECTION_BEAN).getConstructor().newInstance();
            invokeIfPresent(report, "setSelectedPackage", selection);
        }
        List<String> scopeIds = firstStringList(body, parameters, "scopeElementIds", "packageIds");
        for (String id : scopeIds) {
            Support.requireElement(project, id, "Scope element");
        }
        if (scopeIds.isEmpty()) {
            scopeIds.add(com.cameomcp.plugin.cameo.ProjectKind.primaryModel(project).getID());
        }
        invokeIfPresent(selection, "setPackageIds", toStringArray(scopeIds));
        invokeIfPresent(selection, "setSelectRecursive", firstBoolean(body, parameters, "recursive", true));
        invokeIfPresent(selection, "setShowAuxiliary", firstBoolean(body, parameters, "showAuxiliary", false));
        invokeIfPresent(selection, "setShowOnlyPackageElement", firstBoolean(body, parameters, "showOnlyPackageElement", false));

        Object reportProperty = ElementJson.invokeZeroArg(report, "getReportProperty");
        if (reportProperty == null) {
            reportProperty = Support.loadClass(REPORT_PROPERTY_BEAN).getConstructor().newInstance();
            invokeIfPresent(report, "setReportProperty", reportProperty);
        }
        String format = normalizeFormat(firstString(body, parameters, "format", "outputFormat"), outputFile);
        invokeIfPresent(reportProperty, "setOutputFile", outputFile.getAbsolutePath());
        invokeIfPresent(reportProperty, "setOutputFormat", format);
        invokeIfPresent(reportProperty, "setDisplayInViewer", firstBoolean(body, parameters, "displayInViewer", false));
        invokeIfPresent(report, "setFileName", outputFile.getAbsolutePath());
    }

    /**
     * Templates known to the installed Report Wizard, via reflection on TemplateHelper.
     *
     * @return the templates in Report Wizard's order
     * @throws ClassNotFoundException when Report Wizard is not installed
     * @throws Exception other reflective failures
     */
    private static List<Object> listTemplates() throws Exception {
        Class<?> helper = Support.loadClass(TEMPLATE_HELPER);
        Object value = helper.getMethod("listTemplates").invoke(null);
        List<Object> templates = new ArrayList<>();
        if (value instanceof Collection) {
            templates.addAll((Collection<?>) value);
        }
        return templates;
    }

    /**
     * Picks a template by list index, then by name, path or template file name (case-insensitive). With no
     * template requested, the first one is used.
     *
     * @param templates templates from {@link #listTemplates}
     * @param body client-shaped request; templateId, templateName or template names the template
     * @param parameters the nested "parameters" object, or null
     * @return the selected template bean
     * @throws ToolException NOT_FOUND when there are no templates or none matches
     */
    private static Object selectTemplate(List<Object> templates, JsonObject body, JsonObject parameters) {
        if (templates.isEmpty()) {
            throw new ToolException("NOT_FOUND", "No Report Wizard templates are available.");
        }
        String requested = firstString(body, parameters, "templateId", "templateName", "template");
        if (requested == null || requested.trim().isEmpty()) {
            return templates.get(0);
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        try {
            int index = Integer.parseInt(normalized);
            if (index >= 0 && index < templates.size()) {
                return templates.get(index);
            }
        } catch (NumberFormatException ignored) {
            // fall through to name/path matching
        }
        for (Object template : templates) {
            if (equalsIgnoreCase(normalized, Support.stringGetter(template, "getName"))
                    || equalsIgnoreCase(normalized, Support.stringGetter(template, "getPath"))
                    || equalsIgnoreCase(normalized, Support.stringGetter(template, "getTemplateFileName"))) {
                return template;
            }
        }
        throw new ToolException("NOT_FOUND", "Report template '" + requested + "' not found. Use cameo_list_report_templates "
                + "for valid ids (index) and names.");
    }

    /**
     * Named report configuration, else the template's default, selected or first one.
     *
     * @param template template bean
     * @param reportName requested report name, or null
     * @return a clone of the chosen report when the bean can be cloned, else the bean itself; null if the
     *     template has none
     */
    private static Object selectReport(Object template, String reportName) {
        Object report = null;
        if (reportName != null && !reportName.trim().isEmpty()) {
            for (Object candidate : asCollection(ElementJson.invokeZeroArg(template, "getReportList"))) {
                if (equalsIgnoreCase(reportName.trim().toLowerCase(Locale.ROOT), Support.stringGetter(candidate, "getName"))) {
                    report = candidate;
                    break;
                }
            }
        }
        if (report == null) {
            report = ElementJson.invokeZeroArg(template, "getDefaultReport");
        }
        if (report == null) {
            report = ElementJson.invokeZeroArg(template, "getSelectedReport");
        }
        if (report == null) {
            Collection<?> reports = asCollection(ElementJson.invokeZeroArg(template, "getReportList"));
            if (!reports.isEmpty()) {
                report = reports.iterator().next();
            }
        }
        if (report == null) {
            throw new ToolException("PRECONDITION_FAILED", "Selected report template has no report configuration.");
        }
        return cloneIfPossible(report);
    }

    /**
     * Template metadata via whichever getters the installed TemplateBean class has.
     *
     * @param template template bean
     * @param index list index used as the id, or a negative number to omit the id fields
     * @return the template description
     */
    private static JsonObject serializeTemplate(Object template, int index) {
        JsonObject json = new JsonObject();
        if (index >= 0) {
            json.addProperty("index", index);
            json.addProperty("id", String.valueOf(index));
        }
        json.addProperty("className", template.getClass().getName());
        putGetter(json, template, "name", "getName");
        putGetter(json, template, "category", "getCategory");
        putGetter(json, template, "description", "getDescription");
        putGetter(json, template, "path", "getPath");
        putGetter(json, template, "templateFileName", "getTemplateFileName");
        putGetter(json, template, "readOnly", "getReadOnly");
        putGetter(json, template, "enabled", "getEnabled");
        putGetter(json, template, "configFile", "getConfigFile");
        putGetter(json, template, "requiredPlugins", "getRequiredPlugins");
        Object reportList = ElementJson.invokeZeroArg(template, "getReportList");
        if (reportList instanceof Collection) {
            json.addProperty("reportCount", ((Collection<?>) reportList).size());
            JsonArray names = new JsonArray();
            for (Object r : (Collection<?>) reportList) {
                names.add(Support.stringGetter(r, "getName"));
            }
            json.add("reportNames", names);
        }
        return json;
    }

    /**
     * Adds the value of a public zero-argument getter under key; skipped silently when the getter is missing
     * or fails.
     *
     * @param json target object
     * @param target bean to read
     * @param key member name to add
     * @param methodName getter name
     */
    private static void putGetter(JsonObject json, Object target, String key, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() == 0) {
                json.add(key, Support.serializeValue(method.invoke(target), true, false));
            }
        } catch (Throwable ignored) {
            // TemplateBean versions differ across releases.
        }
    }

    /**
     * Uses the bean's public clone() when it has one.
     *
     * @param value bean to copy
     * @return the clone, or the original object when it cannot be cloned
     */
    private static Object cloneIfPossible(Object value) {
        Object clone = ElementJson.invokeZeroArg(value, "clone");
        return clone != null ? clone : value;
    }

    /**
     * Calls a public one-argument method by name if a compatible overload exists. Null targets, null
     * arguments and missing methods are skipped silently. A primitive parameter is treated as compatible and
     * relies on reflection to unbox the argument.
     *
     * @param target object to call, or null
     * @param methodName setter name
     * @param arg argument, or null
     * @throws Exception if the setter itself fails
     */
    private static void invokeIfPresent(Object target, String methodName, Object arg) throws Exception {
        if (target == null || arg == null) {
            return;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.isPrimitive() || parameterType.isAssignableFrom(arg.getClass())
                    || (parameterType.isArray() && arg.getClass().isArray())) {
                method.invoke(target, arg);
                return;
            }
        }
    }

    /**
     * First string value among keys. For each key the top-level body is checked before the nested parameters
     * object.
     *
     * @param body client-shaped request
     * @param parameters the nested "parameters" object, or null
     * @param keys keys to try, in order
     * @return the first string value found, or null
     */
    private static String firstString(JsonObject body, JsonObject parameters, String... keys) {
        for (String key : keys) {
            String value = stringValue(body, key);
            if (value != null) {
                return value;
            }
            value = stringValue(parameters, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * A primitive member as a string.
     *
     * @param source object to read, or null
     * @param key member name
     * @return the string form, or null when the member is missing, null or not a primitive
     */
    private static String stringValue(JsonObject source, String key) {
        if (source == null || !source.has(key)) {
            return null;
        }
        JsonElement value = source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    /**
     * Same lookup order as {@link #firstString}, for lists. A single string is accepted as a one-item list.
     *
     * @param body client-shaped request
     * @param parameters the nested "parameters" object, or null
     * @param keys keys to try, in order
     * @return the first non-empty list found, or a new empty list
     */
    private static List<String> firstStringList(JsonObject body, JsonObject parameters, String... keys) {
        for (String key : keys) {
            List<String> values = stringList(body, key);
            if (!values.isEmpty()) {
                return values;
            }
            values = stringList(parameters, key);
            if (!values.isEmpty()) {
                return values;
            }
        }
        return new ArrayList<>();
    }

    /**
     * Strings from an array member, or a single primitive as a one-item list. Non-primitive array items are
     * skipped.
     *
     * @param source object to read, or null
     * @param key member name
     * @return a new list, empty when the member is missing
     */
    private static List<String> stringList(JsonObject source, String key) {
        List<String> values = new ArrayList<>();
        if (source == null || !source.has(key)) {
            return values;
        }
        JsonElement value = source.get(key);
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    values.add(item.getAsString());
                }
            }
        } else if (value.isJsonPrimitive()) {
            values.add(value.getAsString());
        }
        return values;
    }

    /**
     * Boolean from the body, else from the nested parameters object, else the default.
     *
     * @param body client-shaped request
     * @param parameters the nested "parameters" object, or null
     * @param key member name
     * @param defaultValue value when neither object has a primitive under key
     * @return the flag
     */
    private static boolean firstBoolean(JsonObject body, JsonObject parameters, String key, boolean defaultValue) {
        Boolean value = booleanValue(body, key);
        if (value != null) {
            return value;
        }
        value = booleanValue(parameters, key);
        return value != null ? value : defaultValue;
    }

    /**
     * A primitive member as a boolean.
     *
     * @param source object to read, or null
     * @param key member name
     * @return the value, or null when the member is missing or not a primitive
     */
    private static Boolean booleanValue(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonPrimitive()) {
            return null;
        }
        return Gs.bool(source, key, false);
    }

    /**
     * String[] of package IDs for PackageSelectionBean.setPackageIds, built reflectively so it passes {@link
     * #invokeIfPresent}'s array check.
     *
     * @param values package IDs
     * @return a new String array
     */
    private static Object toStringArray(List<String> values) {
        Object array = Array.newInstance(String.class, values.size());
        for (int i = 0; i < values.size(); i++) {
            Array.set(array, i, values.get(i));
        }
        return array;
    }

    /**
     * Report output format: the explicit format, else the output file's extension, else docx.
     *
     * @param requested requested format, or null
     * @param outputFile output file whose extension is the fallback
     * @return the lower-case format
     */
    static String normalizeFormat(String requested, File outputFile) {
        if (requested != null && !requested.trim().isEmpty()) {
            return requested.trim().toLowerCase(Locale.ROOT);
        }
        String name = outputFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "docx";
    }

    /**
     * Null-safe view of a getter result as a collection.
     *
     * @param value any object
     * @return value when it is a collection, else an empty list
     */
    private static Collection<?> asCollection(Object value) {
        return value instanceof Collection ? (Collection<?>) value : Collections.emptyList();
    }

    /**
     * Case-insensitive, trimmed comparison against an already normalized needle.
     *
     * @param normalizedNeedle trimmed lower-case text to look for
     * @param candidate value to compare, or null
     * @return true when candidate, trimmed and lower-cased, equals the needle
     */
    private static boolean equalsIgnoreCase(String normalizedNeedle, String candidate) {
        return candidate != null && normalizedNeedle.equals(candidate.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Innermost cause's class and message, which is usually the useful part of a reflective failure.
     *
     * @param t any throwable
     * @return "SimpleClassName: message" of the innermost cause
     */
    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
