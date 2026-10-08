// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.base.ModelObject;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

public interface Element extends ModelObject {
    List<Stereotype> getAppliedStereotype();
    Collection<Comment> getOwnedComment();
    Collection<Element> getOwnedElement();
    Element getOwner();
    void setOwner(Element value);
    Collection<DirectedRelationship> get_directedRelationshipOfSource();
    Collection<DirectedRelationship> get_directedRelationshipOfTarget();
    Collection<Relationship> get_relationshipOfRelatedElement();
    Collection<Comment> get_commentOfAnnotatedElement();
}
