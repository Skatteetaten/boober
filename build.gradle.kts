import com.adarshr.gradle.testlogger.theme.ThemeType

plugins {
    id("org.springframework.cloud.contract") version "2.1.5.RELEASE"
    id("org.jetbrains.kotlin.jvm") version "1.3.72"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.72"
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1"
    id("org.sonarqube") version "2.8"

    id("org.springframework.boot") version "2.2.13.RELEASE"
    id("org.asciidoctor.convert") version "2.4.0"

    id("com.gorylenko.gradle-git-properties") version "2.2.2"
    id("com.github.ben-manes.versions") version "0.39.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.17"
    id("com.adarshr.test-logger") version "3.0.0"

    id("no.skatteetaten.gradle.aurora") version "3.5.2"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.3.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.4")

    implementation("org.eclipse.jgit:org.eclipse.jgit:5.7.0.202003110725-r")
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("com.github.fge:json-patch:1.13")
    // Newest json-patch removes guava as dependency: https://github.com/java-json-tools/json-patch/releases
    implementation("com.google.guava:guava:30.1.1-jre")
    implementation("org.encryptor4j:encryptor4j:0.1.2")
    // The above library uses an vulnerable bcprov, set the fixed version here, hopefully this will work.
    // pr is sent to maintainer
    implementation("org.bouncycastle:bcprov-jdk15on:1.69")
    implementation("com.github.ben-manes.caffeine:caffeine:3.0.4")
    implementation("org.apache.commons:commons-text:1.9")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // TODO: 2.7.1 er nyere en 3.0, det er viktig at vi kjører denne og ikke 3.0 job formatet er feil i 3.0
    implementation("com.fkorotkov:kubernetes-dsl:2.7.1")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.retry:spring-retry")
    implementation("com.cronutils:cron-utils:9.1.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    testImplementation("io.mockk:mockk:1.12.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.22")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.0")
    testImplementation("com.ninja-squad:springmockk:2.0.3")
}

testlogger {
    theme = ThemeType.PLAIN
}

/*
tasks.test { onlyIf { false } }
tasks.asciidoctor { onlyIf { false } }
*/
