// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.auxiliaryconstructs.mdinformationflows;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.DirectedRelationship;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.PackageableElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Relationship;

public interface InformationFlow extends PackageableElement, DirectedRelationship {
    Collection<Classifier> getConveyed();
    Collection<NamedElement> getInformationSource();
    Collection<NamedElement> getInformationTarget();
    Collection<Connector> getRealizingConnector();
    Collection<Relationship> getRealization();
}
