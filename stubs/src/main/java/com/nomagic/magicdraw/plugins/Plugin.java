// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.plugins;

import java.util.*;

public abstract class Plugin {
    public Plugin() { }
    public PluginDescriptor getDescriptor() { throw new UnsupportedOperationException("stub"); }
    public abstract void init();
    public abstract boolean close();
    public abstract boolean isSupported();
}
