rootProject.name = "protoc-gen-mermaid"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

rootDir
    .walkTopDown()
    .filter { it != rootDir }
    .filter { it.isDirectory }
    .filterNot { it.name.startsWith(".") }
    .filterNot { setOf("build", "buildSrc", "tmp", "scratch").contains(it.name) }
    .filterNot { it.resolve(".gradle_ignore").exists() }
    .filter { it.resolve("build.gradle.kts").let { it.exists() && it.isFile } }
    .forEach {
        val relativePath = it.relativeTo(rootDir)
        val projectName = ":${rootProject.name}-${relativePath.path.replace("/", "-")}"
        include(projectName)
        project(projectName).projectDir = it
    }

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
