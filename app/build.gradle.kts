plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.micronaut.crac") version "4.0.4"
    id("io.micronaut.library") version "4.0.4"
    id("io.micronaut.aot") version "4.0.4"
}

version = "0.1"
group = "de.roamingthings"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")

    implementation(project(":shared"))

    implementation("io.micronaut.aws:micronaut-function-aws")
    implementation("io.micronaut.aws:micronaut-aws-lambda-events-serde")
    implementation("io.micronaut.crac:micronaut-crac")
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    implementation("io.micronaut.aws:micronaut-aws-sdk-v2")

    implementation("software.amazon.awssdk:url-connection-client")
    implementation("software.amazon.awssdk:dynamodb")


    implementation(platform("org.apache.logging.log4j:log4j-bom:2.20.0"))
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl")

    compileOnly("org.projectlombok:lombok")

    testImplementation("org.mockito:mockito-core")
    testImplementation("io.micronaut.aws:micronaut-function-aws-test")
    testImplementation("org.assertj:assertj-core:3.24.2")

    testImplementation("software.amazon.awssdk:cloudformation")

    testImplementation("org.instancio:instancio-core:2.6.0")
    testImplementation("org.instancio:instancio-junit:2.9.0")
    testImplementation("software.amazon.awssdk:eventbridge")
}

configurations.all {
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    exclude(group = "software.amazon.awssdk", module = "apache-client")
}

java {
    sourceCompatibility = JavaVersion.toVersion("17")
    targetCompatibility = JavaVersion.toVersion("17")
}

//graalvmNative.toolchainDetection.set(false)
micronaut {
    runtime("lambda_java")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("de.roamingthings.*")
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading.set(false)
        convertYamlToJava.set(false)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
    }
}

tasks.test {
    useJUnitPlatform() {
        excludeTags("system")
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}

tasks.register<Test>("systemTests") {
    useJUnitPlatform {
        includeTags("system")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
