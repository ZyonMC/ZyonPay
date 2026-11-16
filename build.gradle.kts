plugins {
    java
}

group = "net.zyonmc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.helpch.at/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.249-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false } // arrastra bukkit 1.13
    compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }

    // Cargadas en runtime por Paper (plugin.yml `libraries`), tambien necesarias para compilar.
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
    compileOnly("io.lettuce:lettuce-core:6.5.5.RELEASE")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

// Los tests (incluidos los de integracion con MariaDB/Redis reales) ven las mismas librerias que el codigo.
configurations.testImplementation.get().extendsFrom(configurations.compileOnly.get())

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
}
