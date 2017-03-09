buildscript {
    val springBootVersion = "1.5.2.RELEASE"
    val kotlinVersion = "1.1.0"
    extra["kotlinVersion"] = kotlinVersion

    repositories {
        gradleScriptKotlin()
        mavenCentral()
    }

    dependencies {
        classpath(kotlinModule("gradle-plugin", kotlinVersion))
        classpath("org.jetbrains.kotlin:kotlin-noarg:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-allopen:$kotlinVersion")
        classpath("org.springframework.boot:spring-boot-gradle-plugin:$springBootVersion")

    }
}


apply {
    plugin("kotlin")
    plugin("kotlin-spring")
    plugin("org.springframework.boot")
}

version = "0.0.1-SNAPSHOT"

configure<JavaPluginConvention> {
    setSourceCompatibility(1.8)
    setTargetCompatibility(1.8)
}

val kotlinVersion = extra["kotlinVersion"] as String


repositories {
    gradleScriptKotlin()
    mavenCentral()

}


dependencies {
    compile(kotlinModule("stdlib"))
    compile(kotlinModule("reflect"))
    compile("org.springframework.boot:spring-boot-starter-actuator")
    compile("org.springframework.boot:spring-boot-starter-web")
    compile("org.springframework.boot:spring-boot-starter-hateoas")
    compile("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.8.7")
    testCompile("org.springframework.boot:spring-boot-starter-test")
    testCompile("org.springframework.restdocs:spring-restdocs-mockmvc")

}


