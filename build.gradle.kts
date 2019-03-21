buildscript {
    dependencies {
        //must specify this in gradle.properties since the same version must be here and in aurora plugin
        val springCloudContractVersion: String = project.property("aurora.springCloudContractVersion") as String
        classpath("org.springframework.cloud:spring-cloud-contract-gradle-plugin:$springCloudContractVersion")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.21"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "7.1.0"

    id("org.springframework.boot") version "2.1.3.RELEASE"
    // TODO: Fix asciidoc tests
//    id("org.asciidoctor.convert") version "1.6.0"

    id("com.gorylenko.gradle-git-properties") version "2.0.0"
    id("com.github.ben-manes.versions") version "0.21.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.9"

    id("no.skatteetaten.gradle.aurora") version "2.2.1"

}

apply(plugin = "spring-cloud-contract")

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.1.1")

    implementation("org.eclipse.jgit:org.eclipse.jgit:4.11.0.201803080745-r")
    implementation("com.github.fge:json-patch:1.9")
    implementation("org.encryptor4j:encryptor4j:0.1.2")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.apache.commons:commons-text:1.3")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    implementation("com.fkorotkov:kubernetes-dsl:2.0.1")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.retry:spring-retry")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    testImplementation("com.squareup.okhttp3:mockwebserver:3.14.0")
    testImplementation("io.mockk:mockk:1.9.2")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.13")
    testImplementation("com.nhaarman:mockito-kotlin:1.6.0")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:0.4.0")

}
