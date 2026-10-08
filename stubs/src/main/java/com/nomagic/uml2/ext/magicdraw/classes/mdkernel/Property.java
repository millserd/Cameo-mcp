// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement;

public interface Property extends StructuralFeature, ConnectableElement {
    AggregationKind getAggregation();
    void setAggregation(AggregationKind value);
    Association getAssociation();
    void setAssociation(Association value);
    boolean isComposite();
    ValueSpecification getDefaultValue();
    void setDefaultValue(ValueSpecification value);
}
