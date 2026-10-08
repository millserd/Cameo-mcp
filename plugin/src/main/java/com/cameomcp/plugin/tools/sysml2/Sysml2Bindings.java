package com.cameomcp.plugin.tools.sysml2;

import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonObject;

/** Binds the sysml2 family (see {@code com.cameomcp.catalog.Sysml2Tools}). */
public final class Sysml2Bindings {

    private Sysml2Bindings() {
    }

    @FunctionalInterface
    private interface Impl {
        JsonObject run(Args a) throws Exception;
    }

    private static void bind(ToolRegistry r, String name, Impl impl) {
        r.bind(name, (a, c) -> Gs.fromGson(impl.run(a)));
    }

    public static void bind(ToolRegistry r) {
        bind(r, "cameo_sysml2_status", a -> Sysml2Service.status());
        bind(r, "cameo_sysml2_list_roots", a -> Sysml2Service.listRoots(a.bool("include_libraries", false),
                a.intIn("members_limit", 100, 0, 500)));
        bind(r, "cameo_sysml2_list_children", a -> Sysml2Service.listChildren(a.reqStr("element_id"),
                a.intIn("limit", 200, 1, 1000), a.intIn("offset", 0, 0, Integer.MAX_VALUE)));
        bind(r, "cameo_sysml2_get_element", a -> Sysml2Service.getElement(a.reqStr("element_id"),
                a.bool("include_text", false), a.intIn("members_limit", 50, 0, 500)));
        bind(r, "cameo_sysml2_query", a -> Sysml2Service.query(a.str("name"), a.str("type"), a.str("scope_id"),
                a.bool("include_libraries", false), a.intIn("limit", 100, 1, 1000),
                a.intIn("offset", 0, 0, Integer.MAX_VALUE)));
        bind(r, "cameo_sysml2_get_text", a -> Sysml2Service.getText(a.str("element_id"),
                a.intIn("max_chars", 200000, 1000, 2000000)));
        bind(r, "cameo_sysml2_validate_text", a -> Sysml2Service.validateText(a.reqStr("text")));
        bind(r, "cameo_sysml2_create_from_text", a -> Sysml2Service.createFromText(a.reqStr("text"),
                a.bool("allow_warnings", true)));
        bind(r, "cameo_sysml2_rename_element", a -> Sysml2Service.rename(a.reqStr("element_id"), a.reqStr("name")));
        bind(r, "cameo_sysml2_delete_element", a -> Sysml2Service.delete(a.reqStr("element_id")));
    }
}
