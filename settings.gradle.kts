import org.gradle.api.GradleException

rootProject.name = "LowYSwampHut"

// Native search core. LowYSwampHutFinderBase owns the C engine, the JNI
// library, the standalone CLI and its build scripts; the main module only
// consumes its artifacts. See the contract comment in build.gradle.
val baseProjectDir = file("LowYSwampHutFinderBase")
if (!baseProjectDir.isDirectory) {
    throw GradleException(
        "LowYSwampHutFinderBase is missing. Restore the directory, or once the GitHub " +
            "repository exists run: git submodule update --init --recursive"
    )
}

include("lowyswamphutfinderbase")
project(":lowyswamphutfinderbase").projectDir = baseProjectDir
