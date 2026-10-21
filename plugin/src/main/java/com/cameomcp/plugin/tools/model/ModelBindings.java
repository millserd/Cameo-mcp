package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.ToolRegistry;
import com.cameomcp.mcp.ToolResult;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonObject;

/** Binds the model family (see {@code com.cameomcp.catalog.ModelTools}). */
public final class ModelBindings {

    /** Not instantiable; all members are static. */
    private ModelBindings() {
    }

    /** A model tool written against JSON trees: takes the core Args and returns a JSON tree object. */
    @FunctionalInterface
    private interface Impl {
        /**
         * Runs the tool.
         *
         * @param a the tool arguments
         * @return the tool result as a JSON object
         * @throws Exception any failure, which the registry turns into a tool error
         */
        JsonObject run(com.cameomcp.mcp.Args a) throws Exception;
    }

    /**
     * Registers an {@link Impl} under a tool name, converting its tree result back to plain JSON values.
     *
     * @param r the registry to add to
     * @param name the tool name from the catalog
     * @param impl the implementation
     */
    private static void bind(ToolRegistry r, String name, Impl impl) {
        r.bind(name, (a, c) -> Gs.fromTree(impl.run(a)));
    }

    /**
     * Binds every model-family tool to its service method.
     *
     * @param r the registry to add to; every name must already be declared in the catalog
     */
    public static void bind(ToolRegistry r) {
        bind(r, "cameo_get_project", a -> ProjectUiService.getProject());
        bind(r, "cameo_save_project", a -> ProjectUiService.saveProject());
        bind(r, "cameo_get_ui_state", a -> ProjectUiService.uiState(a.bool("summary_only", false)));
        bind(r, "cameo_get_active_diagram", a -> ProjectUiService.activeDiagram());
        bind(r, "cameo_get_ui_selection", a -> ProjectUiService.selection());

        bind(r, "cameo_query_elements", QueryService::queryElements);
        bind(r, "cameo_get_element", QueryService::getElement);
        bind(r, "cameo_get_containment_tree", QueryService::containmentTree);
        bind(r, "cameo_list_containment_children", QueryService::listChildren);
        bind(r, "cameo_get_relationships", QueryService::relationships);

        bind(r, "cameo_create_element", MutationService::createElement);
        bind(r, "cameo_create_elements", MutationService::createElements);
        bind(r, "cameo_modify_element", MutationService::modifyElement);
        bind(r, "cameo_move_element", MutationService::moveElement);
        bind(r, "cameo_delete_element", MutationService::deleteElement);
        bind(r, "cameo_apply_stereotype", MutationService::applyStereotype);
        bind(r, "cameo_remove_stereotype", MutationService::removeStereotype);
        bind(r, "cameo_set_tagged_values", MutationService::setTaggedValues);
        bind(r, "cameo_set_stereotype_metaclasses", MutationService::setStereotypeMetaclasses);
        bind(r, "cameo_apply_profile", MutationService::applyProfile);
        bind(r, "cameo_create_relationship", MutationService::createRelationship);
        bind(r, "cameo_set_usecase_subject", MutationService::setUseCaseSubject);

        bind(r, "cameo_get_specification", SpecificationService::get);
        bind(r, "cameo_set_specification", SpecificationService::set);

        bind(r, "cameo_get_transition_triggers", StateMachineService::getTriggers);
        bind(r, "cameo_set_transition_trigger", StateMachineService::setTrigger);
        bind(r, "cameo_get_state_behaviors", StateMachineService::getBehaviors);
        bind(r, "cameo_set_state_behaviors", StateMachineService::setBehaviors);

        // A macro that fails still returns its output and log; flag the result as an error so the agent notices.
        r.bind("cameo_execute_macro", (a, c) -> {
            JsonObject out = MacroService.execute(a);
            ToolResult result = ToolResult.of(Gs.fromTree(out));
            return out.has("success") && !out.get("success").getAsBoolean() ? result.markError() : result;
        });
        bind(r, "cameo_list_macro_engines", a -> MacroService.listEngines());
    }
}
