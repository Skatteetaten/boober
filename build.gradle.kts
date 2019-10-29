import com.adarshr.gradle.testlogger.theme.ThemeType

plugins {
    id("org.springframework.cloud.contract")
    id("org.jetbrains.kotlin.jvm") version "1.3.50"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.50"
    id("org.jlleitschuh.gradle.ktlint") version "9.0.0"
    id("org.sonarqube") version "2.8"

    id("org.springframework.boot") version "2.1.9.RELEASE"
    id("org.asciidoctor.convert") version "2.3.0"

    id("com.gorylenko.gradle-git-properties") version "2.2.0"
    id("com.github.ben-manes.versions") version "0.27.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.12"
    id("com.adarshr.test-logger") version "2.0.0"

    id("no.skatteetaten.gradle.aurora") version "3.1.0"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")

    implementation("org.eclipse.jgit:org.eclipse.jgit:4.11.0.201803080745-r")
    implementation("com.github.fge:json-patch:1.9")
    implementation("org.encryptor4j:encryptor4j:0.1.2")
    // The above library uses an vulnerable bcprov, set the fixed version here, hopefully this will work.
    // pr is sent to maintainer
    implementation("org.bouncycastle:bcprov-jdk15on:1.64")
    implementation("com.github.ben-manes.caffeine:caffeine:2.8.0")
    implementation("org.apache.commons:commons-text:1.8")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    implementation("com.fkorotkov:kubernetes-dsl:3.0")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.retry:spring-retry")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.19")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.0.2")
    testImplementation("com.ninja-squad:springmockk:1.1.3")
}

testlogger {
    theme = ThemeType.PLAIN
}
