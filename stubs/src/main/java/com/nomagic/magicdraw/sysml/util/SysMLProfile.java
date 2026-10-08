// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.sysml.util;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.profiles.ProfileImplementation;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

public class SysMLProfile extends ProfileImplementation {
    public static SysMLProfile getInstance(BaseElement element) { throw new UnsupportedOperationException("stub"); }  // UNVERIFIED: method summary truncated in javadoc fetch
    public static SysMLProfile getInstanceByProject(Project project) { throw new UnsupportedOperationException("stub"); }  // UNVERIFIED: method summary truncated in javadoc fetch
    public SysMLProfile.BlockStereotype block() { throw new UnsupportedOperationException("stub"); }

    public static class BlockStereotype extends ProfileImplementation.StereotypeWrapper {
        public Stereotype getStereotype() { throw new UnsupportedOperationException("stub"); }
        public boolean is(Element element) { throw new UnsupportedOperationException("stub"); }
        public static boolean isInstance(Element element) { throw new UnsupportedOperationException("stub"); }
    }
}
