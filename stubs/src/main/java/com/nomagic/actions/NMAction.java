// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.actions;

import java.util.*;
import javax.swing.AbstractAction;
import java.awt.event.ActionEvent;
import javax.swing.KeyStroke;

public abstract class NMAction extends AbstractAction implements Comparable {
    public NMAction() { }
    public NMAction(String id, String name, KeyStroke stroke) { }
    public NMAction(String id, String name, KeyStroke stroke, String group) { }
    public NMAction(String id, String name, int mnemonic) { }
    public NMAction(String id, String name, int mnemonic, String group) { }
    public abstract void actionPerformed(ActionEvent e);
    public void updateState() { throw new UnsupportedOperationException("stub"); }
    public String getID() { throw new UnsupportedOperationException("stub"); }
    public String getName() { throw new UnsupportedOperationException("stub"); }
    public void setName(String name) { throw new UnsupportedOperationException("stub"); }
    public int compareTo(Object o) { throw new UnsupportedOperationException("stub"); }
}
