rootProject.name = "protoc-gen-mermaid"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

rootDir
    .walkTopDown()
    .onEnter { dir ->
        val excluded =
            dir.name.startsWith(".") ||
                dir.name in setOf("build", "buildSrc", "tmp", "scratch") ||
                dir.resolve(".gradle_ignore").exists()
        dir == rootDir || !excluded
    }
    .filter { it != rootDir }
    .filter { it.isDirectory }
    .filter { it.resolve("build.gradle.kts").let { f -> f.exists() && f.isFile } }
    .forEach {
        val relativePath = it.relativeTo(rootDir)
        val projectName = ":${rootProject.name}-${relativePath.path.replace("/", "-")}"
        include(projectName)
        project(projectName).projectDir = it
    }

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
