package de.julianweinelt.gop.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.julianweinelt.gop.GoPSystem;
import de.julianweinelt.gop.except.ModuleInvalidException;
import de.julianweinelt.gop.modules.event.Event;
import de.julianweinelt.gop.util.SystemScope;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

@Slf4j
public class ModuleLoader2 {
    private final Registry registry;
    private final HashMap<String, URL> moduleURLs = new HashMap<>();
    private final List<String> alreadyLoaded = new ArrayList<>();
    private URLClassLoader sharedLoader;

    public ModuleLoader2(Registry registry) {
        this.registry = registry;
    }

    public void prepareLoading() {
        File folder = new File("modules");
        File[] modules = folder.listFiles();
        if (modules == null) return;
        for (File f : modules) {
            if (f.getName().endsWith(".jar")) {
                try {
                    Path jarPath = Path.of("modules/" + f.getName());

                    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                        ZipEntry jsonEntry = jarFile.getEntry("module.json");
                        if (jsonEntry == null) {
                            throw new ModuleInvalidException("The loaded file " + f.getName() + " does not contain a module.json file.");
                        }

                        try(InputStream inputStream = jarFile.getInputStream(jsonEntry)) {
                            String jsonString = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                            JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();

                            URL jarURL = jarPath.toUri().toURL();
                            moduleURLs.put(json.get("moduleName").getAsString(), jarURL);
                        }

                    }
                } catch (Exception e) {
                    log.error("Error while loading module.");
                    log.error(e.getMessage());
                    for (StackTraceElement s : e.getStackTrace()) {
                        log.error(s.toString());
                    }
                }
            }
        }


        sharedLoader = new URLClassLoader(moduleURLs.values().toArray(URL[]::new), getClass().getClassLoader());
    }

    public void loadPlugin(String name) {
        log.info("Loading {}", name);
        boolean hasTabulaEntry = false;
        name = name.replace(".jar", "");
        try {
            Path jarPath = Path.of("modules/"+name+".jar");

            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                ZipEntry jsonEntry = jarFile.getEntry("module.json");
                if (jsonEntry == null) {
                    throw new ModuleInvalidException("The loaded file " + name + ".jar does not contain a module.json file.");
                }
                ZipEntry tabulaEntry = jarFile.getEntry("tabula.json");
                if (tabulaEntry != null) {
                    log.info("Module {} has a tabula.json.", name);
                    hasTabulaEntry = true;
                }

                try(InputStream inputStream = jarFile.getInputStream(jsonEntry)) {
                    String jsonString = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();
                    List<String> authors = new ArrayList<>();
                    for (JsonElement e : json.get("authors").getAsJsonArray()) authors.add(e.getAsString());
                    StringBuilder autorString = new StringBuilder();
                    for (String s : authors) autorString.append(s).append(",");
                    log.info("Detected module with name {} created by {}.", json.get("moduleName").getAsString(), autorString);
                    log.info("Version: {}", json.get("version").getAsString());

                    if (GoPSystem.getInstance().getRegistry().getModule(name) != null) {
                        return; // Module with the name is already loaded
                    }

                    String mainClassName = json.get("mainClass").getAsString();
                    //URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL}, getClass().getClassLoader());
                    URLClassLoader classLoader = sharedLoader;

                    log.info("Loading {}", mainClassName);

                    Class<?> mainClass = Class.forName(mainClassName, true, classLoader);

                    if (!Module.class.isAssignableFrom(mainClass)) {
                        throw new ModuleInvalidException("Main class must implement Module interface");
                    }

                    Module moduleInstance = (Module) mainClass.getDeclaredConstructor().newInstance();
                    log.info("Module Classloader: {}", moduleInstance.getClass().getClassLoader());

                    try {
                        moduleInstance.setName(json.get("moduleName").getAsString());
                        moduleInstance.setDescription(json.get("description").getAsString());
                        moduleInstance.setVersion(json.get("version").getAsString());
                    } catch (NullPointerException ignored) {
                        log.error("It looks like the author of the Module {} forgot to add important information" +
                                " to their module.json. Please contact them for support.", name);
                        log.error("Module {} can't be loaded due to a fatal error while loading.", name);
                        return;
                    }

                    try {
                        JsonElement minAPI = json.get("minAPIVersion");
                        if (minAPI == null) log.warn("Module {} does not request a minimum API version. " +
                                "This is recommended, as the API may change. Please report any problems" +
                                " related to this module to the corresponding author(s).", moduleInstance.getName());
                        else {
                            moduleInstance.setMinAPIVersion(minAPI.getAsString());
                            ComparableVersion moduleVersion = new ComparableVersion(minAPI.getAsString());
                            ComparableVersion systemVersion = new ComparableVersion(GoPSystem.systemVersion);
                            if (systemVersion.compareTo(moduleVersion) > 0) log.warn("Module {} is using an older version of" +
                                    " GoP: {}, but the server is using {}. Expect weird things while using.",
                                    name, minAPI.getAsString(), GoPSystem.systemVersion);
                        }
                        moduleInstance.setStoresSensitiveData(json.get("storesSensitiveData").getAsBoolean());
                        moduleInstance.setUsesEncryption(json.get("usesEncryption").getAsBoolean());
                        moduleInstance.setPreferredScope(SystemScope.valueOf(json.get("preferredScope").getAsString()
                                .replace("Scope.", "")));

                        JsonObject clientOptions = json.get("client").getAsJsonObject();
                        moduleInstance.setUseTabula(clientOptions.get("hasTab").getAsBoolean() && hasTabulaEntry);
                        moduleInstance.setTabulaPermission(clientOptions.get("tabViewPermission").getAsString());
                        moduleInstance.setTabulaTabShortName(clientOptions.get("tabShortName").getAsString());

                    } catch (NullPointerException ignored) {
                        log.error("The Module.json of {} provides some broken information. Please let the Author(s) " +
                                "correct them.", moduleInstance.getName());
                    } catch (IllegalArgumentException ignored) {
                        log.error("Module {} defined an illegal scope for running. Please contact the author(s) if there " +
                                "are problems while using this module", name);
                    }
                    moduleInstance.onLoad();
                    GoPSystem.getInstance().getRegistry().addModule(moduleInstance);
                    StringBuilder s = new StringBuilder();
                    for (Module m : GoPSystem.getInstance().getRegistry().getModules()) {
                        s.append(m.getName()).append(", ");
                    }
                    s = new StringBuilder(s.substring(0, s.length() - 2));
                    log.info(s.toString());

                    File dataFolder = new File("data/" + moduleInstance.getName());
                    if (dataFolder.mkdir()) log.info("Created new data folder for {}.", moduleInstance.getName());
                    GoPSystem.getInstance().getRegistry().callEvent(
                            new Event("ServerModuleLoadEvent")
                                    .set("module", moduleInstance.getName())
                                    .set("description", moduleInstance.getDescription())
                                    .set("authors", moduleInstance.getAuthors())
                                    .set("version", moduleInstance.getVersion())
                                    .set("dataFolder", moduleInstance.getDataFolder())
                    );



                    if (hasTabulaEntry) {
                        log.info("Registering module {} into Tabula Registry...", name);

                        try(InputStream iS = jarFile.getInputStream(tabulaEntry)) {
                            String tabulaString = new String(iS.readAllBytes(), StandardCharsets.UTF_8);
                            registry.getTabulaManager().createTab(moduleInstance.getName()
                                    , JsonParser.parseString(tabulaString).getAsJsonObject().toString(),
                                    moduleInstance.getTabulaPermission(), moduleInstance.getTabulaTabShortName());
                        } catch (Exception e) {
                            log.error("Module {} could not be registered in Tabula.", name);
                            log.error(e.getMessage());
                            printStacktrace(e);
                        }
                    }

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        log.info("Stopping {}...", moduleInstance.getName());
                        moduleInstance.onDisable();
                    }));
                }

            }
        } catch (Exception e) {
            log.error("Error while loading module {}.", name);
            log.error(e.getMessage());
            printStacktrace(e);
        }
    }

    public void enablePlugins() {
        for (Module m : GoPSystem.getInstance().getRegistry().getModules()) {
            m.onCreateCommands();
            m.onDefineEvents();
            m.onEnable();
        }
    }

    public void unloadPlugin(String name) {
        log.info("Disabling {}...", name);
        GoPSystem.getInstance().getRegistry().getModule(name).onDisable();
        GoPSystem.getInstance().getRegistry().removeModule(name);
    }

    private void printStacktrace(Exception e) {
        for (StackTraceElement a : e.getStackTrace()) {
            log.error(a.toString());
        }
    }
}