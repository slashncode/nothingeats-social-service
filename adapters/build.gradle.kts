// HTTP routes and the public feed stream, persistence, the Valkey leaderboard and Kafka, behind the application's ports.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.kotlinx-serialization")
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    api(project(":application"))
    api(libs.nothingeats.platform.http)
    api(libs.nothingeats.platform.persistence)
    api(libs.nothingeats.ktor.posthog)
    api(libs.nothingeats.platform.kafka)
    api(libs.nothingeats.platform.valkey)
    implementation(libs.ktor.server.sse)
    implementation(libs.exposed.java.time)

    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.nothingeats.platform.testing)
    testImplementation(libs.ktor.server.test.host)
}
