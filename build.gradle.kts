plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    `signing`
    id("org.jetbrains.dokka") version "1.9.10"
    id("com.gradleup.nmcp") version "0.0.9"
    id("com.gradle.plugin-publish") version "1.2.1"
}

group = "dev.wilhelms.gradle"
version = project.findProperty("version")?.takeIf { it != "unspecified" } ?: "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.wilhelms.gradle:gradle-progress-logger:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

gradlePlugin {
    website.set("https://github.com/jpwilhelms/lib-insight-plugin")
    vcsUrl.set("https://github.com/jpwilhelms/lib-insight-plugin.git")
    
    plugins {
        create("libInsightPlugin") {
            id = "dev.wilhelms.gradle.lib-insight"
            implementationClass = "dev.wilhelms.gradle.insight.LibInsightPlugin"
            displayName = "Library Insight Plugin"
            description = "Fact-based supply chain audit tool for Gradle dependencies."
            tags.set(listOf("audit", "metrics", "dependencies", "security", "supply-chain"))
        }
    }
}

// Ensure NMCP is configured AFTER all plugins have registered their publications
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            if (name == "pluginMaven") {
                artifactId = "gradle-lib-insight"
            }
            
            pom {
                name.set("Library Insight Plugin")
                description.set("Fact-based supply chain audit tool for Gradle dependencies.")
                url.set("https://github.com/jpwilhelms/lib-insight-plugin")
                
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                
                developers {
                    developer {
                        id.set("jpwilhelms")
                        name.set("Jan-Peter Wilhelms")
                        email.set("lib-insight-plugin@wilhelms.dev")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/jpwilhelms/lib-insight-plugin.git")
                    developerConnection.set("scm:git:ssh://github.com:jpwilhelms/lib-insight-plugin.git")
                    url.set("https://github.com/jpwilhelms/lib-insight-plugin")
                }
            }
        }
    }

    nmcp {
        publish("pluginMaven") {
            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))
        }
    }
}

signing {
    val key = System.getenv("GPG_PRIVATE_KEY")
    val password = System.getenv("GPG_PASSPHRASE")
    
    setRequired({ !key.isNullOrBlank() })

    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, password ?: "")
        sign(publishing.publications)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}
