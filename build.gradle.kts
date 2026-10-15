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
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.spring") version "2.3.10"
    kotlin("plugin.jpa") version "2.3.10"
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

// The Spring Boot BOM pins org.postgresql:postgresql to 42.7.11;
// 42.7.12 fixes CVE-2026-54291
extra["postgresql.version"] = "42.7.12"

// The Spring Boot BOM pins tomcat-embed-core to 11.0.22;
// 11.0.25 fixes CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525
extra["tomcat.version"] = "11.0.25"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Persistence: JPA + Postgres + Flyway (invitation-rights)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Spring Modulith
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    // HAPI FHIR R4
    implementation("ca.uhn.hapi.fhir:hapi-fhir-client:8.10.1")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.10.1")

    // Rate limiting (Resilience4j)
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("org.springframework.boot:spring-boot-starter-aspectj:4.1.1")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")

    // HTMX
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.webjars.npm:htmx.org:2.0.10")
    implementation("org.webjars:webjars-locator-lite")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.0")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }

    allOpen {
        annotation("io.github.resilience4j.ratelimiter.annotation.RateLimiter")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The Keycloak provider lives in its own Gradle subproject (keycloak-provider) and is packaged
// there. Ensure a root build also produces the provider jar for docker-compose.
tasks.named("build") {
    dependsOn(":keycloak-provider:keycloakProviderJar")
}
