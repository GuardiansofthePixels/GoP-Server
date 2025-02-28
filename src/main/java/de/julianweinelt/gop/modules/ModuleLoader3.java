package de.julianweinelt.gop.modules;

import com.google.gson.*;
import de.julianweinelt.gop.GoPSystem;
import de.julianweinelt.gop.except.ModuleInvalidException;
import de.julianweinelt.gop.modules.event.Event;
import de.julianweinelt.gop.util.LoadPriority;
import de.julianweinelt.gop.util.SystemScope;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

@Slf4j
public class ModuleLoader {
    private final Registry registry;
    private URLClassLoader sharedLoader;
    private List<ModuleLoadHolder> holders = new ArrayList<>();


    public ModuleLoader(Registry registry) {
        this.registry = registry;
    }

    /**
     * Sorts the list of modules based on their dependencies and assigns a load order to each module.
     * Modules are ordered according to their specified {@link LoadPriority}, either LOAD_BEFORE or LOAD_AFTER.
     * This ensures that modules with dependencies are loaded in the correct sequence.
     * <p>
     * The sorting is implemented using a topological sorting algorithm (Kahn's algorithm),
     * which constructs a directed graph where modules are nodes, and dependencies define the edges.
     * <p>
     * Example:
     * Module A depends on Module B (LOAD_BEFORE).
     * Module C depends on Module A (LOAD_BEFORE).
     * The sorted order will be: B → A → C.
     * <p>
     * If circular dependencies are detected, an error is logged.
     */
    public void sortModules() {
        List<ModuleLoadHolder> modules = holders;
        Map<String, ModuleLoadHolder> moduleMap = new HashMap<>();
        Map<String, List<String>> adjacencyList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (ModuleLoadHolder module : modules) {
            moduleMap.put(module.name(), module);
            adjacencyList.put(module.name(), new ArrayList<>());
            inDegree.put(module.name(), 0);
        }

        for (ModuleLoadHolder module : modules) {
            for (ModuleDependency dep : module.dependencies()) {
                String dependencyName = dep.getName();

                if (!moduleMap.containsKey(dependencyName)) {
                    log.error("Module not found: {}", dependencyName);
                }

                if (dep.getLoadPrior() == LoadPriority.LOAD_BEFORE) {
                    adjacencyList.get(dependencyName).add(module.name()); // dep -> module
                    inDegree.put(module.name(), inDegree.getOrDefault(module.name(), 0) + 1);
                } else if (dep.getLoadPrior() == LoadPriority.LOAD_AFTER) {
                    adjacencyList.get(module.name()).add(dependencyName); // module -> dep
                    inDegree.put(dependencyName, inDegree.getOrDefault(dependencyName, 0) + 1);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (String moduleName : inDegree.keySet()) {
            if (inDegree.get(moduleName) == 0) {
                queue.add(moduleName);
            }
        }

        List<ModuleLoadHolder> sortedModules = new ArrayList<>();
        int loadOrder = 0;

        while (!queue.isEmpty()) {
            String moduleName = queue.poll();
            ModuleLoadHolder module = moduleMap.get(moduleName);
            sortedModules.add(new ModuleLoadHolder(module.name(), module.jarURl(), module.mainClass(), loadOrder++, module.dependencies()));

            for (String dependent : adjacencyList.get(moduleName)) {
                inDegree.put(dependent, inDegree.get(dependent) - 1);
                if (inDegree.get(dependent) == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (sortedModules.size() != modules.size()) {
            log.error("Circular reference found while loading modules!");
        }

        holders = sortedModules;
    }

    /**
     * Scans the "modules" directory for JAR files and loads their metadata from the "module.json" file.
     * Parses the metadata to extract module information, dependencies, and other relevant properties.
     * Valid modules are stored in a list for further processing.
     * <p>
     * If a module is missing its "module.json" file or has incorrect formatting, an error is logged.
     * <p>
     * Example:
     * - A valid "module.json" file should contain:
     *   {
     *     "moduleName": "ExampleModule",
     *     "mainClass": "com.example.ExampleModule",
     *     "version": "1.0.0",
     *     "dependencies": [
     *       {"name": "CoreModule", "minVersion": "1.2.0", "required": true, "loadPrior": "LOAD_BEFORE"}
     *     ]
     *   }
     *   </p>
     * <p>
     * - If "module.json" is missing, the module will be considered invalid and ignored.
     */
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
                            List<ModuleDependency> moduleDependencies = new ArrayList<>();
                            for (JsonElement element : json.get("dependencies").getAsJsonArray()) {
                                JsonObject o = element.getAsJsonObject();
                                moduleDependencies.add(
                                        new ModuleDependency(
                                                o.get("name").getAsString(),
                                                o.get("minVersion").getAsString(),
                                                o.get("required").getAsBoolean(),
                                                LoadPriority.valueOf(o.get("loadPrior").getAsString())
                                        )
                                );
                            }
                            ModuleLoadHolder holder = new ModuleLoadHolder(
                                    json.get("moduleName").getAsString(),
                                    jarURL,
                                    json.get("mainClass").getAsString(),
                                    0,
                                    moduleDependencies
                            );
                            holders.add(holder);
                            registry.callEvent(new Event("ServerModuleDiscoverEvent").set(
                                    "module", holder.name()
                            ));
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

        URL[] urls = new URL[holders.size()];
        int i = 0;
        for (ModuleLoadHolder h : holders) urls[i] = h.jarURl();

        sharedLoader = new URLClassLoader(urls, getClass().getClassLoader());
    }
    /**
     * Iterates over the sorted list of modules and loads each module in sequence.
     * Ensures that all modules are loaded in the correct order based on their dependencies.
     * <p>
     * Before loading, each module's metadata is checked to prevent duplicate loading.
     */
    public void loadModules() {
        for (ModuleLoadHolder h : holders) {
            String name = h.name();
            loadModule(name);
        }
    }
    /**
     * Loads a specific module by its name.
     * Reads the module's metadata, validates its dependencies, and initializes its main class.
     * The module's lifecycle method `onLoad()` is called after successful initialization.
     * <p>
     * If the module is already loaded or has missing dependencies, an error is logged.
     * <p>
     * Example:
     * - If "MyModule" depends on "CoreModule", "CoreModule" must be loaded first.
     * - If "MyModule" has a main class "com.example.MyModule", the system attempts to load it dynamically.
     *
     * @param name The name of the module to load.
     */
    public void loadModule(String name) {
        log.info("Loading {}", name);
        boolean hasTabulaEntry = false;
        name = name.replace(".jar", "");
        try {
            Path jarPath = Path.of("modules/" + name + ".jar");

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

                try (InputStream inputStream = jarFile.getInputStream(jsonEntry)) {
                    String jsonString = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();
                    List<String> authors = new ArrayList<>();
                    for (JsonElement e : json.get("authors").getAsJsonArray()) authors.add(e.getAsString());
                    StringBuilder autorString = new StringBuilder();
                    for (String s : authors) autorString.append(s).append(",");
                    log.info("Detected module with name {} created by {}.", json.get("moduleName").getAsString(), autorString);
                    log.info("Version: {}", json.get("version").getAsString());

                    if (GoPSystem.getInstance().getRegistry().getModule(name) != null) {
                        log.warn("A module named {} seems to be already loaded. Skipping load...", name);
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
                    moduleInstance.setJarURL(jarPath);
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
                            if (systemVersion.compareTo(moduleVersion) > 0)
                                log.warn("Module {} is using an older version of" +
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
                    registry.callEvent(new Event("ServerModuleLoadEvent")
                            .set("module", json.get("moduleName").getAsString())
                            .set("version", json.get("version").getAsString())
                            .set("description", json.get("description").getAsString())
                            .set("mainClass", json.get("mainClass").getAsString())
                            .set("preferredScope", SystemScope.valueOf(json.get("preferredScope").getAsString().replace("Scope.", "")))
                            .set("storesSensitiveData", json.get("storesSensitiveData").getAsBoolean())
                            .set("usesEncryption", json.get("usesEncryption").getAsBoolean())
                            .set("usesTabula", json.get("usesTabula").getAsBoolean())
                    );


                    if (hasTabulaEntry) {
                        log.info("Registering module {} into Tabula Registry...", name);

                        try (InputStream iS = jarFile.getInputStream(tabulaEntry)) {
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

    /**
     * Unloads a specific module by its name.
     * Calls the module's `onDisable()` method to allow proper cleanup before removal.
     * The module is then removed from the registry to free up resources.
     * <p>
     * Example:
     * - If "MyModule" is active and needs to be disabled, calling `unloadPlugin("MyModule")`
     *   ensures that its cleanup process is executed before being removed.
     *
     * @param name The name of the module to unload.
     */
    public void unloadPlugin(String name) {
        log.info("Disabling {}...", name);
        GoPSystem.getInstance().getRegistry().getModule(name).onDisable();
        GoPSystem.getInstance().getRegistry().removeModule(name);
    }

    /**
     * Logs the full stack trace of an exception for debugging purposes.
     * This helps in identifying the cause of module loading or execution failures.
     * <p>
     * Example:
     * - If an exception occurs while loading a module, this method prints the full error trace
     *   to aid in debugging.
     *
     * @param e The exception to log.
     */
    private void printStacktrace(Exception e) {
        for (StackTraceElement a : e.getStackTrace()) {
            log.error(a.toString());
        }
    }
}