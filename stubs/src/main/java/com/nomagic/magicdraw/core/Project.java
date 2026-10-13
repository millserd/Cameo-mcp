// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.core;

import java.util.*;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.magicdraw.ui.browser.Browser;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Diagram;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.project.ElementProject;
import com.nomagic.uml2.impl.ElementsFactory;
import com.nomagic.uml2.ext.magicdraw.auxiliaryconstructs.mdmodels.Model;

public class Project implements ElementProject, BaseElement {
    public String getName() { throw new UnsupportedOperationException("stub"); }
    public String getFileName() { throw new UnsupportedOperationException("stub"); }
    public com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package getPrimaryModel() { throw new UnsupportedOperationException("stub"); }
    public Model getModel() { throw new UnsupportedOperationException("stub"); }  // @Deprecated in 2024x
    public List<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package> getModels() { throw new UnsupportedOperationException("stub"); }
    public BaseElement getElementByID(String id) { throw new UnsupportedOperationException("stub"); }
    public ElementsFactory getElementsFactory() { throw new UnsupportedOperationException("stub"); }
    public Collection<DiagramPresentationElement> getDiagrams() { throw new UnsupportedOperationException("stub"); }
    public Collection<DiagramPresentationElement> getDiagrams(String type) { throw new UnsupportedOperationException("stub"); }
    public DiagramPresentationElement getDiagram(Diagram diagramElement) { throw new UnsupportedOperationException("stub"); }
    public DiagramPresentationElement getActiveDiagram() { throw new UnsupportedOperationException("stub"); }
    public Browser getBrowser() { throw new UnsupportedOperationException("stub"); }
    public boolean isDirty() { throw new UnsupportedOperationException("stub"); }
    public boolean isRemote() { throw new UnsupportedOperationException("stub"); }
    public Class getClassType() { throw new UnsupportedOperationException("stub"); }
    public String getHumanName() { throw new UnsupportedOperationException("stub"); }
    public String getHumanType() { throw new UnsupportedOperationException("stub"); }
    public boolean isEditable() { throw new UnsupportedOperationException("stub"); }
    public int compareTo(Object o) { throw new UnsupportedOperationException("stub"); }
}
