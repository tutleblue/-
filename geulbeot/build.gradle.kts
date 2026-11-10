// No plugins block here on purpose.
//
// Declaring the Kotlin plugin at the root puts it on the build classpath, and a subproject that
// then asks for a sibling Kotlin plugin *with a version* - `:app` needs kotlin-android - is
// rejected with "already on the classpath with an unknown version". Each module declares the
// plugins it needs instead, which also means this file never has to resolve the Android Gradle
// plugin, so `:core-hwp:test` still runs where Google's Maven repository is unreachable.

/**
 * Builds the distribution archive.
 *
 * Source only: no build outputs, no local configuration, and no signing material - `keystore.properties`
 * and any keystore are excluded explicitly as well as being git-ignored, so a release package can
 * never carry a signing key even if one is sitting in the working directory.
 */
val distributionZip = tasks.register<Zip>("packageDistribution") {
    group = "distribution"
    description = "배포용 소스 압축 파일을 dist/ 에 만듭니다."

    val versionJson = layout.projectDirectory.file("version.json").asFile
    val version = if (versionJson.exists()) {
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(versionJson.readText())?.groupValues?.get(1) ?: "0.0.0"
    } else {
        "0.0.0"
    }

    archiveFileName.set("geulbeot-$version-source.zip")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))

    from(layout.projectDirectory) {
        exclude(
            "**/build/**",
            "**/.gradle/**",
            "**/.idea/**",
            "dist/**",
            "local.properties",
            "keystore.properties",
            "**/*.jks",
            "**/*.keystore",
            "**/*.apk",
            "**/*.aab",
            "**/.DS_Store",
        )
    }
    into("geulbeot-$version")
}

tasks.register("checkNoSecrets") {
    group = "verification"
    description = "배포 전에 자격 증명이나 서명 파일이 섞여 있지 않은지 확인합니다."
    doLast {
        val offenders = layout.projectDirectory.asFile.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.path.contains("/build/") || it.path.contains("/.git/") || it.path.contains("/dist/") }
            .filter { file ->
                file.name == "keystore.properties" ||
                    file.name == "local.properties" ||
                    file.extension in setOf("jks", "keystore", "p12", "pem", "key")
            }
            .map { it.relativeTo(layout.projectDirectory.asFile).path }
            .toList()
        if (offenders.isNotEmpty()) {
            throw GradleException("배포 대상에 넣으면 안 되는 파일이 있습니다: " + offenders.joinToString(", "))
        }
        logger.lifecycle("[geulbeot] 자격 증명/서명 파일 없음 - 배포 가능")
    }
}

distributionZip.configure { dependsOn("checkNoSecrets") }
