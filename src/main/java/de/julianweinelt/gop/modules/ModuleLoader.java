package de.julianweinelt.gop.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.julianweinelt.gop.except.ModuleInvalidException;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

@Slf4j
public class ModuleLoader {
    public void loadPlugin(String name) {
        log.info("Loading {}", name);
        name = name.replace(".jar", "");
        try {
            Path jarPath = Path.of("modules/"+name+".jar");

            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                ZipEntry jsonEntry = jarFile.getEntry("module.json");
                if (jsonEntry == null) {
                    throw new ModuleInvalidException("The loaded file " + name + ".jar does not contain a module.json file.");
                }

                try(InputStream inputStream = jarFile.getInputStream(jsonEntry)) {
                    String jsonString = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();

                    String mainClassName = json.get("mainClass").getAsString();
                    URL jarURL = jarPath.toUri().toURL();
                    URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL});

                    Class<?> mainClass = Class.forName(mainClassName, true, classLoader);

                    if (!Module.class.isAssignableFrom(mainClass)) {
                        throw new ModuleInvalidException("Main class must implement Module interface");
                    }

                    Module moduleInstance = (Module) mainClass.getDeclaredConstructor().newInstance();
                    moduleInstance.setName(json.get("moduleName").getAsString());
                    moduleInstance.setDescription(json.get("description").getAsString());
                    moduleInstance.setVersion(json.get("version").getAsString());
                    moduleInstance.onLoad();
                }
            }
        } catch (Exception e) {
            log.info(e.getMessage());
        }
    }
}