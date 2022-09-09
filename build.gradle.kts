plugins {
    id("java")
    id("no.skatteetaten.gradle.aurora") version "4.5.4"
}

aurora {
    useKotlinDefaults
    useSpringBootDefaults

    versions {
        javaSourceCompatibility = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r")
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("com.google.guava:guava:31.1-jre")

    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.1")
    implementation("org.apache.commons:commons-text:1.9")
    implementation("commons-io:commons-io:2.11.0")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.4")

    implementation("com.github.fkorotkov:k8s-kotlin-dsl:3.0.1")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.retry:spring-retry")
    implementation("com.cronutils:cron-utils:9.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    testImplementation("io.mockk:mockk:1.12.7")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.8")
    testImplementation("no.skatteetaten.aurora:mockwebserver-extensions-kotlin:1.3.1")
    testImplementation("com.ninja-squad:springmockk:3.1.1")
}
