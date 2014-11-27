package org.python.pydev.shared_core.preferences;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.python.pydev.shared_core.io.FileUtils;
import org.python.pydev.shared_core.log.Log;
import org.python.pydev.shared_core.string.StringUtils;
import org.python.pydev.shared_core.structure.OrderedSet;
import org.python.pydev.shared_core.structure.Tuple;
import org.yaml.snakeyaml.Yaml;

public final class ScopedPreferences implements IScopedPreferences {

    private static final Map<String, IScopedPreferences> pluginNameToPreferences = new HashMap<String, IScopedPreferences>();
    private static final Object lock = new Object();

    public static IScopedPreferences get(final String pluginName) {
        IScopedPreferences ret = pluginNameToPreferences.get(pluginName);
        if (ret == null) {
            synchronized (lock) {
                ret = new ScopedPreferences(pluginName);
                pluginNameToPreferences.put(pluginName, ret);
            }
        }
        return ret;
    }

    private String pluginName;
    private File[] trackedDirs;
    private File defaultSettingsDir = null;

    private ScopedPreferences(String pluginName) {
        this.pluginName = pluginName;
        Set<File> set = new OrderedSet<File>();

        //Default paths always there!
        String userHome;
        userHome = System.getProperty("user.home");
        if (userHome != null) {
            try {
                File f = new File(userHome);
                if (f.isDirectory()) {
                    f = new File(f, ".eclipse");
                    try {
                        if (!f.exists()) {
                            f.mkdirs();
                        }
                    } catch (Exception e) {
                        Log.log(e);
                    }
                    if (f.isDirectory()) {
                        set.add(f);
                        defaultSettingsDir = f;
                    }
                }
            } catch (Throwable e) {
                Log.log(e);
            }
        }
        if (set.size() == 0) {
            Log.log("System.getProperty(\"user.home\") returned " + userHome + " which is not a directory!");
        }

        // TODO: Add support later on.
        // ScopedPreferenceStore workspaceSettings = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginName);
        // String string = workspaceSettings.getString("ADDITIONAL_TRACKED_DIRS");
        // //Load additional tracked dirs
        // for (String s : StringUtils.split(string, '|')) {
        //     set.add(new File(s));
        // }
        this.trackedDirs = set.toArray(new File[0]);
    }

    @Override
    public Tuple<Map<String, Object>, Set<String>> loadFromUserSettings(Map<String, Object> saveData) throws Exception {
        Map<String, Object> o1 = new HashMap<>();
        Set<String> o2 = new HashSet<>();
        Tuple<Map<String, Object>, Set<String>> ret = new Tuple<>(o1, o2);

        File yamlFile = new File(defaultSettingsDir, pluginName + ".yaml");
        if (yamlFile.exists()) {
            String fileContents = FileUtils.getFileContents(yamlFile);
            Map<String, Object> loaded = getYamlFileContents(fileContents);
            Set<Entry<String, Object>> initialEntrySet = saveData.entrySet();
            for (Entry<String, Object> entry : initialEntrySet) {
                Object loadedObj = loaded.get(entry.getKey());
                if (loadedObj == null) {
                    //not in loaded file
                    o2.add(entry.getKey());
                } else {
                    o1.put(entry.getKey(), convertValueToTypeOfOldValue(loadedObj, entry.getValue()));
                }
            }
        }
        return ret;
    }

    @Override
    public String saveToUserSettings(Map<String, Object> saveData) throws Exception {
        if (defaultSettingsDir == null) {
            throw new Exception("user.home is not available!");
        }
        if (!defaultSettingsDir.isDirectory()) {
            throw new Exception("user.home/.settings: " + defaultSettingsDir + "is not a directory!");
        }
        Map<String, Object> yamlMapToWrite = new TreeMap<>();
        Set<Entry<String, Object>> entrySet = saveData.entrySet();
        for (Entry<String, Object> entry : entrySet) {
            yamlMapToWrite.put(convertPreferencesKeyToYamlKey(entry.getKey()), entry.getValue());
        }
        saveData = null; // make sure we don't use it anymore
        File yamlFile = new File(defaultSettingsDir, pluginName + ".yaml");
        if (yamlFile.exists()) {
            try {
                String fileContents = FileUtils.getFileContents(yamlFile);
                Map<String, Object> initial = getYamlFileContents(fileContents);
                initial.putAll(yamlMapToWrite);
                yamlMapToWrite = new TreeMap<>(initial);
            } catch (Exception e) {
                throw new Exception(
                        StringUtils
                                .format("Error: unable to write settings because the file: %s already exists but "
                                        + "is not a parseable YAML file (aborting to avoid overriding existing file).",
                                        yamlFile), e);
            }
        }

        dumpSaveDataToFile(yamlMapToWrite, yamlFile);
        return "Contents saved to:\n" + yamlFile;
    }

    private void dumpSaveDataToFile(Map<String, Object> saveData, File yamlFile) throws IOException {
        Yaml yaml = new Yaml();
        String dumpAsMap = yaml.dumpAsMap(saveData);
        FileUtils.writeStrToFile(dumpAsMap, yamlFile);
        // Don't use the code below because we want to dump as a map to have a better layout for the file.
        //
        // try (Writer output = new FileWriter(yamlFile)) {
        //     yaml.dump(saveData, new BufferedWriter(output));
        // }
    }

    /**
     * Returns the contents of the configuration file to be used or null.
     */
    private static IFile getProjectConfigFile(IProject project, String filename) {
        try {
            if (project != null && project.exists()) {
                return project.getFile(new Path(".settings").append(filename));
            }
        } catch (Exception e) {
            Log.log(e);
        }
        return null;
    }

    //TODO: We may want to have some caches...
    //long modificationStamp = projectConfigFile.getModificationStamp();

    @Override
    public String getString(IPreferenceStore pluginPreferenceStore, String keyInPreferenceStore, IAdaptable adaptable) {
        Object object = getFromProjectOrUserSettings(keyInPreferenceStore, adaptable);
        if (object != null) {
            return object.toString();
        }
        // Ok, not in project or user settings: get it from the workspace settings.
        return pluginPreferenceStore.getString(keyInPreferenceStore);
    }

    @Override
    public boolean getBoolean(IPreferenceStore pluginPreferenceStore, String keyInPreferenceStore, IAdaptable adaptable) {
        Object object = getFromProjectOrUserSettings(keyInPreferenceStore, adaptable);
        if (object != null) {
            return toBoolean(object);
        }
        // Ok, not in project or user settings: get it from the workspace settings.
        return pluginPreferenceStore.getBoolean(keyInPreferenceStore);
    }

    private Object getFromProjectOrUserSettings(String keyInPreferenceStore, IAdaptable adaptable) {
        // In the yaml all keys are lowercase!
        String keyInYaml = convertPreferencesKeyToYamlKey(keyInPreferenceStore);

        try {
            IProject project = (IProject) adaptable.getAdapter(IProject.class);
            IFile projectConfigFile = getProjectConfigFile(project, pluginName + ".yaml");
            if (projectConfigFile != null && projectConfigFile.exists()) {
                Map<String, Object> yamlFileContents = null;
                try {
                    yamlFileContents = getYamlFileContents(projectConfigFile);
                } catch (Exception e) {
                    Log.log(e);
                }
                if (yamlFileContents != null) {
                    Object object = yamlFileContents.get(keyInYaml);
                    if (object != null) {
                        return object;
                    }
                }
            }
        } catch (Exception e) {
            Log.log(e);
        }

        // If it got here, it's not in the project, let's try in the user settings...
        for (File dir : trackedDirs) {
            try {
                File yaml = new File(dir, pluginName + ".yaml");
                if (yaml.exists()) {
                    String fileContents = FileUtils.getFileContents(yaml);
                    Map<String, Object> yamlFileContents = null;
                    try {
                        yamlFileContents = getYamlFileContents(fileContents);
                    } catch (Exception e) {
                        Log.log(e);
                    }
                    if (yamlFileContents != null) {
                        Object object = yamlFileContents.get(keyInYaml);
                        if (object != null) {
                            return object;
                        }
                    }
                }
            } catch (Exception e) {
                Log.log(e);
            }
        }
        return null;
    }

    private String convertPreferencesKeyToYamlKey(String keyInPreferenceStore) {
        return keyInPreferenceStore; //don't convert it!
    }

    public static boolean toBoolean(Object found) {
        if (found == null) {
            return false;
        }
        if (Boolean.FALSE.equals(found)) {
            return false;
        }
        String asStr = found.toString();

        if ("false".equals(asStr) || "False".equals(asStr) || "0".equals(asStr) || asStr.trim().length() == 0) {
            return false;
        }
        return true;
    }

    public static int toInt(Object found) {
        if (found == null) {
            return 0;
        }
        if (found instanceof Integer) {
            return (int) found;
        }

        String asStr = found.toString();
        try {
            return Integer.parseInt(asStr);
        } catch (Exception e) {
            Log.log(e);
            return 0;
        }
    }

    private Object convertValueToTypeOfOldValue(Object loadedObj, Object oldValue) {
        if (oldValue == null) {
            return loadedObj; // Unable to do anything in this case...
        }
        if (loadedObj == null) {
            return null; // Nothing to see?
        }
        if (oldValue instanceof Boolean) {
            return toBoolean(loadedObj);
        }
        if (oldValue instanceof Integer) {
            return toInt(loadedObj);
        }
        if (oldValue instanceof String) {
            return loadedObj.toString();
        }
        throw new RuntimeException("Unable to handle type conversion to: " + oldValue.getClass());
    }

    /**
     * A number of exceptions may happen when loading the contents...
     */
    private Map<String, Object> getYamlFileContents(IFile projectConfigFile) throws Exception {
        IDocument fileContents = getFileContents(projectConfigFile);
        String yamlContents = fileContents.get();

        return getYamlFileContents(yamlContents);
    }

    /**
     * A number of exceptions may happen when loading the contents...
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getYamlFileContents(String yamlContents) throws Exception {
        if (yamlContents.trim().length() == 0) {
            return new HashMap<String, Object>();
        }
        Yaml yaml = new Yaml();
        Object load = yaml.load(yamlContents);
        if (!(load instanceof Map)) {
            if (load == null) {
                throw new Exception("Expected top-level element to be a map. Found: null");
            }
            throw new Exception("Expected top-level element to be a map. Found: " + load.getClass());
        }
        return (Map<String, Object>) load;
    }

    private IDocument getFileContents(IFile file) {
        return FileUtils.getDocFromResource(file);
    }

}
