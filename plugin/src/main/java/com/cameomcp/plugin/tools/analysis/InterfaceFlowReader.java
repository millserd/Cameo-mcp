package com.cameomcp.plugin.tools.analysis;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads interface blocks and their owned flow properties (name, owner, direction) in one model read.
 * There is no public tool for this, so the analysis family reads it directly.
 */
final class InterfaceFlowReader {

    /** Static methods only. */
    private InterfaceFlowReader() {
    }

    /**
     * Validates the ID list, then reads all interface blocks and flow properties in a single model read
     * ({@link Edt#read}, on the calling thread) and converts the tree result into plain maps for the
     * analysis code.
     *
     * @param interfaceIds interface block element IDs
     * @return {interfaceBlocks: [...], flowProperties: [...]} as plain Java maps and lists; each flow
     *     property carries ownerId, ownerName and, when set, direction
     * @throws ToolException INVALID_PARAMS for an empty list or a non-Class ID, NOT_FOUND for an unknown ID
     * @throws Exception NO_PROJECT or MODEL_CHANGED from the model read
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(List<String> interfaceIds) throws Exception {
        if (interfaceIds == null || interfaceIds.isEmpty()) {
            throw ToolException.invalid("interface_block_ids must be a non-empty array of interface block IDs. "
                    + "Use cameo_query_elements(stereotype=\"InterfaceBlock\") to find them.");
        }
        JsonObject response = Edt.read(project -> readIn(project, interfaceIds));
        return (Map<String, Object>) Gs.fromTree(response);
    }

    /**
     * Reads the blocks inside the model read. Each ID must resolve to a Class (InterfaceBlock or Block);
     * owned attributes without a FlowProperty stereotype are ordinary value or part properties and are
     * skipped.
     *
     * @param project the open project
     * @param interfaceIds interface block element IDs
     * @return compact interface blocks and flow properties
     * @throws ToolException NOT_FOUND for an unknown ID, INVALID_PARAMS for a non-Class ID
     */
    private static JsonObject readIn(Project project, List<String> interfaceIds) {
        JsonArray interfaceBlocks = new JsonArray();
        JsonArray flowProperties = new JsonArray();
        for (String interfaceId : interfaceIds) {
            Object found = project.getElementByID(interfaceId);
            if (!(found instanceof Element)) {
                throw new ToolException("NOT_FOUND", "Interface block not found: " + interfaceId
                        + ". Use cameo_query_elements(stereotype=\"InterfaceBlock\") to find valid IDs.");
            }
            // InterfaceBlock is a stereotyped UML Class, so the Class check accepts both InterfaceBlock and Block.
            if (!(found instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class)) {
                throw ToolException.invalid("Interface block is not a class: " + interfaceId
                        + ". Pass the IDs of InterfaceBlock (or Block) elements.");
            }
            com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class block =
                    (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class) found;
            interfaceBlocks.add(ElementJson.toJsonCompact(block));
            Collection<Property> attributes = block.getOwnedAttribute();
            if (attributes == null) {
                continue;
            }
            for (Property prop : attributes) {
                if (prop == null) {
                    continue;
                }
                Stereotype stereotype = ElementJson.getFlowPropertyStereotype(prop);
                if (stereotype == null) {
                    continue;
                }
                JsonObject flowProperty = ElementJson.toJsonCompact(prop);
                // Owner fields are added explicitly so callers can group flow properties by interface without a second lookup.
                flowProperty.addProperty("ownerId", block.getID());
                String ownerName = block.getName();
                flowProperty.addProperty("ownerName", ownerName == null ? "" : ownerName);
                // Direction (in/out/inout) is a stereotype tag value; it is omitted when unset rather than defaulted.
                String direction = ElementJson.readFlowPropertyDirection(prop, stereotype);
                if (direction != null) {
                    flowProperty.addProperty("direction", direction);
                }
                flowProperties.add(flowProperty);
            }
        }
        JsonObject response = new JsonObject();
        response.add("interfaceBlocks", interfaceBlocks);
        response.add("flowProperties", flowProperties);
        return response;
    }
}
