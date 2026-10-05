plugins {
    java
    kotlin("jvm") version "2.0.21"
}

group = "com.tecnor.adm"
version = "0.9.0-stage10"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    implementation(kotlin("stdlib"))
    implementation("com.h2database:h2:2.3.232")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}