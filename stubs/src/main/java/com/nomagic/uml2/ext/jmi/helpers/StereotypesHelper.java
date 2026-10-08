// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.jmi.helpers;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

public class StereotypesHelper extends TagsHelper {
    public static void addStereotype(Element element, Stereotype stereotype) { throw new UnsupportedOperationException("stub"); }
    public static void removeStereotype(Element element, Stereotype stereotype) { throw new UnsupportedOperationException("stub"); }
    public static void applyProfile(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package pkg, Profile profile) { throw new UnsupportedOperationException("stub"); }
    public static boolean canApplyProfile(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package pkg, Profile profile) { throw new UnsupportedOperationException("stub"); }
    public static Stereotype createStereotype(Element owner, String name, Collection<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class> metaClasses) { throw new UnsupportedOperationException("stub"); }
    public static Collection<Stereotype> getAllStereotypes(Project project) { throw new UnsupportedOperationException("stub"); }
    public static Collection<Profile> getAppliedProfiles(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package pkg) { throw new UnsupportedOperationException("stub"); }
    public static Stereotype getAppliedStereotypeByString(Element element, String stereotypeName) { throw new UnsupportedOperationException("stub"); }
    public static List<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class> getBaseClasses(Stereotype stereotype) { throw new UnsupportedOperationException("stub"); }
    public static com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class getMetaClassByName(Project project, String name) { throw new UnsupportedOperationException("stub"); }
    public static Profile getProfile(Project project, String profileName) { throw new UnsupportedOperationException("stub"); }
    public static com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package getProfileForStereotype(Stereotype stereotype) { throw new UnsupportedOperationException("stub"); }
    public static Stereotype getStereotype(Project project, String stereotypeName, Profile profile) { throw new UnsupportedOperationException("stub"); }
    public static List<Stereotype> getStereotypes(Element element) { throw new UnsupportedOperationException("stub"); }
    public static boolean hasStereotype(Element element) { throw new UnsupportedOperationException("stub"); }
    public static boolean hasStereotype(Element element, Stereotype stereotype) { throw new UnsupportedOperationException("stub"); }
    public static boolean isExtensionProperty(Property property) { throw new UnsupportedOperationException("stub"); }
    public static void setBaseClassesByName(Stereotype stereotype, Collection<String> metaClasses) { throw new UnsupportedOperationException("stub"); }
}
