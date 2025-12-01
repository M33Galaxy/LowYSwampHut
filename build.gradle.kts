plugins {
    id("java")
}

group = "project"
version = "1.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("com.seedfinding:mc_core:1.210.0")
    implementation("com.seedfinding:mc_feature:1.171.9")
    implementation("com.github.jellejurre:seed-checker:1.2.0-1.18.1") { isTransitive = false }
    implementation("com.github.KalleStruik:noise-sampler:1.20.0")
    implementation("com.seedfinding:latticg:1.06@jar")
    implementation("org.junit.jupiter:junit-jupiter:5.8.1")
    implementation("org.junit.jupiter:junit-jupiter:5.8.1")
    implementation("org.junit.jupiter:junit-jupiter:5.8.1")
}

tasks.test {
    useJUnitPlatform()
}