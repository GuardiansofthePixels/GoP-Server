package de.julianweinelt.gop.modules.event;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class EventManager {
    private final Map<Class<?>, List<ListenerMethod>> listeners = new HashMap<>();
    
    public void registerListener(Object listener) {
        for (Method method : listener.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(EventHandler.class)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && Event.class.isAssignableFrom(params[0])) {
                    listeners.computeIfAbsent(params[0], k -> new ArrayList<>())
                             .add(new ListenerMethod(listener, method));
                }
            }
        }
    }
    
    public void callEvent(Event event) {
        List<ListenerMethod> methods = listeners.get(event.getClass());
        if (methods != null) {
            for (ListenerMethod lm : methods) {
                try {
                    lm.method.setAccessible(true);
                    lm.method.invoke(lm.listener, event);
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        }
    }
    
    private record ListenerMethod(Object listener, Method method) {}
}