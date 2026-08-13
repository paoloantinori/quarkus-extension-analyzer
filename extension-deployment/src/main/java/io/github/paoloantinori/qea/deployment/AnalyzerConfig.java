/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.paoloantinori.qea.deployment;

import io.quarkus.runtime.configuration.ConfigUtils;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Build-time config for the extension-analyzer extension.
 *
 * <p>Reads the flags from MicroProfile Config (the standard Quarkus config source), avoiding the
 * deprecated {@code @ConfigRoot}/{@code @ConfigItem} annotation API (whose shape changed across
 * Quarkus 3.x). The flags mirror the mojo's: {@code quarkus.extension-analyzer.fail-on-suspect}
 * (default false).
 */
public final class AnalyzerConfig {

    public final boolean failOnSuspect;

    public AnalyzerConfig() {
        this.failOnSuspect = readBoolean("quarkus.extension-analyzer.fail-on-suspect", false);
    }

    private static boolean readBoolean(String key, boolean fallback) {
        try {
            return ConfigProvider.getConfig().getOptionalValue(key, Boolean.class).orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }
}
