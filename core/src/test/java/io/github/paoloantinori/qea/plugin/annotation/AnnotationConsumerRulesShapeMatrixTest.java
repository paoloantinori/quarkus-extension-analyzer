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
package io.github.paoloantinori.qea.plugin.annotation;

import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-33: the shape matrix. Every type-mention mechanism in the engine crossed with every
 * generic declaration shape, with the expected semantics DOCUMENTED per cell - so the next rule
 * that misses a wrapper is caught by construction instead of by a bench re-run (the lesson of the
 * Apicurio Instance&lt;JsonWebToken&gt; false negative: the behavioral fixtures had encoded the
 * same incomplete shape model as the code).
 *
 * <p>Expected semantics table (why each cell credits or does not):
 * <ul>
 *   <li>JWT probe ({@code mentionsJwt}): the raw type name OR one level of type argument,
 *       wrapper-name-agnostic (Instance/Optional/Supplier/Provider are indistinguishable to it).
 *       Arrays and wildcards credit via Jandex delegation: {@code ArrayType.name()} and {@code
 *       WildcardType.name()} return the component/bound name. Two-level nesting does NOT credit
 *       (not observed in real code; the TASK-32 near-miss telemetry reports it instead).
 *   <li>Qute probe: BARE {@code Template} field / {@code TemplateInstance} return only. Wrapped
 *       forms (e.g. {@code Instance<Template>}) do NOT credit - a documented gap, not observed in
 *       any bench app; extend the probe before crediting them.
 *   <li>REST serializer ({@code returnTypeNeedsSerializer}, RETURN position on REST methods):
 *       unwrap of Uni/Multi/Optional/CompletionStage recurses, so arbitrary nesting of those
 *       credits; RestResponse&lt;T&gt; unwraps its payload; List/Set (unexcluded containers)
 *       credit conservatively; Void/Response/TemplateInstance returns never credit.
 * </ul>
 *
 * <p>Annotation-POSITION shapes (class vs method targets, inherited endpoints, interface-declared
 * methods, field-level constraint annotations) are positional, not generic-type, shapes and are
 * covered by the dedicated tests in {@link AnnotationConsumerRulesBehaviorTest}
 * (methodLevelPathOnInterfaceCreditsSerializer, inheritedRestMethodCreditsSerializer,
 * notNullFieldCreditsHibernateValidator, and the endpoint family); this matrix does not duplicate
 * them.
 */
class AnnotationConsumerRulesShapeMatrixTest {

    private static final String SMALLRYE_JWT = "io.quarkus:quarkus-smallrye-jwt";
    private static final String REST_QUTE = "io.quarkus:quarkus-rest-qute";
    private static final String REST_JACKSON = "io.quarkus:quarkus-rest-jackson";
    private static final String JWT = "org.eclipse.microprofile.jwt.JsonWebToken";
    private static final String POJO = "com.acme.Pojo";
    private static final String INSTANCE = "jakarta.enterprise.inject.Instance";
    private static final String PATH_ANN = "jakarta.ws.rs.Path";
    private static final String GET_ANN = "jakarta.ws.rs.GET";
    private static final Path NOWHERE = Path.of("no-such-project-root");

    /** One matrix row: a declaration shape and whether it must credit. */
    record Shape(String label, boolean mustCredit, byte[] clazz) {}

    // --- JWT matrix: shapes x positions --------------------------------------------------------------

    @Test
    void jwtMatrixFieldPosition() throws IOException {
        assertMatrix(SMALLRYE_JWT, List.of(
                shape("bare Jwt field", true, fieldClass(JWT)),
                shape("Instance<Jwt>", true, genericField(INSTANCE, JWT)),
                shape("Optional<Jwt>", true, genericField("java.util.Optional", JWT)),
                shape("Supplier<Jwt>", true, genericField("java.util.function.Supplier", JWT)),
                shape("Provider<Jwt>", true, genericField("jakarta.enterprise.inject.Provider", JWT)),
                shape("Jwt[] array", true, arrayField(JWT)),
                shape("Instance<? extends Jwt>", true, wildcardField(INSTANCE, JWT)),
                shape("Provider<Instance<Jwt>> nested (no credit; near-miss territory)",
                        false, nestedField("jakarta.enterprise.inject.Provider", INSTANCE, JWT)),
                shape("Jwt[][] 2D array (array of a usage type at any depth is usage)",
                        true, array2Field(JWT)),
                shape("Instance<String> unrelated argument", false, genericField(INSTANCE, "java.lang.String")),
                shape("bare String field", false, fieldClass("java.lang.String"))));
    }

    @Test
    void jwtMatrixReturnPosition() throws IOException {
        assertMatrix(SMALLRYE_JWT, List.of(
                shape("bare Jwt return", true, methodReturning("()L" + internal(JWT) + ";", null)),
                shape("Instance<Jwt> return", true,
                        methodReturning("()L" + internal(INSTANCE) + ";",
                                "()L" + internal(INSTANCE) + "<L" + internal(JWT) + ";>;")),
                shape("Provider<Instance<Jwt>> nested return (no credit)", false,
                        methodReturning("()L" + internal("jakarta.enterprise.inject.Provider") + ";",
                                "()L" + internal("jakarta.enterprise.inject.Provider")
                                        + "<L" + internal(INSTANCE) + "<L" + internal(JWT) + ";>;>;"))));
    }

    @Test
    void jwtMatrixParameterPosition() throws IOException {
        assertMatrix(SMALLRYE_JWT, List.of(
                shape("bare Jwt parameter", true,
                        methodWithParam("(L" + internal(JWT) + ";)V", null)),
                shape("Instance<Jwt> parameter", true,
                        methodWithParam("(L" + internal(INSTANCE) + ";)V",
                                "(L" + internal(INSTANCE) + "<L" + internal(JWT) + ";>;)V"))));
    }

    // --- Qute matrix: bare vs wrapped (documented gap) ------------------------------------------------

    @Test
    void quteMatrixBareOnlyIsADocumentedGap() throws IOException {
        assertMatrix(REST_QUTE, List.of(
                shape("bare Template field", true, fieldClass("io.quarkus.qute.Template")),
                shape("bare TemplateInstance return", true,
                        methodReturning("()Lio/quarkus/qute/TemplateInstance;", null)),
                shape("Instance<Template> (documented gap: not credited)", false,
                        genericField(INSTANCE, "io.quarkus.qute.Template"))));
    }

    // --- REST serializer matrix: return shapes -------------------------------------------------------

    @Test
    void serializerMatrixReturnShapes() throws IOException {
        assertMatrix(REST_JACKSON, List.of(
                shape("bare Pojo", true, restMethod("()L" + internal(POJO) + ";", null)),
                shape("Uni<Pojo>", true, restMethod("()Lio/smallrye/mutiny/Uni;",
                        "()Lio/smallrye/mutiny/Uni<L" + internal(POJO) + ";>;")),
                shape("Uni<Uni<Pojo>> (recursion)", true, restMethod("()Lio/smallrye/mutiny/Uni;",
                        "()Lio/smallrye/mutiny/Uni<Lio/smallrye/mutiny/Uni<L" + internal(POJO) + ";>;>;")),
                shape("Optional<Pojo>", true, restMethod("()Ljava/util/Optional;",
                        "()Ljava/util/Optional<L" + internal(POJO) + ";>;")),
                shape("CompletionStage<Pojo>", true,
                        restMethod("()Ljava/util/concurrent/CompletionStage;",
                                "()Ljava/util/concurrent/CompletionStage<L" + internal(POJO) + ";>;")),
                shape("List<Pojo> (unexcluded container: conservative credit)", true,
                        restMethod("()Ljava/util/List;", "()Ljava/util/List<L" + internal(POJO) + ";>;")),
                shape("RestResponse<Pojo>", true,
                        restMethod("()Lorg/jboss/resteasy/reactive/RestResponse;",
                                "()Lorg/jboss/resteasy/reactive/RestResponse<L" + internal(POJO) + ";>;")),
                shape("Uni<RestResponse<Pojo>>", true, restMethod("()Lio/smallrye/mutiny/Uni;",
                        "()Lio/smallrye/mutiny/Uni<Lorg/jboss/resteasy/reactive/RestResponse<L"
                                + internal(POJO) + ";>;>;")),
                shape("Uni<Void> (machinery payload)", false, restMethod("()Lio/smallrye/mutiny/Uni;",
                        "()Lio/smallrye/mutiny/Uni<Ljava/lang/Void;>;")),
                shape("bare Response (machinery)", false,
                        restMethod("()Ljakarta/ws/rs/core/Response;", null)),
                shape("TemplateInstance (rendered, not serialized)", false,
                        restMethod("()Lio/quarkus/qute/TemplateInstance;", null)),
                shape("Pojo[] array", true, restMethod("()[L" + internal(POJO) + ";", null)),
                shape("Pojo[][] 2D array", true, restMethod("()[[L" + internal(POJO) + ";", null)),
                shape("String[] (array of an excluded type must NOT credit)", false,
                        restMethod("()[Ljava/lang/String;", null)),
                shape("Void[] (array of an excluded type must NOT credit)", false,
                        restMethod("()[Ljava/lang/Void;", null))));
    }

    // --- harness ---------------------------------------------------------------------------------------

    private static void assertMatrix(String ga, List<Shape> shapes) throws IOException {
        for (Shape s : shapes) {
            Index idx = index(s.clazz());
            AnalysisReport out = AnnotationConsumerRules.apply(reportWithSuspect(ga), idx,
                    Set.of(ga), Set.of(), NOWHERE);
            Verdict verdict = out.dependencies().stream()
                    .filter(r -> r.ga().equals(ga)).findFirst().orElseThrow().verdict();
            assertThat(verdict)
                    .as("shape '%s' must %s", s.label(), s.mustCredit() ? "CREDIT" : "NOT credit")
                    .isEqualTo(s.mustCredit() ? Verdict.USED_BYTECODE : Verdict.SUSPECT);
        }
    }

    private static Shape shape(String label, boolean mustCredit, byte[] clazz) {
        return new Shape(label, mustCredit, clazz);
    }

    private static io.github.paoloantinori.qea.plugin.report.AnalysisReport reportWithSuspect(String ga) {
        var row = new io.github.paoloantinori.qea.plugin.report.ExtensionReport(ga, true, Verdict.SUSPECT,
                false, Set.of(), List.of(), Set.of(), List.of(), false, List.of(), null, null, null,
                List.of(), List.of());
        return new io.github.paoloantinori.qea.plugin.report.AnalysisReport("test:app:1", "now",
                List.of(row), List.of(), null, null, null);
    }

    private static Index index(byte[]... classes) throws IOException {
        Indexer indexer = new Indexer();
        byte[][] stubs = {
                plain("com.acme.Pojo"), plain("io.smallrye.mutiny.Uni"),
                plain("io.smallrye.mutiny.Multi"), plain(JWT),
                plain("io.quarkus.qute.Template"), plain("io.quarkus.qute.TemplateInstance"),
                annotation(PATH_ANN), annotation(GET_ANN)};
        for (byte[] b : stubs) {
            indexer.index(new ByteArrayInputStream(b));
        }
        for (byte[] b : classes) {
            indexer.index(new ByteArrayInputStream(b));
        }
        return indexer.complete();
    }

    private static String internal(String fqcn) {
        return fqcn.replace('.', '/');
    }

    private static byte[] plain(String fqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal(fqcn), null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] annotation(String fqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE,
                internal(fqcn), null, "java/lang/Object", new String[]{"java/lang/Annotation"});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] fieldClass(String fieldFqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "matrix/F", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f", "L" + internal(fieldFqcn) + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] genericField(String wrapperFqcn, String argFqcn) {
        return nestedField(wrapperFqcn, null, argFqcn);
    }

    private static byte[] nestedField(String outerFqcn, String innerFqcn, String argFqcn) {
        String typeArg = innerFqcn == null
                ? "L" + internal(argFqcn) + ";"
                : "L" + internal(innerFqcn) + "<L" + internal(argFqcn) + ";>;";
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "matrix/G", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f", "L" + internal(outerFqcn) + ";",
                "L" + internal(outerFqcn) + "<" + typeArg + ">;", null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] arrayField(String componentFqcn) {
        return arrayFieldN(1, componentFqcn, "matrix/A");
    }

    private static byte[] array2Field(String componentFqcn) {
        return arrayFieldN(2, componentFqcn, "matrix/A2");
    }

    private static byte[] arrayFieldN(int dims, String componentFqcn, String className) {
        String one = "[L" + internal(componentFqcn) + ";";
        String desc = one.repeat(dims);
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f", desc, desc, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] wildcardField(String wrapperFqcn, String boundFqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "matrix/W", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f", "L" + internal(wrapperFqcn) + ";",
                "L" + internal(wrapperFqcn) + "<+L" + internal(boundFqcn) + ";>;", null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] methodReturning(String descriptor, String signature) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "matrix/R", null, "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", descriptor, signature, null);
        mv.visitCode();
        mv.visitMaxs(1, 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A void method with one parameter of the given full descriptor (+ optional signature). */
    private static byte[] methodWithParam(String descriptor, String signature) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "matrix/P", null, "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", descriptor, signature, null);
        mv.visitCode();
        mv.visitMaxs(1, 2);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A @Path class with one @GET method having the given return descriptor/signature. */
    private static byte[] restMethod(String descriptor, String signature) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "matrix/S", null, "java/lang/Object", null);
        cw.visitAnnotation("L" + internal(PATH_ANN) + ";", true).visitEnd();
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", descriptor, signature, null);
        mv.visitAnnotation("L" + internal(GET_ANN) + ";", true).visitEnd();
        mv.visitCode();
        mv.visitMaxs(1, 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
