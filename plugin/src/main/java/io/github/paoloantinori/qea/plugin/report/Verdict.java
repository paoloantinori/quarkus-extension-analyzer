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
package io.github.paoloantinori.qea.plugin.report;

import com.fasterxml.jackson.annotation.JsonValue;

/** The four classifications from DESIGN.md, in signal-priority order. */
public enum Verdict {
    USED_BYTECODE("used-bytecode"),
    USED_CONFIG("used-config"),
    USED_CAPABILITY("used-capability"),
    SUSPECT("suspect");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
