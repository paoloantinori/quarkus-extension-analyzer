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
package io.github.paoloantinori.qea.plugin.bytecode;

import org.apache.maven.shared.dependency.analyzer.ClassesPatterns;
import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer;
import org.apache.maven.shared.dependency.analyzer.DependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Signal 2: does the project's own compiled bytecode reference a dependency's classes.
 *
 * <p>Two mechanisms, per DESIGN.md: extensions are checked via a Jandex scan of the project's own
 * {@code target/classes}/{@code target/test-classes} (a reference to any class contained in the
 * extension's runtime artifact marks it used); plain (non-extension) dependencies are delegated to
 * {@code org.apache.maven.shared:maven-dependency-analyzer}'s ASM-based analysis, so results for them
 * stay directly comparable to {@code maven-dependency-plugin:analyze}.
 *
 * <p>The Jandex-based extraction only sees types that appear in field/parameter/return-type/supertype/
 * annotation position, not e.g. a class referenced only via a bare static method call inside a method
 * body with no other trace; the ASM path used for plain jars does not have this limitation. This
 * asymmetry is accepted here per the locked M2 design (plan item 6): extensions additionally carry
 * signals 1 and 3, so a bytecode-signal miss is not automatically a false "suspect" the way it would be
 * for a plain jar.
 */
public final class BytecodeUsage {

    private BytecodeUsage() {
    }

    /**
     * Every type referenced, in declaration position, by the classes under {@code classesDirs}: field
     * types, method return/parameter types, superclass, interfaces and annotation types. Annotation types
     * include member-level (field, method, record-component) annotations, not just class-level ones
     * (TASK-12: previously missed because {@code declaredAnnotations()} excluded them).
     */
    public static Set<String> referencedTypesViaJandex(List<Path> classesDirs) throws IOException {
        Indexer indexer = new Indexer();
        boolean any = false;
        for (Path dir : classesDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path classFile : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    try (InputStream in = Files.newInputStream(classFile)) {
                        indexer.index(in);
                        any = true;
                    } catch (IOException | IllegalArgumentException ignored) {
                        // Unparseable class (multi-release, corrupt); skip it, coverage over completeness.
                    }
                }
            }
        }
        if (!any) {
            return Set.of();
        }
        Index index = indexer.complete();
        return referencedTypes(index);
    }

    /**
     * Every type referenced, in declaration position, by the classes in a Jandex {@link Index}: field
     * types, method return/parameter types, superclass, interfaces and annotation types (class-,
     * field-, method- and record-component-level via {@code annotations()}, per TASK-12).
     *
     * <p>Shared by the app-classes scan ({@link #referencedTypesViaJandex}) and the deployment-jar
     * vocabulary scan ({@code DeploymentVocabulary}), so "what types does this index reference?" is
     * answered once, identically, for both. The deployment-jar fourth signal (TASK-8) is then a set
     * intersection against the app's referenced set, not new analysis logic.
     */
    public static Set<String> referencedTypes(Index index) {
        Set<String> referenced = new TreeSet<>();
        for (ClassInfo ci : index.getKnownClasses()) {
            addName(referenced, ci.superName());
            for (DotName itf : ci.interfaceNames()) {
                addName(referenced, itf);
            }
            for (FieldInfo f : ci.fields()) {
                addName(referenced, topLevelName(f.type()));
            }
            for (MethodInfo m : ci.methods()) {
                addName(referenced, topLevelName(m.returnType()));
                for (Type param : m.parameterTypes()) {
                    addName(referenced, topLevelName(param));
                }
            }
            for (AnnotationInstance ai : ci.annotations()) {
                addName(referenced, ai.name());
            }
        }
        return referenced;
    }

    /** The set of classes physically contained in a jar (or classes directory), for membership checks. */
    public static Set<String> containedClasses(Path artifact) throws IOException {
        return new DefaultClassAnalyzer().analyze(toUrl(artifact), new ClassesPatterns());
    }

    /**
     * Classes referenced from {@code classesDirs}, computed via the ASM-based analyzer that {@code
     * maven-dependency-plugin:analyze} itself uses, for the plain-jar (non-extension) bytecode signal.
     */
    public static Set<String> referencedClassesViaAsm(List<Path> classesDirs) throws IOException {
        DependencyAnalyzer analyzer = new ASMDependencyAnalyzer();
        Set<String> referenced = new TreeSet<>();
        for (Path dir : classesDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            referenced.addAll(analyzer.analyze(toUrl(dir), new ClassesPatterns()));
        }
        return referenced;
    }

    private static void addName(Set<String> out, DotName name) {
        if (name != null) {
            out.add(name.toString());
        }
    }

    private static DotName topLevelName(Type type) {
        return type == null ? null : type.name();
    }

    private static URL toUrl(Path path) throws IOException {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IOException("Cannot build URL for " + path, e);
        }
    }
}
