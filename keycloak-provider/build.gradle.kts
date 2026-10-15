/*
 * Copyright 2026 MA 01 - Wien Digital
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

plugins {
    kotlin("jvm") version "2.3.10"
}

group = "io.cloudflight"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

// Must match the Keycloak image used in docker-compose.yml (quay.io/keycloak/keycloak:26.7).
val keycloakVersion = "26.7.1"

dependencies {
    // Keycloak event-listener and protocol-mapper SPIs. compileOnly on purpose: these classes are provided by the
    // Keycloak runtime and must NOT be bundled into the provider jar.
    compileOnly("org.keycloak:keycloak-core:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")

    // Testing
    testImplementation("org.keycloak:keycloak-core:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-server-spi:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-services:$keycloakVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.16")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

/**
 * The Keycloak runtime ships no Kotlin runtime, so the provider jar has to carry it. Kept in a
 * separate configuration to make explicit that this is packaging, not a dependency of the app.
 */
val keycloakProviderRuntime: Configuration by configurations.creating

dependencies {
    keycloakProviderRuntime("org.jetbrains.kotlin:kotlin-stdlib:${kotlin.coreLibrariesVersion}")
}

/**
 * Packages the Keycloak provider (event listener) as a plain jar for /opt/keycloak/providers. Only
 * the provider classes, their META-INF/services registration and the Kotlin runtime are included -
 * never the Spring application.
 */
tasks.register<Jar>("keycloakProviderJar") {
    group = "build"
    description = "Builds the Keycloak provider jar for /opt/keycloak/providers"
    archiveBaseName = "pgk-idp-event-listener"
    archiveVersion = ""
    destinationDirectory = layout.buildDirectory.dir("keycloak")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output) {
        include("io/cloudflight/pgk/keycloakprovider/**")
        include("META-INF/services/org.keycloak.*")
    }
    from(keycloakProviderRuntime.map { zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
        exclude("**/module-info.class")
    }
}
