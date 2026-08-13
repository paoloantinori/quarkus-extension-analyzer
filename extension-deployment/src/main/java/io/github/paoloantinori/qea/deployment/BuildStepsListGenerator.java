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

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Indexer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Scans the deployment module's compiled classes ({@code target/classes}) for methods annotated
 * with {@code @BuildStep} and writes {@code META-INF/quarkus-build-steps.list} (the FQN list Quarkus
 * reads to discover build steps at augmentation time, instead of runtime classpath scanning).
 *
 * <p>Run by {@code exec-maven-plugin} during {@code process-classes} (after compile, before package):
 * <pre>
 * java -cp ... io.github.paoloantinori.qea.deployment.BuildStepsListGenerator target/classes
 * </pre>
 * Replaces the static {@code quarkus-build-steps.list} resource: the file is generated from the
 * actual bytecode, so adding a new {@code @BuildStep} class is automatically picked up without
 * manually editing the list.
 */
public final class BuildStepsListGenerator {

    private static final DotName BUILD_STEP = DotName.createSimple("io.quarkus.deployment.annotations.BuildStep");

    public static void main(String[] args) throws IOException {
        Path classesDir = args.length > 0 ? Paths.get(args[0]) : Paths.get("target", "classes");
        if (!Files.isDirectory(classesDir)) {
            System.err.println("BuildStepsListGenerator: " + classesDir + " is not a directory, skipping");
            return;
        }

        Indexer indexer = new Indexer();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            for (Path classFile : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                try (InputStream in = Files.newInputStream(classFile)) {
                    indexer.index(in);
                } catch (IOException | IllegalArgumentException ignored) {
                    // skip unparseable
                }
            }
        }

        TreeSet<String> buildStepClasses = new TreeSet<>();
        for (AnnotationInstance ai : indexer.complete().getAnnotations(BUILD_STEP)) {
            AnnotationTarget target = ai.target();
            if (target != null && target.kind() == AnnotationTarget.Kind.METHOD) {
                ClassInfo ci = target.asMethod().declaringClass();
                buildStepClasses.add(ci.name().toString());
            }
        }

        Path output = classesDir.resolve("META-INF").resolve("quarkus-build-steps.list");
        Files.createDirectories(output.getParent());
        if (buildStepClasses.isEmpty()) {
            Files.deleteIfExists(output);
            System.out.println("BuildStepsListGenerator: no @BuildStep methods found, removed " + output);
        } else {
            Files.write(output, buildStepClasses);
            System.out.println("BuildStepsListGenerator: wrote " + buildStepClasses.size()
                    + " build-step class(es) to " + output);
        }
    }
}
