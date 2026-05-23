plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "info.wilhelms.gradle"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    website.set("https://github.com/wilhelms/lib-insight-plugin")
    vcsUrl.set("https://github.com/wilhelms/lib-insight-plugin.git")
    plugins {
        create("libInsightPlugin") {
            id = "info.wilhelms.gradle.lib-insight"
            implementationClass = "info.wilhelms.gradle.insight.LibInsightPlugin"
            displayName = "Library Insight Plugin"
            description = "Fact-based supply chain audit tool for Gradle dependencies."
            tags.set(listOf("audit", "metrics", "dependencies", "security", "supply-chain"))
        }
    }
}

publishing {
    repositories {
        maven {
            name = "localManagedRepo"
            url = uri("file:/home/jpw/managed/localMavenRepo")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}
