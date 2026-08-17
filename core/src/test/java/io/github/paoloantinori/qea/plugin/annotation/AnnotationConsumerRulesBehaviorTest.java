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
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral test suite for {@link AnnotationConsumerRules#apply} (born as TASK-25's suite for the extension-form AnnotationAttribution; TASK-28 moved the engine to core so both execution forms share it). This is the coverage the
 * /code-review high finding 7 demanded: the rules engine shipped with zero behavioral coverage
 * (the adjacent structural test never called {@code apply()}, never built an index with
 * {@code @Path} or {@code @RegisterRestClient}), which is how two regressions from the skeptic
 * fixes shipped undetected (the dead {@code @RegisterRestClient} rule and the
 * {@code RestResponse<Pojo>} false negative).
 *
 * <p>Each test generates .class bytes with ASM for the exact app shapes the rules recognize, feeds
 * them through a real Jandex {@link Indexer}, and calls {@code apply()} end-to-end with a
 * declared-GA set and a report with SUSPECT rows. The generated classes carry the
 * REAL framework FQCNs ({@code jakarta.ws.rs.Path}, {@code io.smallrye.mutiny.Uni}, ...) because
 * {@code annotationFamilyPresent} probes exact names; stand-in names would silently match nothing.
 * Generic returns ({@code Uni<Pojo>}) are emitted as descriptor + generic signature, since Java
 * descriptors cannot carry type arguments. Fixture method bodies are RETURN-only stubs that would
 * fail the JVM verifier if loaded; Jandex reads no Code attribute, so the fixtures must never be
 * reused anywhere that loads classes.
 */
class AnnotationConsumerRulesBehaviorTest {

    // Real GAs the rules table targets (so the tests exercise the production rule entries).
    private static final String REST_JACKSON = "io.quarkus:quarkus-rest-jackson";
    private static final String RESTEASY_JACKSON = "io.quarkus:quarkus-resteasy-jackson";
    private static final String RESTEASY = "io.quarkus:quarkus-resteasy";
    private static final String QUARKUS_REST = "io.quarkus:quarkus-rest";
    private static final String RESTEASY_CLIENT_JACKSON = "io.quarkus:quarkus-resteasy-client-jackson";
    private static final String HIBERNATE_VALIDATOR = "io.quarkus:quarkus-hibernate-validator";
    private static final String SMALLRYE_JWT = "io.quarkus:quarkus-smallrye-jwt";
    private static final String SCHEDULER = "io.quarkus:quarkus-scheduler";
    private static final String FAULT_TOLERANCE = "io.quarkus:quarkus-smallrye-fault-tolerance";
    private static final String OPENAPI = "io.quarkus:quarkus-smallrye-openapi";
    private static final String REST_QUTE = "io.quarkus:quarkus-rest-qute";
    private static final String MONGODB_PANACHE = "io.quarkus:quarkus-mongodb-panache";
    private static final String CONFIG_YAML = "io.quarkus:quarkus-config-yaml";
    private static final String REACTIVE_PG = "io.quarkus:quarkus-reactive-pg-client";
    private static final String REACTIVE_MYSQL = "io.quarkus:quarkus-reactive-mysql-client";
    private static final String HIBERNATE_REACTIVE = "io.quarkus:quarkus-hibernate-reactive";
    private static final String HIBERNATE_REACTIVE_PANACHE = "io.quarkus:quarkus-hibernate-reactive-panache";

    // Real framework FQCNs (the probes match exact names; stand-ins would not fire). These
    // literals are DELIBERATELY independent of any constant in AnnotationAttribution: sharing
    // them would make the fixtures generate annotations under the same phantom name a broken
    // probe looks for, and the suite would pass while production stays dead against real apps
    // (exactly the restclient bug this suite caught).
    private static final String PATH_ANN = "jakarta.ws.rs.Path";
    private static final String GET_ANN = "jakarta.ws.rs.GET";
    private static final String REGISTER_REST_CLIENT_ANN =
            "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
    private static final String NOT_NULL_ANN = "jakarta.validation.constraints.NotNull";
    private static final String SCHEDULED_ANN = "io.quarkus.scheduler.Scheduled";
    private static final String JWT_TYPE = "org.eclipse.microprofile.jwt.JsonWebToken";
    private static final String POJO = "com.acme.Pojo";
    private static final String UNI = "io.smallrye.mutiny.Uni";
    private static final String MULTI = "io.smallrye.mutiny.Multi";
    private static final String REST_RESPONSE = "org.jboss.resteasy.reactive.RestResponse";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String RESPONSE = "jakarta.ws.rs.core.Response";
    private static final String TEMPLATE_INSTANCE = "io.quarkus.qute.TemplateInstance";
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";
    private static final String VALID_ANN = "jakarta.validation.Valid";
    private static final String VALIDATE_ON_EXECUTION_ANN = "jakarta.validation.executable.ValidateOnExecution";
    private static final String FALLBACK_ANN = "org.eclipse.microprofile.faulttolerance.Fallback";
    private static final String APPLY_GUARD_ANN = "io.smallrye.faulttolerance.api.ApplyGuard";
    private static final String OPERATION_ANN = "org.eclipse.microprofile.openapi.annotations.Operation";
    private static final String CHECKED_TEMPLATE_ANN = "io.quarkus.qute.CheckedTemplate";
    private static final String PANACHE_MONGO_ENTITY = "io.quarkus.mongodb.panache.PanacheMongoEntity";

    /** A project root that never exists (non-FILE tests stay hermetic: the FILE: prefixes are in
     *  RULES, so apply() probes the filesystem on every call). */
    private static final Path NOWHERE = Path.of("no-such-project-root");

    // --- bytecode fixtures (ASM) -------------------------------------------------------------------

    private static String internal(String fqcn) {
        return fqcn.replace('.', '/');
    }

    /** Annotation-type stub .class bytes (RUNTIME retention, no members). */
    private static byte[] annotationClass(String fqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION
                        | Opcodes.ACC_INTERFACE,
                internal(fqcn), null, "java/lang/Object", new String[]{"java/lang/Annotation"});
        var retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
        retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
        retention.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClass(String fqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A method return: erasure descriptor plus the generic signature when parameterized. */
    private record Ret(String descriptor, String signature) {
        static Ret of(String wrapperFqcn, String argFqcn) {
            return new Ret("()L" + internal(wrapperFqcn) + ";",
                    "()L" + internal(wrapperFqcn) + "<L" + internal(argFqcn) + ";>;");
        }
    }

    /** Raw (non-parameterized) object return helper: the descriptor form of {@code ()LFoo;}. */
    private static Ret rawRet(String fqcn) {
        return new Ret("()L" + internal(fqcn) + ";", null);
    }

    /** Nested generic return {@code Outer<Inner<Arg>>} (e.g. {@code Uni<RestResponse<Pojo>>}). */
    private static Ret nestedRet(String outerFqcn, String innerFqcn, String argFqcn) {
        return new Ret("()L" + internal(outerFqcn) + ";",
                "()L" + internal(outerFqcn) + "<L" + internal(innerFqcn)
                        + "<L" + internal(argFqcn) + ";>;>;");
    }

    /** A plain class carrying one class-level annotation (the family-presence probes). */
    private static byte[] annotatedClass(String fqcn, String annotationFqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", null);
        cw.visitAnnotation("L" + internal(annotationFqcn) + ";", true).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A plain subclass of the given FQCN (the panache superclass probe). */
    private static byte[] subclassOf(String fqcn, String superFqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, internal(superFqcn), null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A @Path-annotated class with one @GET method per given return type. */
    private static byte[] pathResource(String fqcn, Ret... returns) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", null);
        cw.visitAnnotation("L" + internal(PATH_ANN) + ";", true).visitEnd();
        addGetMethods(cw, returns);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An interface whose methods carry method-level @Path + @GET (a real REST resource shape). */
    private static byte[] pathInterface(String fqcn, Ret... returns) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
                internal(fqcn), null, "java/lang/Object", null);
        for (int i = 0; i < returns.length; i++) {
            var mv = cw.visitMethod(Opcodes.ACC_PUBLIC
                    | Opcodes.ACC_ABSTRACT, "m" + i,
                    returns[i].descriptor(), returns[i].signature(), null);
            mv.visitAnnotation("L" + internal(PATH_ANN) + ";", true).visitEnd();
            mv.visitAnnotation("L" + internal(GET_ANN) + ";", true).visitEnd();
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A base class with @GET endpoint methods but no @Path (the subclass carries it). */
    private static byte[] endpointBase(String fqcn, Ret... returns) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", null);
        addGetMethods(cw, returns);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An interface declaring @GET endpoint methods (no @Path anywhere on it). */
    private static byte[] getInterface(String fqcn, Ret... returns) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
                internal(fqcn), null, "java/lang/Object", null);
        for (int i = 0; i < returns.length; i++) {
            var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m" + i,
                    returns[i].descriptor(), returns[i].signature(), null);
            mv.visitAnnotation("L" + internal(GET_ANN) + ";", true).visitEnd();
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A @Path class implementing the given interface (endpoints inherited from the interface). */
    private static byte[] pathResourceImplementing(String fqcn, String itfFqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", new String[]{internal(itfFqcn)});
        cw.visitAnnotation("L" + internal(PATH_ANN) + ";", true).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A @Path-annotated subclass of the given base (endpoints inherited from the base). */
    private static byte[] pathSubclass(String fqcn, String baseFqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, internal(baseFqcn), null);
        cw.visitAnnotation("L" + internal(PATH_ANN) + ";", true).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addGetMethods(ClassWriter cw, Ret... returns) {
        for (int i = 0; i < returns.length; i++) {
            var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m" + i,
                    returns[i].descriptor(), returns[i].signature(), null);
            mv.visitAnnotation("L" + internal(GET_ANN) + ";", true).visitEnd();
            mv.visitCode();
            mv.visitMaxs(1, 1);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitEnd();
        }
    }

    /** A @RegisterRestClient-annotated interface (MicroProfile REST client shape). */
    private static byte[] restClientInterface(String fqcn) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
                internal(fqcn), null, "java/lang/Object", null);
        cw.visitAnnotation("L" + internal(REGISTER_REST_CLIENT_ANN) + ";", true).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with a (optionally annotated) field of the given type (the JWT/validation probes). */
    private static byte[] classWithAnnotatedField(String fqcn, String fieldFqcn,
            String... annotationFqcns) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", null);
        var fv = cw.visitField(Opcodes.ACC_PUBLIC, "f",
                "L" + internal(fieldFqcn) + ";", null, null);
        for (String ann : annotationFqcns) {
            fv.visitAnnotation("L" + internal(ann) + ";", true).visitEnd();
        }
        fv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with a generic field {@code Wrapper<Arg>} (descriptor + signature, the CDI
     *  {@code Instance<JsonWebToken>} shape). */
    private static byte[] genericFieldClass(String fqcn, String wrapperFqcn, String argFqcn) {
        return nestedGenericFieldClass(fqcn, wrapperFqcn, null, argFqcn);
    }

    /** A class with a generic field {@code Outer<Arg>} or {@code Outer<Inner<Arg>>}
     *  (innerFqcn null for one level). */
    private static byte[] nestedGenericFieldClass(String fqcn, String outerFqcn, String innerFqcn,
            String argFqcn) {
        String typeArg = innerFqcn == null
                ? "L" + internal(argFqcn) + ";"
                : "L" + internal(innerFqcn) + "<L" + internal(argFqcn) + ";>;";
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                internal(fqcn), null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f",
                "L" + internal(outerFqcn) + ";",
                "L" + internal(outerFqcn) + "<" + typeArg + ">;", null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The framework stubs every index carries (the FQCNs the probes look for). */
    private static final byte[][] STUBS = {
            annotationClass(PATH_ANN), annotationClass(GET_ANN),
            annotationClass(REGISTER_REST_CLIENT_ANN), annotationClass(NOT_NULL_ANN),
            annotationClass(SCHEDULED_ANN), annotationClass(VALID_ANN),
            annotationClass(VALIDATE_ON_EXECUTION_ANN), annotationClass(FALLBACK_ANN),
            annotationClass(APPLY_GUARD_ANN), annotationClass(OPERATION_ANN),
            annotationClass(CHECKED_TEMPLATE_ANN),
            plainClass(POJO), plainClass(UNI), plainClass(MULTI),
            plainClass(REST_RESPONSE), plainClass(JWT_TYPE)};

    /** The stub-only index (identical for every test that passes no app classes). */
    private static final Index STUB_ONLY_INDEX = stubOnlyIndex();

    private static Index stubOnlyIndex() {
        try {
            return index();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Indexes the framework stubs plus the given app classes. */
    private static Index index(byte[]... appClasses) throws IOException {
        Indexer indexer = new Indexer();
        for (byte[] b : STUBS) {
            indexer.index(new ByteArrayInputStream(b));
        }
        for (byte[] b : appClasses) {
            indexer.index(new ByteArrayInputStream(b));
        }
        return indexer.complete();
    }

    // --- report factory -----------------------------------------------------------------------------

    private static ExtensionReport suspect(String ga) {
        return row(ga, Verdict.SUSPECT);
    }

    /** A plain-jar (non-extension) suspect row: never a rule target, but summary-counted. */
    private static ExtensionReport plainJarSuspect(String ga) {
        return row(ga, false, Verdict.SUSPECT, List.of(), List.of());
    }

    private static ExtensionReport row(String ga, Verdict verdict) {
        return row(ga, true, verdict, List.of(), List.of());
    }

    /** Full-control row factory (the single site that spells out the 15 record components). */
    private static ExtensionReport row(String ga, boolean quarkusExtension, Verdict verdict,
            List<ExtensionReport.SharedReferencedJar> sharedReferencedJars,
            List<String> vocabularyEvidence) {
        return new ExtensionReport(ga, quarkusExtension, verdict, false, Set.of(), List.of(),
                Set.of(), List.of(), false, List.of(), null, null, null, sharedReferencedJars,
                vocabularyEvidence);
    }

    private static AnalysisReport report(ExtensionReport... rows) {
        AnalysisReport.Summary ext = AnalysisReport.Summary.of(List.of(rows));
        return new AnalysisReport("test:app:1", "now", List.of(rows), List.of(), ext,
                AnalysisReport.Summary.of(List.of()), AnalysisReport.Summary.combine(ext,
                        AnalysisReport.Summary.of(List.of())));
    }

    /** The row for {@code ga} in an already-applied report. */
    private static ExtensionReport rowOf(AnalysisReport out, String ga) {
        return out.dependencies().stream().filter(r -> r.ga().equals(ga)).findFirst().orElseThrow();
    }

    /** The row for {@code ga} after running apply() over a single-suspect report for it. */
    private static ExtensionReport applied(Index index, String ga) {
        return rowOf(AnnotationConsumerRules.apply(report(suspect(ga)), index, Set.of(ga), Set.of(), NOWHERE, java.util.Map.of()), ga);
    }

    /** Single-suspect shorthand: {@code ga} is declared, suspected, and must flip to used. */
    private static void assertFlipped(Index index, String ga) {
        assertThat(applied(index, ga).verdict()).isEqualTo(Verdict.USED_BYTECODE);
    }

    /** Single-suspect shorthand: {@code ga} is declared, suspected, and must stay suspect. */
    private static void assertStillSuspect(Index index, String ga) {
        assertThat(applied(index, ga).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    // --- REST-SERIALIZER rule (TASK-21): POJO-returning endpoints credit the serializer -----------

    @Test
    void pojoEndpointCreditsRestJackson() throws IOException {
        Index idx = index(pathResource("res.A", rawRet(POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void stringOnlyEndpointsLeaveRestJacksonSuspect() throws IOException {
        Index idx = index(pathResource("res.B", rawRet("java.lang.String")));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void voidEndpointsLeaveRestJacksonSuspect() throws IOException {
        Index idx = index(pathResource("res.B2", new Ret("()V", null)));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void uniOfVoidLeavesRestJacksonSuspect() throws IOException {
        // Regression: Uni<Void> and Uni<Response> must NOT credit the serializer, but Uni<Pojo>
        // must (the raw name alone cannot discriminate; the unwrap checks the type argument).
        Index idx = index(pathResource("res.C", Ret.of(UNI, "java.lang.Void")));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void uniOfPojoCreditsRestJackson() throws IOException {
        Index idx = index(pathResource("res.D", Ret.of(UNI, POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void multiOfVoidLeavesRestJacksonSuspect() throws IOException {
        Index idx = index(pathResource("res.E", Ret.of(MULTI, "java.lang.Void")));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void optionalOfPojoCreditsRestJackson() throws IOException {
        Index idx = index(pathResource("res.E2", Ret.of(OPTIONAL, POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void restResponseOfPojoCreditsRestJackson() throws IOException {
        // Regression (code-review finding 4): RestResponse<Pojo> carries a POJO payload, so the
        // serializer IS needed despite the HTTP-machinery raw name.
        Index idx = index(pathResource("res.F", Ret.of(REST_RESPONSE, POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void restResponseOfStringLeavesRestJacksonSuspect() throws IOException {
        // Regression (the phantom-FQCN fix): RestResponse<String> is HTTP machinery all the way
        // down, so no serializer credit.
        Index idx = index(pathResource("res.G", Ret.of(REST_RESPONSE, "java.lang.String")));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void methodLevelPathOnInterfaceCreditsSerializer() throws IOException {
        // The interface-resource shape Quarkus registers (skeptic finding 3: method-level @Path)
        Index idx = index(pathInterface("res.H", rawRet(POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void inheritedRestMethodCreditsSerializer() throws IOException {
        // Base has the @GET endpoint; subclass carries the @Path (code-review finding 3)
        Index idx = index(endpointBase("res.Base", rawRet(POJO)), pathSubclass("res.Sub", "res.Base"));
        assertFlipped(idx, REST_JACKSON);
    }

    // --- @RegisterRestClient rule -------------------------------------------------------------------

    @Test
    void registerRestClientCreditsClientSerializer() throws IOException {
        Index idx = index(restClientInterface("cli.A"));
        assertFlipped(idx, RESTEASY_CLIENT_JACKSON);
    }

    @Test
    void serverPathDoesNotCreditClientSerializer() throws IOException {
        // The bug the skeptic found: server @Path must NOT credit the client serializer (the two
        // have no overlapping capability, so crediting the client from server evidence was wrong).
        Index idx = index(pathResource("res.I", rawRet(POJO)));
        assertStillSuspect(idx, RESTEASY_CLIENT_JACKSON);
    }

    // --- jakarta.ws.rs family rules -----------------------------------------------------------------

    @Test
    void pathCreditsResteasyFamilyTogether() throws IOException {
        Index idx = index(pathResource("res.J", rawRet(POJO)));
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(suspect(QUARKUS_REST), suspect(RESTEASY), suspect(RESTEASY_JACKSON)),
                idx, Set.of(QUARKUS_REST, RESTEASY, RESTEASY_JACKSON), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, QUARKUS_REST).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, RESTEASY).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, RESTEASY_JACKSON).verdict()).isEqualTo(Verdict.USED_BYTECODE);
    }

    @Test
    void undeclaredExtensionIsNeverCredited() throws IOException {
        // The guard: a rule fires only when its target GA is a declared extension, so a report
        // row for an extension that is not declared (empty declared set here) must stay suspect.
        Index idx = index(pathResource("res.K", rawRet(POJO)));
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(REST_JACKSON)), idx,
                Set.of(), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REST_JACKSON).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void noAnnotationsNoJoinReturnsReportUnchanged() throws IOException {
        // Identity path: nothing fired, apply() returns the same instance untouched.
        AnalysisReport in = report(suspect(REST_JACKSON));
        assertThat(AnnotationConsumerRules.apply(in, STUB_ONLY_INDEX, Set.of(REST_JACKSON), Set.of(), NOWHERE, java.util.Map.of()))
                .isSameAs(in);
    }

    // --- validation / JWT probes ----------------------------------------------------------------------

    @Test
    void notNullFieldCreditsHibernateValidator() throws IOException {
        Index idx = index(classWithAnnotatedField("res.L", "java.lang.String", NOT_NULL_ANN));
        assertFlipped(idx, HIBERNATE_VALIDATOR);
    }

    @Test
    void exactJwtTypeCreditsSmallryeJwt() throws IOException {
        Index idx = index(classWithAnnotatedField("res.M", JWT_TYPE));
        assertFlipped(idx, SMALLRYE_JWT);
    }

    @Test
    void similarlyNamedUserTypeDoesNotCreditSmallryeJwt() throws IOException {
        // Regression (code-review finding 5): a user type whose FQCN merely CONTAINS
        // "JsonWebToken" must not fire the exact-FQCN probe.
        Index idx = index(classWithAnnotatedField("res.N", "com.acme.JsonWebTokenWrapper"),
                plainClass("com.acme.JsonWebTokenWrapper"));
        assertStillSuspect(idx, SMALLRYE_JWT);
    }

    @Test
    void cdiInstanceWrappedJwtCreditsSmallryeJwt() throws IOException {
        // The Apicurio bench shape: @Inject Instance<JsonWebToken>. The raw field type is the
        // Instance interface, so the probe must unwrap the type argument (found as a real false
        // negative while re-establishing the Apicurio bench, TASK-31).
        Index idx = index(
                genericFieldClass("res.O", "jakarta.enterprise.inject.Instance", JWT_TYPE));
        assertFlipped(idx, SMALLRYE_JWT);
    }

    @Test
    void instanceOfAnUnrelatedTypeDoesNotCreditSmallryeJwt() throws IOException {
        // Instance<String> (or any non-JWT argument) must not fire: the unwrap is exact-FQCN
        // on the argument, not a contains().
        Index idx = index(
                genericFieldClass("res.P", "jakarta.enterprise.inject.Instance", "java.lang.String"));
        assertStillSuspect(idx, SMALLRYE_JWT);
    }

    @Test
    void instanceOfASimilarlyNamedUserTypeDoesNotCreditSmallryeJwt() throws IOException {
        // The unwrap must stay exact-FQCN on the TYPE ARGUMENT too: a contains() regression
        // would fire on Instance<com.acme.JsonWebTokenWrapper> the way the original raw-type
        // probe did (code-review finding 5).
        Index idx = index(
                genericFieldClass("res.Q", "jakarta.enterprise.inject.Instance",
                        "com.acme.JsonWebTokenWrapper"),
                plainClass("com.acme.JsonWebTokenWrapper"));
        assertStillSuspect(idx, SMALLRYE_JWT);
    }

    // --- deployment-consumer credits (TASK-38) --------------------------------------------------------

    @Test
    void deploymentConsumerCreditsTheSuspect() {
        // The Keycloak shape, empirically REVERSED from note-enrichment by the ablation: the
        // Quarkus extension descriptor REFUSES to build when a -deployment artifact's runtime
        // counterpart is not declared, so the declaration is required -> used-bytecode.
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(REST_JACKSON)),
                STUB_ONLY_INDEX, Set.of(REST_JACKSON), Set.of(), NOWHERE,
                java.util.Map.of(REST_JACKSON, "org.keycloak:keycloak-quarkus-server"));
        assertThat(rowOf(out, REST_JACKSON).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, REST_JACKSON).note()).contains("deployment-consumer")
                .contains("org.keycloak:keycloak-quarkus-server");
    }

    @Test
    void deploymentConsumerLeavesNonSuspectRowsUntouched() {
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(REST_JACKSON, Verdict.USED_CONFIG)),
                STUB_ONLY_INDEX, Set.of(REST_JACKSON), Set.of(), NOWHERE,
                java.util.Map.of(REST_JACKSON, "org.keycloak:keycloak-quarkus-server"));
        assertThat(rowOf(out, REST_JACKSON).note()).isNull();
    }

    @Test
    void emptyDeploymentConsumersMapChangesNothing() {
        AnalysisReport in = report(suspect(REST_JACKSON));
        AnalysisReport out = AnnotationConsumerRules.apply(in, STUB_ONLY_INDEX,
                Set.of(REST_JACKSON), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(out).isSameAs(in);
    }

    // --- near-miss telemetry (TASK-32) -----------------------------------------------------------------

    @Test
    void nestedJwtWrappingStaysSuspectButReportsANearMiss() throws IOException {
        // Provider<Instance<JsonWebToken>>: the strict probe unwraps one level only, so no
        // credit - but the row's note must self-report the almost-evidence instead of staying
        // silent (the runtime detector for the shape-blindness bug class).
        Index idx = index(nestedGenericFieldClass("res.R",
                "jakarta.enterprise.inject.Provider", "jakarta.enterprise.inject.Instance", JWT_TYPE));
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(SMALLRYE_JWT)),
                idx, Set.of(SMALLRYE_JWT), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, SMALLRYE_JWT).verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(rowOf(out, SMALLRYE_JWT).note()).contains("near-miss (diagnostic)");
        assertThat(out.extensions().suspect()).isEqualTo(1);
    }

    @Test
    void creditedJwtShapesReportNoNearMiss() throws IOException {
        // When the strict probe fires the loose probe is not consulted: no diagnostic noise.
        Index idx = index(
                genericFieldClass("res.S", "jakarta.enterprise.inject.Instance", JWT_TYPE));
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(SMALLRYE_JWT)),
                idx, Set.of(SMALLRYE_JWT), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, SMALLRYE_JWT).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, SMALLRYE_JWT).note()).doesNotContain("near-miss");
    }

    @Test
    void absentJwtEvidenceReportsNoNearMiss() throws IOException {
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(SMALLRYE_JWT)),
                STUB_ONLY_INDEX, Set.of(SMALLRYE_JWT), Set.of(), NOWHERE, java.util.Map.of());
        String note = rowOf(out, SMALLRYE_JWT).note();
        assertThat(note == null || !note.contains("near-miss")).isTrue();
    }

    // --- reactive-driver join (TASK-22/23) --------------------------------------------------------------

    @Test
    void singleDeclaredReactiveClientWithHibernateReactiveUsedIsCredited() throws IOException {
        Index idx = STUB_ONLY_INDEX;
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE_PANACHE, Verdict.USED_BYTECODE), suspect(REACTIVE_PG)),
                idx, Set.of(REACTIVE_PG), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.USED_BYTECODE);
    }

    @Test
    void hibernateReactiveAbsentLeavesReactiveClientSuspect() throws IOException {
        // The join fires only when hibernate-reactive is USED; without it there is no evidence
        // the reactive client is load-bearing.
        Index idx = STUB_ONLY_INDEX;
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(REACTIVE_PG)),
                idx, Set.of(REACTIVE_PG), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void multipleReactiveClientsWithDbKindCreditOnlyTheMatch() throws IOException {
        Index idx = STUB_ONLY_INDEX;
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE_PANACHE, Verdict.USED_BYTECODE),
                        suspect(REACTIVE_PG), suspect(REACTIVE_MYSQL)),
                idx, Set.of(REACTIVE_PG, REACTIVE_MYSQL), Set.of("postgresql"), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, REACTIVE_MYSQL).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void multipleReactiveClientsWithNoDbKindStaySuspect() throws IOException {
        // Ambiguity must not manufacture a verdict (TASK-23).
        Index idx = STUB_ONLY_INDEX;
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE_PANACHE, Verdict.USED_BYTECODE),
                        suspect(REACTIVE_PG), suspect(REACTIVE_MYSQL)),
                idx, Set.of(REACTIVE_PG, REACTIVE_MYSQL), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(rowOf(out, REACTIVE_MYSQL).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    // --- flipSuspects contract (skeptic finding 6) --------------------------------------------------------

    @Test
    void flippedRowClearsSharedJarsAndCarriesTheEvidenceInTheNote() throws IOException {
        Index idx = index(pathResource("res.O", rawRet(POJO)));
        ExtensionReport suspectWithHints = row(REST_JACKSON, true, Verdict.SUSPECT,
                List.of(new ExtensionReport.SharedReferencedJar(
                        "jakarta.validation:jakarta.validation-api",
                        List.of("io.quarkus:quarkus-hibernate-validator"))),
                List.of("io.lib.Marker"));
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspectWithHints), idx,
                Set.of(REST_JACKSON), Set.of(), NOWHERE, java.util.Map.of());
        ExtensionReport flipped = out.dependencies().get(0);
        assertThat(flipped.verdict()).isEqualTo(Verdict.USED_BYTECODE);
        // Shared-jar hints are suspect-row-only evidence and must not survive the flip.
        assertThat(flipped.sharedReferencedJars()).isEmpty();
        // The vocabulary evidence is independent signal output and is preserved as-is.
        assertThat(flipped.vocabularyEvidence()).containsExactly("io.lib.Marker");
        assertThat(flipped.note()).startsWith("resolved by annotation-consumer signal:");
        assertThat(flipped.bytecodeReferenced()).isTrue();
    }

    @Test
    void summariesAreRecomputedAfterTheFlip() throws IOException {
        Index idx = index(pathResource("res.P", rawRet(POJO)));
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(suspect(REST_JACKSON), suspect(HIBERNATE_VALIDATOR)),
                idx, Set.of(REST_JACKSON, HIBERNATE_VALIDATOR), Set.of(), NOWHERE, java.util.Map.of());
        // Exactly one row flips: the serializer (via REST-SERIALIZER). The validator stays
        // suspect (the index has no validation annotation), and the recomputed summaries must
        // reflect that split.
        assertThat(out.extensions().suspect()).isEqualTo(1);
        assertThat(out.extensions().usedBytecode()).isEqualTo(1);
        assertThat(out.summary().suspect()).isEqualTo(1);
    }

    @Test
    void alreadyUsedRowIsNotTouchedByTheFlip() throws IOException {
        // The other half of the flipSuspects contract: a row already resolved by another signal
        // (here USED_CONFIG) must not be rebuilt to USED_BYTECODE even though its GA is in the
        // credits map (both the jakarta.ws.rs and REST-SERIALIZER rules target resteasy-jackson).
        Index idx = index(pathResource("res.Q", rawRet(POJO)));
        ExtensionReport alreadyUsed = new ExtensionReport(RESTEASY_JACKSON, true,
                Verdict.USED_CONFIG, false, Set.of("jakarta.persistence.jdbc.url"),
                List.of("jakarta.persistence.jdbc.url"), Set.of(), List.of(), false, List.of(),
                "config signal", null, null, List.of(), List.of());
        AnalysisReport out = AnnotationConsumerRules.apply(report(alreadyUsed), idx,
                Set.of(RESTEASY_JACKSON), Set.of(), NOWHERE, java.util.Map.of());
        ExtensionReport untouched = rowOf(out, RESTEASY_JACKSON);
        assertThat(untouched.verdict()).isEqualTo(Verdict.USED_CONFIG);
        assertThat(untouched.note()).isEqualTo("config signal");
        assertThat(untouched.configMatchedKeys()).containsExactly("jakarta.persistence.jdbc.url");
    }

    @Test
    void plainJarRowsPartitionIntoTheirOwnSummary() throws IOException {
        // flipSuspects partitions rows by quarkusExtension and rebuilds both summaries: a plain
        // jar row must never receive a credit but must survive the rebuild counted as suspect.
        Index idx = index(pathResource("res.R", rawRet(POJO)));
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(suspect(REST_JACKSON), plainJarSuspect("com.acme:plain-lib")),
                idx, Set.of(REST_JACKSON), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, "com.acme:plain-lib").verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(out.extensions().usedBytecode()).isEqualTo(1);
        assertThat(out.plainJars().suspect()).isEqualTo(1);
        assertThat(out.summary().suspect()).isEqualTo(1);
    }

    // --- REST-SERIALIZER exclusions (mutation-verified gaps) --------------------------------------

    @Test
    void rawResponseReturnLeavesRestJacksonSuspect() throws IOException {
        Index idx = index(pathResource("res.S", rawRet(RESPONSE)));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void uniOfResponseLeavesRestJacksonSuspect() throws IOException {
        // The Uni<Response> half of the skeptic-finding-2 regression (the comment claimed it,
        // the suite had not pinned it).
        Index idx = index(pathResource("res.T", Ret.of(UNI, RESPONSE)));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void templateInstanceReturnLeavesRestJacksonSuspect() throws IOException {
        // Qute renders HTML, not JSON: a TemplateInstance endpoint needs no Jackson serializer.
        Index idx = index(pathResource("res.U", rawRet(TEMPLATE_INSTANCE)));
        assertStillSuspect(idx, REST_JACKSON);
    }

    @Test
    void completionStageOfPojoCreditsRestJackson() throws IOException {
        Index idx = index(pathResource("res.V", Ret.of(COMPLETION_STAGE, POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void listOfPojoCreditsRestJackson() throws IOException {
        // The conservative container branch: List<Pojo> elements need serialization.
        Index idx = index(pathResource("res.W", Ret.of("java.util.List", POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void uniOfRestResponseOfPojoCreditsRestJackson() throws IOException {
        // Recursion depth 2, the shape the unwrap javadoc calls out explicitly.
        Index idx = index(pathResource("res.X", nestedRet(UNI, REST_RESPONSE, POJO)));
        assertFlipped(idx, REST_JACKSON);
    }

    @Test
    void endpointMethodsOnImplementedInterfaceCreditSerializer() throws IOException {
        // The classHierarchy interface walk: @Path on the class, @GET methods declared only on
        // the interface it implements (a common real Quarkus resource shape).
        Index idx = index(getInterface("res.Itf", rawRet(POJO)),
                pathResourceImplementing("res.Impl", "res.Itf"));
        assertFlipped(idx, REST_JACKSON);
    }

    // --- annotation-family probes (mutation-verified gaps) ---------------------------------------

    @Test
    void scheduledAnnotationCreditsScheduler() throws IOException {
        Index idx = index(annotatedClass("job.A", SCHEDULED_ANN));
        assertFlipped(idx, SCHEDULER);
    }

    @Test
    void validAnnotationCreditsHibernateValidator() throws IOException {
        // The jakarta.validation.Valid branch (itself a past FQCN regression: the original probe
        // looked for the nonexistent constraints.Valid).
        Index idx = index(classWithAnnotatedField("res.Y", "java.lang.String", VALID_ANN));
        assertFlipped(idx, HIBERNATE_VALIDATOR);
    }

    @Test
    void validateOnExecutionCreditsHibernateValidator() throws IOException {
        Index idx = index(annotatedClass("res.Z", VALIDATE_ON_EXECUTION_ANN));
        assertFlipped(idx, HIBERNATE_VALIDATOR);
    }

    @Test
    void mpFallbackAnnotationCreditsFaultTolerance() throws IOException {
        Index idx = index(annotatedClass("svc.A", FALLBACK_ANN));
        assertFlipped(idx, FAULT_TOLERANCE);
    }

    @Test
    void smallryeApplyGuardCreditsFaultTolerance() throws IOException {
        // Pins the phantom-probe fix: io.smallrye.faulttolerance.api.ApplyGuard is a real
        // annotation (verified against smallrye-fault-tolerance-api 6.10.1); the original probe
        // names (api.Async, api.ApplyProfile) exist in no jar.
        Index idx = index(annotatedClass("svc.B", APPLY_GUARD_ANN));
        assertFlipped(idx, FAULT_TOLERANCE);
    }

    @Test
    void openapiOperationCreditsSmallryeOpenapi() throws IOException {
        Index idx = index(annotatedClass("api.A", OPERATION_ANN));
        assertFlipped(idx, OPENAPI);
    }

    @Test
    void checkedTemplateCreditsRestQute() throws IOException {
        Index idx = index(annotatedClass("web.A", CHECKED_TEMPLATE_ANN));
        assertFlipped(idx, REST_QUTE);
    }

    @Test
    void panacheSuperclassCreditsMongodbPanache() throws IOException {
        Index idx = index(subclassOf("model.A", PANACHE_MONGO_ENTITY));
        assertFlipped(idx, MONGODB_PANACHE);
    }

    // --- reactive-driver join residual branches ---------------------------------------------------

    @Test
    void leftoverReactiveSuspectIsNotCreditedWithoutDbKind() throws IOException {
        // Pins the single-DECLARED (not single-suspect) guard: with two clients declared and the
        // sibling already credited by another signal, the leftover suspect stays suspect absent
        // an explicit db-kind (crediting it would mark dead weight as load-bearing).
        Index idx = index();
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE_PANACHE, Verdict.USED_BYTECODE),
                        row(REACTIVE_MYSQL, Verdict.USED_BYTECODE), suspect(REACTIVE_PG)),
                idx, Set.of(REACTIVE_PG, REACTIVE_MYSQL), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void dbKindMatchingIsCaseInsensitive() throws IOException {
        Index idx = index();
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE_PANACHE, Verdict.USED_BYTECODE),
                        suspect(REACTIVE_PG), suspect(REACTIVE_MYSQL)),
                idx, Set.of(REACTIVE_PG, REACTIVE_MYSQL), Set.of("PostgreSQL"), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, REACTIVE_MYSQL).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void nonPanacheHibernateReactiveAlsoDrivesTheJoin() throws IOException {
        Index idx = index();
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE, Verdict.USED_BYTECODE), suspect(REACTIVE_PG)),
                idx, Set.of(REACTIVE_PG), Set.of(), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.USED_BYTECODE);
    }

    @Test
    void mariadbDbKindCreditsTheMysqlClient() throws IOException {
        // There is no mariadb reactive artifact: MariaDB apps run the mysql client with
        // db-kind=mariadb, so that kind must select it (the phantom-GA fix).
        Index idx = index();
        AnalysisReport out = AnnotationConsumerRules.apply(
                report(row(HIBERNATE_REACTIVE_PANACHE, Verdict.USED_BYTECODE),
                        suspect(REACTIVE_PG), suspect(REACTIVE_MYSQL)),
                idx, Set.of(REACTIVE_PG, REACTIVE_MYSQL), Set.of("mariadb"), NOWHERE, java.util.Map.of());
        assertThat(rowOf(out, REACTIVE_MYSQL).verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, REACTIVE_PG).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    // --- FILE: rules (TASK-24: resolved under the passed project root, not the CWD) ---------------

    /** Fixture-path helper with LITERAL layout, deliberately independent of production's
     *  MavenLayout: sharing it would let a typo'd convention create and probe the same wrong
     *  path, passing vacuously (the phantom-FQCN lesson applied to paths). */
    private static Path resources(String name, Path root) {
        return root.resolve(Path.of("src", "main", "resources", name));
    }

    private static Path classes(String name, Path root) {
        return root.resolve(Path.of("target", "classes", name));
    }

    @Test
    void yamlInProjectRootResourcesCreditsConfigYaml(@TempDir Path moduleRoot) throws IOException {
        Files.createDirectories(resources("application.yml", moduleRoot).getParent());
        Files.writeString(resources("application.yml", moduleRoot), "quarkus: {}\n");
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(CONFIG_YAML)),
                STUB_ONLY_INDEX, Set.of(CONFIG_YAML), Set.of(), moduleRoot, java.util.Map.of());
        assertThat(rowOf(out, CONFIG_YAML).verdict()).isEqualTo(Verdict.USED_BYTECODE);
    }

    @Test
    void yamlInTargetClassesAlsoCreditsConfigYaml(@TempDir Path moduleRoot) throws IOException {
        Files.createDirectories(classes("application.yaml", moduleRoot).getParent());
        Files.writeString(classes("application.yaml", moduleRoot), "quarkus: {}\n");
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(CONFIG_YAML)),
                STUB_ONLY_INDEX, Set.of(CONFIG_YAML), Set.of(), moduleRoot, java.util.Map.of());
        assertThat(rowOf(out, CONFIG_YAML).verdict()).isEqualTo(Verdict.USED_BYTECODE);
    }

    @Test
    void fileRuleResolvesOnlyUnderThePassedRoot(@TempDir Path reactorRoot, @TempDir Path otherModule)
            throws IOException {
        // The yml sits under another module's root (the reactor root, in the TASK-24 scenario);
        // the module being augmented is otherModule, and its verdict must not leak in from the
        // other root. (The strict no-CWD-union semantics are pinned directly by the
        // configFilePresent unit tests below, which do not depend on the surefire CWD.)
        Files.createDirectories(resources("application.yml", reactorRoot).getParent());
        Files.writeString(resources("application.yml", reactorRoot), "quarkus: {}\n");
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(CONFIG_YAML)),
                STUB_ONLY_INDEX, Set.of(CONFIG_YAML), Set.of(), otherModule, java.util.Map.of());
        assertThat(rowOf(out, CONFIG_YAML).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void noYamlAnywhereLeavesConfigYamlSuspect(@TempDir Path moduleRoot) throws IOException {
        AnalysisReport out = AnnotationConsumerRules.apply(report(suspect(CONFIG_YAML)),
                STUB_ONLY_INDEX, Set.of(CONFIG_YAML), Set.of(), moduleRoot, java.util.Map.of());
        assertThat(rowOf(out, CONFIG_YAML).verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void configFilePresentResolvesThePassedRootOnly(@TempDir Path withYml, @TempDir Path without)
            throws IOException {
        // Direct pins of the probe semantics (TASK-24): resolution happens strictly under the
        // passed root, in the two conventional locations, for both file spellings.
        Files.createDirectories(resources("application.yml", withYml).getParent());
        Files.writeString(resources("application.yml", withYml), "q: v\n");
        assertThat(AnnotationConsumerRules.configFilePresent("FILE:application.yml", withYml)).isTrue();
        assertThat(AnnotationConsumerRules.configFilePresent("FILE:application.yml", without)).isFalse();
        assertThat(AnnotationConsumerRules.configFilePresent("FILE:application.yaml", withYml)).isFalse();
        assertThat(AnnotationConsumerRules.configFilePresent("FILE:application.yml", NOWHERE)).isFalse();
    }

    @Test
    void configFilePresentAlsoFindsTargetClassesCopy(@TempDir Path moduleRoot) throws IOException {
        Files.createDirectories(classes("application.yaml", moduleRoot).getParent());
        Files.writeString(classes("application.yaml", moduleRoot), "q: v\n");
        assertThat(AnnotationConsumerRules.configFilePresent("FILE:application.yaml", moduleRoot)).isTrue();
        assertThat(AnnotationConsumerRules.configFilePresent("FILE:application.yml", moduleRoot)).isFalse();
    }
}
