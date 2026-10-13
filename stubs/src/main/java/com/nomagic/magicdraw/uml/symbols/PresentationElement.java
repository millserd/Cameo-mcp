// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.uml.symbols;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.magicdraw.uml.MDElementImpl;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.magicdraw.properties.PropertyManager;
import java.awt.Rectangle;

public abstract class PresentationElement extends MDElementImpl {
    public Rectangle getBounds() { throw new UnsupportedOperationException("stub"); }
    public Element getElement() { throw new UnsupportedOperationException("stub"); }
    public List<PresentationElement> getPresentationElements() { throw new UnsupportedOperationException("stub"); }
    public final PropertyManager getPropertyManager() { throw new UnsupportedOperationException("stub"); }
    public final PresentationElement getParent() { throw new UnsupportedOperationException("stub"); }
    public final DiagramPresentationElement getDiagramPresentationElement() { throw new UnsupportedOperationException("stub"); }
    public boolean isSelected() { throw new UnsupportedOperationException("stub"); }
    public com.nomagic.magicdraw.properties.Property getProperty(String id) { throw new UnsupportedOperationException("stub"); }
}
