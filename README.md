# Boober

## Setting Up a Development Environment

In the process of opensourcing this module, all in-house dependencies also needed to be opensourced. Currently,
all these dependencies are available a source code on GitHub, but none of the have been published to
Maven Central. As a consequence, every dependency has to be built and installed locally manually before
building this component.

The modules in question are;

**CheckStyle Config**

    git clone https://github.com/Skatteetaten/checkstyle-config.git
    cd checkstyle-config
    git checkout v0.6
    mvn clean install

**Aurora Git Version**

    git clone https://github.com/Skatteetaten/aurora-git-version.git
    cd aurora-git-version
    git checkout v1.0.0
    ./gradlew clean install

**Aurora Gradle Plugin**

    git clone https://github.com/Skatteetaten/aurora-gradle-plugin.git
    cd aurora-gradle-plugin
    git checkout feature/AOS-1064-opensource-gradle-plugins
    ./gradlew clean install
  
You should then be able to successfully build Boober itself.
  