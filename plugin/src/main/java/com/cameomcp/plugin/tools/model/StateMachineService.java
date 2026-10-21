package com.cameomcp.plugin.tools.model;

import com.cameomcp.mcp.Args;
import com.cameomcp.plugin.cameo.Edt;
import com.cameomcp.json.tree.JsonArray;
import com.cameomcp.json.tree.JsonNull;
import com.cameomcp.json.tree.JsonObject;
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

    /** Not instantiable; all members are static. */
    private StateMachineService() {
    }

    // ------------------------------------------------------------------ triggers

    /**
     * Implements cameo_get_transition_triggers: the transition's triggers with their events.
     *
     * @param a the tool arguments: {@code transition_id}
     * @return the payload from {@link #triggersPayload}
     * @throws com.cameomcp.mcp.ToolException INVALID_PARAMS when the id is not a transition, NOT_FOUND when it does not
     *     resolve
     * @throws Exception any failure while handing the work to the EDT
     */
    static JsonObject getTriggers(Args a) throws Exception {
        String id = a.reqStr("transition_id");
        return Edt.read(project -> triggersPayload(
                ModelSupport.typed(project, id, "transition_id", Transition.class, "a Transition")));
    }

    /**
     * Implements cameo_set_transition_trigger: adds a change trigger (expression) or signal trigger
     * (signal_id). With replace=true (the default) the existing triggers are removed first. The new event is
     * owned by the nearest package above the state machine, see {@link #eventOwner}.
     *
     * @param a the tool arguments: {@code transition_id}, {@code trigger_kind}, {@code expression} or
     * {@code signal_id}, {@code name} and {@code replace}
     *
     * @return the transition's triggers after the change, see {@link #triggersPayload}
     * @throws com.cameomcp.mcp.ToolException INVALID_PARAMS for a bad kind, missing expression or signal, or wrong
     *     element
     *     kinds;
     * NOT_FOUND or PRECONDITION_FAILED for missing or read-only elements
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * The transition id and its triggers.
     *
     * @param transition the transition
     * @return {@code transitionId}, {@code triggerCount} and {@code triggers}
     */
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

    /**
     * One trigger as JSON: its event type, and the signal or change expression behind it.
     *
     * @param t the trigger
     * @return the trigger JSON; event fields are null when the trigger has no event
     */
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

    /**
     * The text of a value specification: literal string, first opaque body, or its "value" feature.
     *
     * @param v the value specification, may be null
     * @return the text, or null when there is none
     */
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

    /**
     * Implements cameo_get_state_behaviors: the state's entry, do and exit behaviors.
     *
     * @param a the tool arguments: {@code state_id}
     * @return the payload from {@link #behaviorsPayload}
     * @throws com.cameomcp.mcp.ToolException INVALID_PARAMS when the id is not a state, NOT_FOUND when it does not
     *     resolve
     * @throws Exception any failure while handing the work to the EDT
     */
    static JsonObject getBehaviors(Args a) throws Exception {
        String id = a.reqStr("state_id");
        return Edt.read(project -> behaviorsPayload(ModelSupport.typed(project, id, "state_id", State.class, "a State")));
    }

    /**
     * Implements cameo_set_state_behaviors: sets entry, do and exit as OpaqueBehaviors with body text and
     * language. Omitted slots are kept (or cleared with clear_unspecified); blank text clears a slot.
     *
     * @param a the tool arguments: {@code state_id}, {@code entry}, {@code do_activity}, {@code
     *     exit_behavior},
     * {@code language} (default Opaque) and {@code clear_unspecified}
     *
     * @return the state's behaviors after the change, see {@link #behaviorsPayload}
     * @throws com.cameomcp.mcp.ToolException INVALID_PARAMS for a blank language or a non-state id, NOT_FOUND or
     *     PRECONDITION_FAILED
     * for a missing or read-only state
     *
     * @throws Exception any Cameo failure, after the session has been cancelled
     */
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

    /**
     * The behavior currently in a slot.
     *
     * @param s the state
     * @param slot "entry", "doActivity" or anything else for exit
     * @return the behavior, or null when the slot is empty
     */
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

    /**
     * Puts a behavior, or null, into a slot.
     *
     * @param s the state
     * @param slot "entry", "doActivity" or anything else for exit
     * @param b the behavior, or null to clear the slot
     */
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

    /**
     * Applies one slot's rule from {@link ModelText#slotAction}. An existing OpaqueBehavior is rewritten in
     * place so references to it stay valid; otherwise a new one named "State slot" is created under the
     * state.
     *
     * @param project the open project
     * @param state the state
     * @param slot "entry", "doActivity" or "exit"
     * @param body the new body, or null when omitted
     * @param language the body language
     * @param clearUnspecified true to clear the slot when body is null
     * @throws Exception any Cameo failure while adding a new behavior
     */
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

    /**
     * The state id plus each slot's behavior (null when empty).
     *
     * @param state the state
     * @return {@code stateId}, {@code stateName}, and {@code entry}, {@code doActivity} and {@code exit}
     *     (JSON null when
     * empty)
     */
    static JsonObject behaviorsPayload(State state) {
        JsonObject out = new JsonObject();
        out.addProperty("stateId", state.getID());
        out.addProperty("stateName", state.getName() != null ? state.getName() : "");
        out.add("entry", behavior(state.getEntry()));
        out.add("doActivity", behavior(state.getDoActivity()));
        out.add("exit", behavior(state.getExit()));
        return out;
    }

    /**
     * One behavior as {id, name, body, language, humanType}.
     *
     * @param b the behavior, or null
     * @return the JSON, or JSON null when b is null
     */
    private static com.cameomcp.json.tree.JsonElement behavior(Behavior b) {
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
