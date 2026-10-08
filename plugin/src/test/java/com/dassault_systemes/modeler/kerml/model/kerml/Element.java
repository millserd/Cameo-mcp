// family: sysml2
package com.dassault_systemes.modeler.kerml.model.kerml;

import java.util.List;

/**
 * Test double for the KerML Element interface of Cameo 2026x's SysML v2 plugin: the methods the
 * sysml2 tools call reflectively, with the real interface's name so detection works the same way.
 */
public interface Element {

    String getID();

    String getElementId();

    String getName();

    String getDeclaredName();

    String getShortName();

    String getQualifiedName();

    List<Element> getOwnedElement();

    Element getOwner();

    boolean isLibraryElement();

    MetaClass eClass();

    /** Stands in for EMF's EClass. */
    interface MetaClass {
        String getName();
    }
}
