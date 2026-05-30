@file:OptIn(ExperimentalTime::class)

import org.gradle.internal.extensions.stdlib.capitalized
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

plugins {
    alias(libs.plugins.protobuf)
    `java-test-fixtures`
}

/*
 * Per-suite recorder options.  The shared `Dumper` in `src/testFixtures/kotlin/` always
 * compiles the recorded `CodeGeneratorRequest` at whatever options were baked into it by
 * the recorder plugin below, so this map IS the test matrix: one entry per suite, each
 * one isolating a single option from its default.  The `hello` suite passes no options
 * so its fixtures act as a defaults baseline; every other suite flips exactly one knob.
 *
 * Suite name doubles as the proto source directory under `src/<suite>/proto/` and the
 * fixture sink under `src/<suite>/resources/`.
 */
val suiteRecorderOptions =
    mapOf(
        "hello" to emptyList<String>(),
    )

dependencies {
    testFixturesImplementation(projects.protocGenMermaid)
    testFixturesImplementation(libs.protobuf.java)
    testFixturesImplementation(libs.bundles.test.kotest)
}

testing {
    suites {
        /*
         * Each test suite is its own protoc compilation run, so the recorder produces a
         * distinct CodeGeneratorRequest per suite.  The shared block below wires the binpb
         * output of `generate<Suite>Proto` into `process<Suite>Resources`, making the
         * recorded CGR available on each suite's test classpath as `/code-generator-request.binpb`.
         */
        withType<JvmTestSuite> {
            val testSuiteName = this.name

            dependencies {
                implementation(projects.protocGenMermaid)
                /*
                 * The root project's deps are `implementation` scope, so protobuf-java
                 * isn't on consumers' compile classpath despite leaking through the
                 * public API of ProtocGenMermaid (compile() returns PluginProtos.CodeGeneratorResponse).
                 * Pull it in explicitly per suite.
                 */
                implementation(libs.protobuf.java)
                /*
                 * Pulls in the abstract `Dumper` base from testFixtures.  Each suite ships
                 * a tiny concrete subclass under `src/<suite>/kotlin/` so Kotest discovers
                 * the spec and runs the dump once per suite.
                 */
                implementation(testFixtures(project()))
            }

            tasks.named("process${testSuiteName.capitalized()}Resources", ProcessResources::class) {
                dependsOn("generate${testSuiteName.capitalized()}Proto")
                from(
                    project.layout.buildDirectory
                        .dir("generated/sources/proto/$testSuiteName/recorder")
                        .map { it.file("code-generator-request.binpb") },
                )
            }

            /*
             * The Dumper test writes regenerated reference .mermaid files into the suite's
             * resources directory.  Pass the absolute path as a system property so the dumper
             * doesn't have to guess at the test task's working directory.
             */
            targets.all {
                testTask.configure {
                    systemProperty(
                        "dumpDir",
                        layout.projectDirectory.dir("src/$testSuiteName/resources").asFile.absolutePath,
                    )
                }
            }

            tasks.named("check") {
                dependsOn(this@withType)
            }
        }

        /*
         * One JvmTestSuite per entry in `suiteRecorderOptions`.  Adding a new example is
         * just a map entry plus the matching `src/<suite>/proto/` and Dumper subclass.
         */
        suiteRecorderOptions.keys.forEach { suiteName -> register<JvmTestSuite>(suiteName) }
    }
}

protobuf {
    protoc {
        artifact = libs.tools.protoc.compiler.get().toString()
    }
    plugins {
        create("recorder") {
            artifact = libs.tools.protoc.recorder.get().toString()
        }
    }
    generateProtoTasks {
        all().all {
            val suiteName = this.sourceSet.name
            val opts = suiteRecorderOptions[suiteName] ?: return@all
            /*
             * Matches every per-suite generateProto task.  The `main` and `testFixtures`
             * source sets aren't in `suiteRecorderOptions`, so they short-circuit above.
             */
            if (name == "generate${suiteName.capitalized()}Proto") {
                plugins {
                    create("recorder") {
                        option("logLevel=TRACE")
                        option("logFile=${project.layout.buildDirectory.dir("logs/${Clock.System.now().epochSeconds}").map { it.file("${suiteName}.txt") }.get().asFile.absolutePath}")
                        opts.forEach { option(it) }
                    }
                }
            }
        }
    }
}
