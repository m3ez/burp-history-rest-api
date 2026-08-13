import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.zip.ZipFile

plugins { java }

group = "com.burphistoryrest"
version = "1.4.1"

repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { compileOnly("net.portswigger.burp.extensions:montoya-api:2026.4") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    archiveBaseName.set("burp-history-rest-api")
    manifest {
        attributes(
            "Implementation-Title" to "Burp History REST API",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Supakiad S. (m3ez) - E-CQURITY (Thailand)",
            "Implementation-URL" to "http://x.com/supakiad_mee",
            "Specification-Title" to "Burp History REST API",
            "Specification-Version" to "v1",
            "Specification-Vendor" to "E-CQURITY (Thailand)",
            "Automatic-Module-Name" to "com.burphistoryrest",
            "Built-By" to "Supakiad S. (m3ez)"
        )
    }
}

val selfTestSourceSet = sourceSets.create("selfTest") {
    java.srcDir("src/selfTest/java")
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}
val selfTest by tasks.registering(JavaExec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs dependency-free integration and HTTP-hardening tests."
    dependsOn(tasks.named(selfTestSourceSet.classesTaskName))
    classpath = selfTestSourceSet.runtimeClasspath
    mainClass.set("com.burphistoryrest.SelfTest")
}
val auditJar by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.jar)
    doLast {
        val jarFile = tasks.jar.get().archiveFile.get().asFile
        val forbidden = listOf("com/sun/net/httpserver", "burp/api/montoya/")
        ZipFile(jarFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            forbidden.forEach { prefix -> check(names.none { it.startsWith(prefix) }) { "Forbidden bundled class prefix: $prefix" } }
        }
        val bytes = jarFile.readBytes().toString(Charsets.ISO_8859_1)
        check(!bytes.contains("com.sun.net.httpserver")) { "jdk.httpserver reference found" }
    }
}
tasks.named("check") { dependsOn(selfTest, auditJar) }
