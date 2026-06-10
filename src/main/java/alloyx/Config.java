// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project config: {@code alloyx.json} in the folder you run against, found by
 * walking up the directory tree (like {@code .git}). Holds the target org alias
 * so you don't pass {@code --org} every time. A CLI {@code --org} overrides it.
 *
 *   { "org": "my-org-alias", "apiVersion": "60.0" }
 */
final class Config {
    static final String CONFIG_NAME = "alloyx.json";

    private Config() {
    }

    static Optional<String> findOrg(Path start) {
        return find(start).flatMap(json -> field(json, "org"));
    }

    private static Optional<String> find(Path start) {
        Path dir = start.toAbsolutePath();
        if (Files.isRegularFile(dir)) {
            dir = dir.getParent();
        }
        while (dir != null) {
            Path candidate = dir.resolve(CONFIG_NAME);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Optional.of(Files.readString(candidate));
                } catch (Exception e) {
                    return Optional.empty();
                }
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private static Optional<String> field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
