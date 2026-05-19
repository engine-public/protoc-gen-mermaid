import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

description = "protoc compiler to turn gRPC services into mermaid class diagrams"

group = "com.engine"
version = System.getenv("ENGINE_BUILD_VERSION")?.ifEmpty { null } ?: "0.0.0-pre.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.engine.protoc.utils)
    implementation(libs.protobuf.java)

    testImplementation(libs.bundles.test.kotest)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
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

testing {
    suites {
        withType<JvmTestSuite>().configureEach {
            useJUnitJupiter()
        }
    }
}

application {
    mainClass.set("com.engine.protoc.mermaid.MainKt")
}
