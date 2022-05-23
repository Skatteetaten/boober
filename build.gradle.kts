plugins {
    id("java")
    id("no.skatteetaten.gradle.aurora") version "4.4.19"
}

aurora {
    useKotlinDefaults
    useSpringBootDefaults
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")

    implementation("org.eclipse.jgit:org.eclipse.jgit:6.1.0.202203080745-r")
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("com.google.guava:guava:31.1-jre")
    implementation("org.encryptor4j:encryptor4j:0.1.2")
    // The above library uses an vulnerable bcprov, set the fixed version here, hopefully this will work.
    // pr is sent to maintainer
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("com.github.ben-manes.caffeine:caffeine:3.0.6")
    implementation("org.apache.commons:commons-text:1.9")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.2")

    // TODO: 2.7.1 er nyere en 3.0, det er viktig at vi kjører denne og ikke 3.0 job formatet er feil i 3.0
    implementation("com.fkorotkov:kubernetes-dsl:2.8.1")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.retry:spring-retry")
    implementation("com.cronutils:cron-utils:9.1.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    testImplementation("io.mockk:mockk:1.12.3")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.7")
    testImplementation("no.skatteetaten.aurora:mockwebserver-extensions-kotlin:1.2.0")
    testImplementation("com.ninja-squad:springmockk:3.1.1")
}
