package com.cameomcp.plugin.tools.analysis;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.plugin.cameo.ElementJson;
import com.cameomcp.plugin.cameo.Gs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    private InterfaceFlowReader() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(List<String> interfaceIds) throws Exception {
        if (interfaceIds == null || interfaceIds.isEmpty()) {
            throw ToolException.invalid("interface_block_ids must be a non-empty array of interface block IDs. "
                    + "Use cameo_query_elements(stereotype=\"InterfaceBlock\") to find them.");
        }
        JsonObject response = Edt.read(project -> readIn(project, interfaceIds));
        return (Map<String, Object>) Gs.fromGson(response);
    }

    private static JsonObject readIn(Project project, List<String> interfaceIds) {
        JsonArray interfaceBlocks = new JsonArray();
        JsonArray flowProperties = new JsonArray();
        for (String interfaceId : interfaceIds) {
            Object found = project.getElementByID(interfaceId);
            if (!(found instanceof Element)) {
                throw new ToolException("NOT_FOUND", "Interface block not found: " + interfaceId
                        + ". Use cameo_query_elements(stereotype=\"InterfaceBlock\") to find valid IDs.");
            }
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
                flowProperty.addProperty("ownerId", block.getID());
                String ownerName = block.getName();
                flowProperty.addProperty("ownerName", ownerName == null ? "" : ownerName);
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
