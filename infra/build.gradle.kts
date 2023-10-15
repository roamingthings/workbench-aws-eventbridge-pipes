plugins {
    id("application")
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("org.projectlombok:lombok:1.18.20")

    implementation(project(":shared"))

    implementation(platform("io.micronaut.platform:micronaut-platform:4.1.4"))
    implementation("io.micronaut.starter:micronaut-starter-aws-cdk:4.1.3") {
        exclude(group = "software.amazon.awscdk", module = "aws-cdk-lib")
    }
    implementation("software.amazon.awscdk:aws-cdk-lib:2.101.0")

    testImplementation(platform("io.micronaut.platform:micronaut-platform:4.1.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")

    compileOnly("org.projectlombok:lombok:1.18.20")
}

application {
    mainClass.set("de.roamingthings.Main")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

