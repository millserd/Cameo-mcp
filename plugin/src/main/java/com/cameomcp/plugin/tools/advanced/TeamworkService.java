package com.cameomcp.plugin.tools.advanced;

import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.project.ProjectDescriptor;
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory;

import java.lang.reflect.Method;

/** Teamwork Cloud / Magic Collaboration Studio read-only probes (reference TeamworkHandler). */
final class TeamworkService {

    private static final String ESI_UTILS = "com.nomagic.magicdraw.esi.EsiUtils";

    private TeamworkService() {
    }

    static JsonObject capabilities() {
        JsonObject response = Support.baseCapabilities("teamwork", "native-readonly");
        JsonObject probe = Support.classProbe(ESI_UTILS);
        boolean all = probe.get("allFound").getAsBoolean();
        response.addProperty("available", all);
        response.addProperty("status", all ? "available" : "missing-class");
        response.add("classProbe", probe);
        response.add("pluginDirectoriesFound", Support.pluginDirectories("teamwork", "twcloud", "esi"));
        response.addProperty("writeSupported", false);
        response.addProperty("writeReason", "Commit/update remain gated until disposable Teamwork project validation exists.");
        response.addProperty("descriptorReadSupported", true);
        response.addProperty("branchHistoryLockReadSupported", false);
        JsonArray readRoutes = new JsonArray();
        readRoutes.add("cameo_get_teamwork_project");
        readRoutes.add("cameo_list_teamwork_descriptors");
        readRoutes.add("cameo_list_teamwork_branches");
        readRoutes.add("cameo_get_teamwork_history");
        readRoutes.add("cameo_get_teamwork_locks");
        response.add("readRoutes", readRoutes);
        return response;
    }

    static JsonObject project() throws Exception {
        return Edt.read(project -> {
            JsonObject response = capabilities();
            response.addProperty("projectMode", project.isRemote() ? "teamwork" : "local");
            try {
                ProjectDescriptor descriptor = ProjectDescriptorsFactory.getDescriptorForProject(project);
                if (descriptor != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("className", descriptor.getClass().getName());
                    d.addProperty("string", descriptor.toString());
                    d.add("properties", Support.serializeValue(descriptor, false, false));
                    for (String getter : new String[]{"getURI", "getRepresentationString"}) {
                        Object v = Support.invokeZeroArg(descriptor, getter);
                        if (v != null) {
                            d.addProperty(getter.substring(3, 4).toLowerCase() + getter.substring(4), String.valueOf(v));
                        }
                    }
                    response.add("descriptor", d);
                }
            } catch (Throwable t) {
                response.addProperty("descriptorError", t.toString());
            }
            response.add("esiDiagnostics", esiDiagnostics());
            return response;
        });
    }

    static JsonObject descriptors() throws Exception {
        return Edt.read(project -> {
            JsonObject response = capabilities();
            response.addProperty("authenticatedDiscoveryAttempted", true);
            try {
                Class<?> cls = Support.loadClass(ESI_UTILS);
                Method method = cls.getMethod("getRemoteProjectDescriptors");
                Object value = method.invoke(null);
                response.add("descriptors", Support.serializeValue(value, true, false));
                response.addProperty("status", "available");
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                response.addProperty("status", "auth-or-server-unavailable");
                response.addProperty("reason", c.getClass().getName() + ": " + Support.safe(c.getMessage()));
                response.add("descriptors", new JsonArray());
            }
            return response;
        });
    }

    static JsonObject placeholder(String target) throws Exception {
        return Edt.read(project -> {
            JsonObject response = capabilities();
            boolean remote = project.isRemote();
            response.addProperty("target", target);
            response.addProperty("projectMode", remote ? "teamwork" : "local");
            response.addProperty("available", remote);
            response.addProperty("typedReadbackSupported", false);
            response.addProperty("status", remote ? "probe-required" : "local-project");
            response.addProperty("message", "Native " + target
                    + " serialization requires live Teamwork server evidence before typed output is claimed."
                    + (remote ? "" : " The open project is local, so there is no server " + target + "."));
            return response;
        });
    }

    static JsonObject preview(JsonObject p, String operation) {
        JsonObject response = Support.previewAccepted("teamwork", Support.request(p),
                "Teamwork " + operation + " preview recorded. No server write was performed.");
        response.addProperty("operation", operation);
        response.addProperty("approvalTokenIssued", false);
        response.addProperty("writeEnabled", false);
        response.addProperty("requiredGate", "Disposable Teamwork project validation plus validationGate=pass");
        if ("commit".equals(operation) && Gs.str(p, "message") == null) {
            response.addProperty("warning", "No commit message given; Teamwork commits should carry a message.");
        }
        return response;
    }

    private static JsonObject esiDiagnostics() {
        JsonObject diagnostics = new JsonObject();
        try {
            Class<?> cls = Support.loadClass(ESI_UTILS);
            for (String methodName : new String[]{"getLoggedUserName", "getServerInfo"}) {
                try {
                    Method method = cls.getMethod(methodName);
                    diagnostics.add(methodName, Support.serializeValue(method.invoke(null), true, false));
                } catch (Throwable e) {
                    diagnostics.addProperty(methodName + "Error", e.getClass().getName() + ": " + Support.safe(e.getMessage()));
                }
            }
        } catch (Throwable e) {
            diagnostics.addProperty("error", e.getClass().getName() + ": " + Support.safe(e.getMessage()));
        }
        return diagnostics;
    }
}
