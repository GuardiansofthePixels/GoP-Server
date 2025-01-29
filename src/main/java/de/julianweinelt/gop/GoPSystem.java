package de.julianweinelt.gop;

import de.julianweinelt.gop.modules.ModuleLoader;
import de.julianweinelt.gop.modules.Registry;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;


@Getter
public class GoPSystem {
    private static final Logger log = LoggerFactory.getLogger(GoPSystem.class);
    public static final String copyrightYears = "2017-2025";

    private static volatile GoPSystem instance;

    private Registry registry;

    public GoPSystem() {
        instance = this;
    }

    public static void main(String[] args) {
        log.info("Welcome!");
        log.info("GoP is starting up...");
        instance.startup();
        instance.registry = new Registry();
    }

    private void startup() {
        System.out.println("""
                   _____       _____        _____           _                \s
                  / ____|     |  __ \\      / ____|         | |               \s
                 | |  __  ___ | |__) |____| (___  _   _ ___| |_ ___ _ __ ___ \s
                 | | |_ |/ _ \\|  ___/______\\___ \\| | | / __| __/ _ \\ '_ ` _ \\\s
                 | |__| | (_) | |          ____) | |_| \\__ \\ ||  __/ | | | | |
                  \\_____|\\___/|_|         |_____/ \\__, |___/\\__\\___|_| |_| |_|
                                                   __/ |                     \s
                                                  |___/                       \
                """);
        log.info("Starting module loader...");
        File[] modules = new File("modules").listFiles();
        if (modules == null) return;
        for (File f : modules) {
            if (f.getName().endsWith(".jar")) {
                getRegistry().getModuleLoader().loadPlugin(f.getName());
            }
        }
        log.info("Modules have been activated.");
    }
}