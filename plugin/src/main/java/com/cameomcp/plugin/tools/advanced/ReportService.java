package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

    private static final String TEMPLATE_HELPER = "com.nomagic.magicdraw.magicreport.helper.TemplateHelper";
    private static final String GENERATE_TASK = "com.nomagic.magicdraw.magicreport.GenerateTask";
    private static final String REPORT_COMMAND_LINE = "com.nomagic.magicdraw.magicreport.commandline.ReportCommandLine";
    private static final String TEMPLATE_BEAN = "com.nomagic.magicdraw.magicreport.ui.bean.TemplateBean";
    private static final String REPORT_PROPERTY_BEAN = "com.nomagic.magicdraw.magicreport.ui.bean.ReportPropertyBean";
    private static final String PACKAGE_SELECTION_BEAN = "com.nomagic.magicdraw.magicreport.ui.bean.PackageSelectionBean";
    private static final String TASK = "com.nomagic.task.Task";

    private static final Map<String, JsonObject> JOBS = Collections.synchronizedMap(
            new LinkedHashMap<String, JsonObject>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
                    return size() > 50;
                }
            });

    private ReportService() {
    }

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

    private static void copy(JsonObject from, String key, JsonObject to, String as) {
        if (Gs.has(from, key)) {
            to.add(as, from.get(key));
        }
    }

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

    private static JsonObject generateReport(Project project, JsonObject body, JsonObject parameters, File outputFile)
            throws Exception {
        boolean existed = outputFile.isFile();
        long beforeModified = existed ? outputFile.lastModified() : 0L;
        long beforeLength = existed ? outputFile.length() : 0L;

        List<Object> templates = listTemplates();
        Object selectedTemplate = selectTemplate(templates, body, parameters);
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

        Object taskException = Support.invokeZeroArg(task, "getException");
        boolean exists = outputFile.isFile();
        long length = exists ? outputFile.length() : 0L;
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

    private static void configureReport(Project project, Object report, JsonObject body, JsonObject parameters, File outputFile)
            throws Exception {
        Object selection = Support.invokeZeroArg(report, "getSelectedPackage");
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

        Object reportProperty = Support.invokeZeroArg(report, "getReportProperty");
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

    private static List<Object> listTemplates() throws Exception {
        Class<?> helper = Support.loadClass(TEMPLATE_HELPER);
        Object value = helper.getMethod("listTemplates").invoke(null);
        List<Object> templates = new ArrayList<>();
        if (value instanceof Collection) {
            templates.addAll((Collection<?>) value);
        }
        return templates;
    }

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

    private static Object selectReport(Object template, String reportName) {
        Object report = null;
        if (reportName != null && !reportName.trim().isEmpty()) {
            for (Object candidate : asCollection(Support.invokeZeroArg(template, "getReportList"))) {
                if (equalsIgnoreCase(reportName.trim().toLowerCase(Locale.ROOT), Support.stringGetter(candidate, "getName"))) {
                    report = candidate;
                    break;
                }
            }
        }
        if (report == null) {
            report = Support.invokeZeroArg(template, "getDefaultReport");
        }
        if (report == null) {
            report = Support.invokeZeroArg(template, "getSelectedReport");
        }
        if (report == null) {
            Collection<?> reports = asCollection(Support.invokeZeroArg(template, "getReportList"));
            if (!reports.isEmpty()) {
                report = reports.iterator().next();
            }
        }
        if (report == null) {
            throw new ToolException("PRECONDITION_FAILED", "Selected report template has no report configuration.");
        }
        return cloneIfPossible(report);
    }

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
        Object reportList = Support.invokeZeroArg(template, "getReportList");
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

    private static Object cloneIfPossible(Object value) {
        Object clone = Support.invokeZeroArg(value, "clone");
        return clone != null ? clone : value;
    }

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

    private static boolean firstBoolean(JsonObject body, JsonObject parameters, String key, boolean defaultValue) {
        Boolean value = booleanValue(body, key);
        if (value != null) {
            return value;
        }
        value = booleanValue(parameters, key);
        return value != null ? value : defaultValue;
    }

    private static Boolean booleanValue(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonPrimitive()) {
            return null;
        }
        return Gs.bool(source, key, false);
    }

    private static Object toStringArray(List<String> values) {
        Object array = Array.newInstance(String.class, values.size());
        for (int i = 0; i < values.size(); i++) {
            Array.set(array, i, values.get(i));
        }
        return array;
    }

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

    private static Collection<?> asCollection(Object value) {
        return value instanceof Collection ? (Collection<?>) value : Collections.emptyList();
    }

    private static boolean equalsIgnoreCase(String normalizedNeedle, String candidate) {
        return candidate != null && normalizedNeedle.equals(candidate.trim().toLowerCase(Locale.ROOT));
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
