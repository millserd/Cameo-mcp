// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.actions;

import java.util.*;
import com.nomagic.magicdraw.actions.ConfiguratorWithPriority;

public interface AMConfigurator extends ConfiguratorWithPriority {
    void configure(ActionsManager manager);
    default int getPriority() { throw new UnsupportedOperationException("stub"); }
}
