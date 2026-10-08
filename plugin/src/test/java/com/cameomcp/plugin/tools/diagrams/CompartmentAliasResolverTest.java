// family: diagrams
package com.cameomcp.plugin.tools.diagrams;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompartmentAliasResolverTest {

    @Test
    void showPrefixedAliasesResolveToCompartmentCandidates() {
        assertEquals(Collections.singletonList("Suppress Attributes"),
                CompartmentAliasResolver.candidatePropertyNames("showAttributes"));
        assertEquals(Arrays.asList("Suppress Parts", "Show Parts"),
                CompartmentAliasResolver.candidatePropertyNames("showParts"));
        assertEquals(Arrays.asList("Suppress Flow Properties", "Show Flow Properties"),
                CompartmentAliasResolver.candidatePropertyNames("showFlowProperties"));
        assertEquals(Collections.singletonList("Show Tagged Values"),
                CompartmentAliasResolver.candidatePropertyNames("showTaggedValues"));
    }

    @Test
    void suppressPrefixedAliasesReuseTheSameCompartmentCandidates() {
        assertEquals(Arrays.asList("Suppress Parts", "Show Parts"),
                CompartmentAliasResolver.candidatePropertyNames("suppressParts"));
        assertEquals(Arrays.asList("Suppress Proxy Ports", "Show Proxy Ports"),
                CompartmentAliasResolver.candidatePropertyNames("suppressProxyPorts"));
    }

    @Test
    void snakeCaseAndBareKeysWork() {
        assertEquals(Collections.singletonList("Show Tagged Values"),
                CompartmentAliasResolver.candidatePropertyNames("show_tagged_values"));
        assertEquals(Arrays.asList("Suppress Ports", "Show Ports"),
                CompartmentAliasResolver.candidatePropertyNames("ports"));
    }

    @Test
    void unknownCompartmentAliasReturnsNoCandidates() {
        assertTrue(CompartmentAliasResolver.candidatePropertyNames("showNotARealCompartment").isEmpty());
        assertTrue(CompartmentAliasResolver.candidatePropertyNames(null).isEmpty());
    }
}
