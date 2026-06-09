@file:OptIn(ExperimentalTime::class)

import org.gradle.internal.extensions.stdlib.capitalized
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.osdetector)
    `java-test-fixtures`
}

/*
 * protoc-gen-markdown ships only as a POM + per-platform native binaries (no JVM jar) and is
 * not on Maven Central yet; it's published to the local Maven repo as
 * `com.engine:protoc-gen-markdown:0.0.0-pre.0:<os>-<arch>@exe`.  Pull it from there for the
 * end-to-end embedded verification task below.
 */
repositories {
    mavenLocal()
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
        "hideEmptyMembersBox" to listOf("hideEmptyMembersBox=false"),
        "hierarchicalNamespaces" to listOf("hierarchicalNamespaces=true"),
        "suppressNamespaces" to listOf("suppressNamespaces=true"),
        "suppressVisibility" to listOf("suppressVisibility=false"),
        "direction" to listOf("direction=LR"),
        "diagramTypes" to listOf("diagramTypes=COMPLETE"),
        "service" to listOf("diagramTypes=SERVICE"),
        "enumeration" to listOf("diagramTypes=ENUMERATION"),
        "embeddedMarkdown" to listOf("outputType=EMBEDDED_MARKDOWN"),
        "fileOverviewInsertionPoint" to listOf("outputType=EMBEDDED_MARKDOWN", "fileOverviewInsertionPoint=custom_overview_scope"),
        "messageInsertionPoint" to listOf("outputType=EMBEDDED_MARKDOWN", "messageInsertionPoint=custom_message_scope"),
        "serviceInsertionPoint" to listOf("outputType=EMBEDDED_MARKDOWN", "serviceInsertionPoint=custom_service_scope"),
        "enumerationInsertionPoint" to listOf("outputType=EMBEDDED_MARKDOWN", "enumerationInsertionPoint=custom_enum_scope"),
        "completeInsertionPoint" to listOf("outputType=EMBEDDED_MARKDOWN", "completeInsertionPoint=custom_complete"),
        "packageInsertionPoint" to listOf("outputType=EMBEDDED_MARKDOWN", "packageInsertionPoint=custom_package"),
        "oneofRenderingType" to listOf("oneofRenderingType=SEPARATE"),
        "outputType" to listOf("outputType=STANDALONE_MARKDOWN"),
        "package" to listOf("diagramTypes=PACKAGE"),
        "suppressWellKnownTypes" to listOf("suppressWellKnownTypes=false"),
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

/*
 * End-to-end check that `outputType=EMBEDDED_MARKDOWN` diagrams actually splice into the
 * documents protoc-gen-markdown produces, via a real `protoc` run with BOTH plugins writing into
 * one shared output directory (protoc's own insertion-point mechanism).  This complements the
 * `embeddedMarkdown` recorder suite above, which captures the deterministic mermaid-side
 * insertion entries as committed fixtures: markdown output carries a generation timestamp, so its
 * spliced documents are not committed — this task asserts the diagrams landed instead.
 *
 * Both plugins are resolved as native/script executables: protoc-gen-markdown from its local
 * Maven native binary (see `repositories { mavenLocal() }` above) and protoc-gen-mermaid from the
 * root project's `installDist` start script.  The markdown native binary is only available for the
 * platform it was built on, so resolution is lenient and the task skips with a warning when it (or
 * protoc) can't be resolved — keeping CI green on platforms without a published binary.
 */
val markdownPluginVersion = "0.0.0-pre.0"
val osClassifier: String = osdetector.classifier

val e2eProtoc: Configuration by configurations.creating { isCanBeConsumed = false }
val e2eMarkdownPlugin: Configuration by configurations.creating { isCanBeConsumed = false }

dependencies {
    e2eProtoc("${libs.tools.protoc.compiler.get()}:$osClassifier@exe")
    e2eMarkdownPlugin("com.engine:protoc-gen-markdown:$markdownPluginVersion:$osClassifier@exe")
}

val verifyEmbeddedMarkdownEndToEnd =
    tasks.register<Exec>("verifyEmbeddedMarkdownEndToEnd") {
        group = "verification"
        description = "Runs real protoc with protoc-gen-markdown + protoc-gen-mermaid (EMBEDDED_MARKDOWN) and asserts the diagrams splice into the markdown nav files."
        dependsOn(":installDist")

        val protoDir = layout.projectDirectory.dir("src/embeddedMarkdown/proto")
        val outDir = layout.buildDirectory.dir("embeddedMarkdownEndToEnd")
        val binDir = layout.buildDirectory.dir("embeddedMarkdownEndToEndBin")
        val mermaidScript = rootProject.layout.buildDirectory.file("install/${rootProject.name}/bin/${rootProject.name}")
        val protocFiles = e2eProtoc.incoming.artifactView { lenient(true) }.files
        val markdownFiles = e2eMarkdownPlugin.incoming.artifactView { lenient(true) }.files

        inputs.dir(protoDir)
        outputs.dir(outDir)

        onlyIf {
            val resolvable = protocFiles.singleOrNull() != null && markdownFiles.singleOrNull() != null
            if (!resolvable) {
                logger.warn(
                    "Skipping verifyEmbeddedMarkdownEndToEnd: protoc or " +
                        "com.engine:protoc-gen-markdown:$markdownPluginVersion:$osClassifier@exe not resolvable. " +
                        "Publish protoc-gen-markdown to mavenLocal for this platform to enable it.",
                )
            }
            resolvable
        }

        doFirst {
            val out = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
            val bin = binDir.get().asFile.apply { mkdirs() }
            val protoc = File(bin, "protoc").apply { protocFiles.single().copyTo(this, overwrite = true); setExecutable(true) }
            val markdown = File(bin, "protoc-gen-markdown").apply { markdownFiles.single().copyTo(this, overwrite = true); setExecutable(true) }
            val protos = protoDir.asFile.listFiles { f -> f.extension == "proto" }!!.map { it.name }.sorted()

            workingDir = protoDir.asFile
            commandLine(
                buildList {
                    add(protoc.absolutePath)
                    add("--proto_path=.")
                    add("--plugin=protoc-gen-markdown=${markdown.absolutePath}")
                    add("--plugin=protoc-gen-mermaid=${mermaidScript.get().asFile.absolutePath}")
                    // markdown first: it creates the files mermaid then inserts into.
                    add("--markdown_out=includeIndices=true,outputType=PER_FILE:${out.absolutePath}")
                    add("--mermaid_out=outputType=EMBEDDED_MARKDOWN:${out.absolutePath}")
                    addAll(protos)
                },
            )
        }

        doLast {
            val out = outDir.get().asFile
            // The per-file doc, the package-index doc, and the overview doc must each carry a
            // spliced ```mermaid fence (FILE_OVERVIEW/MESSAGE, PACKAGE, and COMPLETE respectively).
            val expectFenced =
                listOf(
                    "embeddedMarkdown.md",
                    "engine.protoc.mermaid.example.embeddedMarkdown.md",
                    "overview.md",
                )
            for (name in expectFenced) {
                val f = File(out, name)
                require(f.exists()) { "expected $name to be generated by protoc-gen-markdown, but it was not" }
                require(f.readText().contains("```mermaid")) { "$name carries no spliced mermaid diagram" }
            }
            logger.lifecycle("verifyEmbeddedMarkdownEndToEnd: diagrams spliced into ${expectFenced.joinToString()}")
        }
    }

tasks.named("check") {
    dependsOn(verifyEmbeddedMarkdownEndToEnd)
}
