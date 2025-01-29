package de.julianweinelt.gop.modules;

import de.julianweinelt.gop.modules.event.EventManager;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Registry {
    private final List<Module> modules = new ArrayList<>();

    private final ModuleLoader moduleLoader;
    private final EventManager eventManager;

    public Registry() {
        moduleLoader = new ModuleLoader();
        eventManager = new EventManager();
    }
}