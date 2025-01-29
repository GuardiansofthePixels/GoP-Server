package de.julianweinelt.gop.modules;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class Module {

    private String name;
    private String description;
    private String[] authors;
    private String version;

    public abstract void onLoad();
    public abstract void onEnable();
    public abstract void onDisable();
}