plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("dev.icerock.mobile.multiplatform-resources")
}

group = rootProject.extra["groupName"].toString()
version = rootProject.extra["versionName"].toString()

val resourcesDir = "$buildDir/resources/"

val skikoWasm by configurations.creating

dependencies {
    skikoWasm("org.jetbrains.skiko:skiko-js-wasm-runtime:${rootProject.extra["skikoVersion"]}")
}

val unzipTask = tasks.register("unzipWasm", Copy::class) {
    destinationDir = file(resourcesDir)
    from(skikoWasm.map { zipTree(it) })
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
    dependsOn(unzipTask)
}

kotlin {
    js(IR) {
        browser()
        binaries.executable()
    }

    sourceSets {
        named("jsMain") {
            dependencies {
                implementation(project(":commonCompose"))
                implementation("org.jetbrains.skiko:skiko:${rootProject.extra["skikoVersion"]}")

                api("dev.icerock.moko:resources:${rootProject.extra["mokoVersion"]}")
            }

            resources.setSrcDirs(resources.srcDirs)
            resources.srcDirs(unzipTask.map { it.destinationDir })
        }
    }
}

multiplatformResources {
    multiplatformResourcesPackage = "tk.zwander.samloaderkotlin.resources.js" // required
}

compose.experimental {
    web.application {}
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

afterEvaluate {
    rootProject.extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension> {
//        versions.webpackDevServer.version = "4.0.0"
        versions.webpackCli.version = "4.10.0"
        nodeVersion = "16.0.0"
    }
}

tasks.findByPath(":common:jsProcessResources")?.apply {
    dependsOn(":common:generateMRcommonMain")
    dependsOn("generateMRjsMain")
}

tasks.findByPath(":commonCompose:jsProcessResources")?.apply {
    dependsOn(":commonCompose:generateMRcommonMain")
    dependsOn("generateMRjsMain")
}

tasks.findByPath(":browser:jsProcessResources")?.apply {
    dependsOn(":browser:generateMRcommonMain")
    dependsOn("generateMRjsMain")
}
