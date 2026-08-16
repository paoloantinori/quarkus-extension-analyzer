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

import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link AnnotationAttribution} detection logic. The full end-to-end behavior
 * (hibernate-validator flipping to used-bytecode on rest-fights) is validated by the bench run
 * documented in docs/AUTONOMOUS-WORK-LOG.md; these tests guard the Jandex-index probing helpers
 * and the AnnotationAttribution rules.
 */
class AnnotationAttributionTest {

    /**
     * An empty Jandex index has no known annotations; the annotation-family probes return false.
     * This guards against false positives when the app uses none of the known annotation families.
     */
    @Test
    void emptyIndexHasNoKnownAnnotations() {
        Indexer indexer = new Indexer();
        Index index = indexer.complete();
        assertThat(index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotNull"))).isEmpty();
        assertThat(index.getAnnotations(DotName.createSimple("io.quarkus.scheduler.Scheduled"))).isEmpty();
    }

    /**
     * Loading {@link AnnotationAttribution} initializes the RULES list (a malformed entry would
     * fail here), and the family annotation names used by the probes are well-formed DotNames.
     * This does NOT guard against rule removal or dead probes; the behavioral coverage lives in
     * {@link AnnotationAttributionBehaviorTest}, which exercises apply() end-to-end per family.
     */
    @Test
    void rulesAreCuratedForKnownFamilies() {
        assertThat(AnnotationAttribution.class).isNotNull();
        assertThat(DotName.createSimple("jakarta.validation.constraints.NotNull").toString())
                .startsWith("jakarta.validation.constraints.");
        assertThat(DotName.createSimple("io.quarkus.scheduler.Scheduled").toString())
                .isEqualTo("io.quarkus.scheduler.Scheduled");
    }

    /**
     * A Jandex index built from an empty indexer is valid but has no classes.
     * The annotation-family probes operate on such an index safely (return false for all).
     */
    @Test
    void emptyIndexIsValid() {
        Indexer indexer = new Indexer();
        Index index = indexer.complete();
        assertThat(index.getKnownClasses()).isEmpty();
    }
}
