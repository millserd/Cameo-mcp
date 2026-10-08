package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.plugin.cameo.Edt;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.LiteralString;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ValueSpecification;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.ChangeEvent;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Event;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.SignalEvent;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Trigger;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.State;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Transition;
import com.nomagic.uml2.impl.ElementsFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Transition triggers and state entry/do/exit behaviors. The reference implemented these as
 * generated Groovy macros; here they use the OpenAPI directly (no script engine needed) and keep
 * the reference's parameters, rules and result shapes.
 */
final class StateMachineService {

    private StateMachineService() {
    }

    // ------------------------------------------------------------------ triggers

    static JsonObject getTriggers(Args a) throws Exception {
        String id = a.reqStr("transition_id");
        return Edt.read(project -> triggersPayload(
                ModelSupport.typed(project, id, "transition_id", Transition.class, "a Transition")));
    }

    static JsonObject setTrigger(Args a) throws Exception {
        String id = a.reqStr("transition_id");
        String expression = a.str("expression");
        String signalId = a.optTrim("signal_id");
        String kind = ModelText.triggerKind(a.str("trigger_kind"), expression, signalId);
        String name = a.str("name");
        boolean replace = a.bool("replace", true);
        return Edt.write("Set transition trigger", project -> {
            Transition transition = ModelSupport.typed(project, id, "transition_id", Transition.class, "a Transition");
            ModelSupport.requireEditable(transition, "Transition");
            ElementsFactory ef = project.getElementsFactory();
            ModelElementsManager mem = ModelElementsManager.getInstance();
            if (replace) {
                for (Trigger t : new ArrayList<>(transition.getTrigger())) {
                    try {
                        mem.removeElement(t);
                    } catch (Exception e) {
                        transition.getTrigger().remove(t);
                    }
                }
            }
            Element eventOwner = eventOwner(project, transition);
            Event event;
            if ("change".equals(kind)) {
                ChangeEvent ce = ef.createChangeEventInstance();
                LiteralString literal = ef.createLiteralStringInstance();
                literal.setValue(expression);
                ce.setChangeExpression(literal);
                event = ce;
            } else {
                Signal signal = ModelSupport.typed(project, signalId, "signal_id", Signal.class, "a Signal");
                SignalEvent se = ef.createSignalEventInstance();
                se.setSignal(signal);
                event = se;
            }
            mem.addElement(event, eventOwner);
            Trigger trigger = ef.createTriggerInstance();
            if (name != null && !name.trim().isEmpty()) {
                trigger.setName(name);
            }
            trigger.setEvent(event);
            mem.addElement(trigger, transition);
            return triggersPayload(transition);
        });
    }

    /**
     * Events are packageable elements: the reference put them into the enclosing StateMachine; a
     * package is the UML-valid owner, so use the nearest package above the state machine (falling
     * back to the state machine itself, then the primary model).
     */
    private static Element eventOwner(Project project, Element transition) {
        Element sm = null;
        Element cur = transition;
        while (cur != null) {
            if (cur instanceof StateMachine) {
                sm = cur;
                break;
            }
            cur = cur.getOwner();
        }
        cur = sm != null ? sm.getOwner() : transition.getOwner();
        while (cur != null) {
            if (cur instanceof Package) {
                return cur;
            }
            cur = cur.getOwner();
        }
        return sm != null ? sm : project.getPrimaryModel();
    }

    static JsonObject triggersPayload(Transition transition) {
        JsonArray triggers = new JsonArray();
        Collection<Trigger> list = transition.getTrigger();
        if (list != null) {
            for (Trigger t : list) {
                triggers.add(trigger(t));
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("transitionId", transition.getID());
        out.addProperty("triggerCount", triggers.size());
        out.add("triggers", triggers);
        return out;
    }

    private static JsonObject trigger(Trigger t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.getID());
        o.addProperty("name", t.getName() != null ? t.getName() : "");
        Event event = null;
        try {
            event = t.getEvent();
        } catch (Exception ignored) {
            // no event
        }
        o.addProperty("eventId", event != null ? event.getID() : null);
        o.addProperty("eventType", event != null ? ModelSupport.className(event) != null
                ? ModelSupport.className(event) : event.getClass().getSimpleName() : "");
        Signal signal = event instanceof SignalEvent ? ((SignalEvent) event).getSignal() : null;
        o.addProperty("signalId", signal != null ? signal.getID() : null);
        o.addProperty("signalName", signal != null ? signal.getName() : null);
        String expr = null;
        if (event instanceof ChangeEvent) {
            expr = valueText(((ChangeEvent) event).getChangeExpression());
        }
        o.addProperty("changeExpression", expr);
        return o;
    }

    static String valueText(ValueSpecification v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LiteralString) {
            return ((LiteralString) v).getValue();
        }
        if (v instanceof OpaqueExpression) {
            List<String> body = ((OpaqueExpression) v).getBody();
            return body != null && !body.isEmpty() ? body.get(0) : null;
        }
        try {
            Object value = v.refGetValue("value");
            return value != null ? String.valueOf(value) : String.valueOf(v);
        } catch (Throwable e) {
            return String.valueOf(v);
        }
    }

    // ------------------------------------------------------------------ state behaviors

    static JsonObject getBehaviors(Args a) throws Exception {
        String id = a.reqStr("state_id");
        return Edt.read(project -> behaviorsPayload(ModelSupport.typed(project, id, "state_id", State.class, "a State")));
    }

    static JsonObject setBehaviors(Args a) throws Exception {
        String id = a.reqStr("state_id");
        String entry = a.str("entry");
        String doActivity = a.str("do_activity");
        String exit = a.str("exit_behavior");
        String language = ModelText.requireLanguage(a.str("language", "Opaque"));
        boolean clearUnspecified = a.bool("clear_unspecified", false);
        return Edt.write("Set state behaviors", project -> {
            State state = ModelSupport.typed(project, id, "state_id", State.class, "a State");
            ModelSupport.requireEditable(state, "State");
            apply(project, state, "entry", entry, language, clearUnspecified);
            apply(project, state, "doActivity", doActivity, language, clearUnspecified);
            apply(project, state, "exit", exit, language, clearUnspecified);
            return behaviorsPayload(state);
        });
    }

    private static Behavior current(State s, String slot) {
        switch (slot) {
            case "entry":
                return s.getEntry();
            case "doActivity":
                return s.getDoActivity();
            default:
                return s.getExit();
        }
    }

    private static void assign(State s, String slot, Behavior b) {
        switch (slot) {
            case "entry":
                s.setEntry(b);
                break;
            case "doActivity":
                s.setDoActivity(b);
                break;
            default:
                s.setExit(b);
                break;
        }
    }

    private static void apply(Project project, State state, String slot, String body, String language,
                              boolean clearUnspecified) throws Exception {
        switch (ModelText.slotAction(body, clearUnspecified)) {
            case KEEP:
                return;
            case CLEAR:
                assign(state, slot, null);
                return;
            default:
                break;
        }
        Behavior existing = current(state, slot);
        OpaqueBehavior behavior;
        if (existing instanceof OpaqueBehavior) {
            behavior = (OpaqueBehavior) existing;
        } else {
            behavior = project.getElementsFactory().createOpaqueBehaviorInstance();
            String stateName = state.getName() == null || state.getName().isEmpty() ? "State" : state.getName();
            behavior.setName(stateName + " " + slot);
            ModelElementsManager.getInstance().addElement(behavior, state);
        }
        behavior.getBody().clear();
        behavior.getBody().add(body);
        behavior.getLanguage().clear();
        behavior.getLanguage().add(language);
        assign(state, slot, behavior);
    }

    static JsonObject behaviorsPayload(State state) {
        JsonObject out = new JsonObject();
        out.addProperty("stateId", state.getID());
        out.addProperty("stateName", state.getName() != null ? state.getName() : "");
        out.add("entry", behavior(state.getEntry()));
        out.add("doActivity", behavior(state.getDoActivity()));
        out.add("exit", behavior(state.getExit()));
        return out;
    }

    private static com.google.gson.JsonElement behavior(Behavior b) {
        if (b == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", b.getID());
        o.addProperty("name", b.getName() != null ? b.getName() : "");
        String body = null;
        String language = null;
        if (b instanceof OpaqueBehavior) {
            List<String> bodies = ((OpaqueBehavior) b).getBody();
            if (bodies != null && !bodies.isEmpty()) {
                body = bodies.get(0);
            }
            List<String> langs = ((OpaqueBehavior) b).getLanguage();
            if (langs != null && !langs.isEmpty()) {
                language = langs.get(0);
            }
        }
        o.addProperty("body", body);
        o.addProperty("language", language);
        String human = b.getHumanType();
        o.addProperty("humanType", human != null ? human : b.getClass().getSimpleName());
        return o;
    }
}
