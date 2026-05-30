import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf).apply(false)
}

description = "protoc compiler to turn gRPC services into mermaid class diagrams"

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    group = "com.engine"
    version = System.getenv("ENGINE_BUILD_VERSION")?.ifEmpty { null } ?: "0.0.0-pre.0"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    configure<KotlinJvmProjectExtension> {
        explicitApi()
    }

    configure<KtlintExtension> {
        version.set("1.8.0")
        filter {
            /*
             * work around bug in the ktlint plugin that doesn't honor exclusions of
             * generated code (protobuf, etc.)
             */
            exclude {
                it.file.absolutePath.startsWith(layout.buildDirectory.get().asFile.absolutePath)
            }
        }
        reporters {
            reporter(ReporterType.CHECKSTYLE)
            reporter(ReporterType.HTML)
        }
    }

    afterEvaluate {
        configure<TestingExtension> {
            suites {
                withType<JvmTestSuite>().configureEach {
                    useJUnitJupiter()
                    dependencies {
                        implementation.bundle(libs.bundles.test.kotest)
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.engine.protoc.utils)
    implementation(libs.protobuf.java)
}

application {
    mainClass.set("com.engine.protoc.mermaid.MainKt")
}

/*
 * Forward Gradle's stdin into the `run` task so `./gradlew -Pagent :run < cgr.binpb`
 * actually streams the recorded CodeGeneratorRequest to the plugin — the GraalVM agent
 * regen workflow documented in CONTRIBUTING.md depends on this.  JavaExec doesn't pipe
 * stdin by default.
 */
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
