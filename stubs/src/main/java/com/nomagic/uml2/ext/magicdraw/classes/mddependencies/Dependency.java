// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mddependencies;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.DirectedRelationship;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.PackageableElement;

public interface Dependency extends PackageableElement, DirectedRelationship {
    Collection<NamedElement> getClient();
    Collection<NamedElement> getSupplier();
}
