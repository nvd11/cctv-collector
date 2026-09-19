package com.gateman.cctv.collector.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight production utility to dynamically load .env files into a Map<String, String>,
 * mimicking Python's python-dotenv (dotenv_values) behavior without third-party dependencies.
 */
public final class DotEnv {

    private DotEnv() {
        // Utility class
    }

    /**
     * Loads .env from candidate locations (current dir, parent dir, module dir)
     * and overlays System.getenv() so both local .env and container/OS environment variables are accessible.
     *
     * @return unmodifiable dynamic Map containing environment variables
     */
    public static Map<String, String> load() {
        Map<String, String> envMap = new HashMap<>();

        // 1. Scan candidate .env locations
        Path[] candidatePaths = new Path[] {
                Path.of(".env"),
                Path.of("../.env"),
                Path.of("cctv-collector-service/.env")
        };

        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate)) {
                envMap.putAll(loadFile(candidate));
                break;
            }
        }

        // 2. System.getenv() overlays with higher precedence
        envMap.putAll(System.getenv());

        return Collections.unmodifiableMap(envMap);
    }

    /**
     * Parses a specific .env file into a dynamic Map<String, String>.
     *
     * @param path the path to the .env file
     * @return unmodifiable map of key-value pairs
     */
    public static Map<String, String> loadFile(Path path) {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                line = line.trim();
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    // Strip surrounding quotes if present
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            // Silently ignore I/O errors and return whatever was parsed
        }
        return Collections.unmodifiableMap(result);
    }
}
