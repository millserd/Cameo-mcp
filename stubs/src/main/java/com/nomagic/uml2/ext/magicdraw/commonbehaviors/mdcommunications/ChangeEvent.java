// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications;

import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ValueSpecification;

public interface ChangeEvent extends Event {
    ValueSpecification getChangeExpression();
    void setChangeExpression(ValueSpecification value);
}
