import com.android.build.api.variant.impl.VariantOutputImpl
import org.gradle.api.tasks.bundling.Zip

val dnsjavaInetAddressResolverService = "META-INF/services/java.net.spi.InetAddressResolverProvider"
val openClawAndroidRuntimeVersion = "0.2.0"
val openClawAndroidRuntimeWorkDir = layout.buildDirectory.dir("generated/openclawAndroidRuntime")
val openClawAndroidRuntimeAssetsDir = layout.buildDirectory.dir("generated/openclawAndroidRuntimeAssets")
val openClawAndroidRuntimeAssetsPath = layout.buildDirectory.asFile.get().resolve("generated/openclawAndroidRuntimeAssets")
val openClawAndroidRuntimeZip = openClawAndroidRuntimeAssetsDir.map { it.file("openclaw/runtime/openclaw-runtime.zip") }

val androidStoreFile = providers.gradleProperty("OPENCLAW_ANDROID_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
val androidStorePassword = providers.gradleProperty("OPENCLAW_ANDROID_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val androidKeyAlias = providers.gradleProperty("OPENCLAW_ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val androidKeyPassword = providers.gradleProperty("OPENCLAW_ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val resolvedAndroidStoreFile =
  androidStoreFile?.let { storeFilePath ->
    if (storeFilePath.startsWith("~/")) {
      "${System.getProperty("user.home")}/${storeFilePath.removePrefix("~/")}"
    } else {
      storeFilePath
    }
  }

val hasAndroidReleaseSigning =
  listOf(resolvedAndroidStoreFile, androidStorePassword, androidKeyAlias, androidKeyPassword).all { it != null }

val wantsAndroidReleaseBuild =
  gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true) ||
      Regex("""(^|:)(bundle|assemble)$""").containsMatchIn(taskName)
  }

if (wantsAndroidReleaseBuild && !hasAndroidReleaseSigning) {
  error(
    "Missing Android release signing properties. Set OPENCLAW_ANDROID_STORE_FILE, " +
      "OPENCLAW_ANDROID_STORE_PASSWORD, OPENCLAW_ANDROID_KEY_ALIAS, and " +
      "OPENCLAW_ANDROID_KEY_PASSWORD in ~/.gradle/gradle.properties.",
  )
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ai.openclaw.app"
  compileSdk = 36

  // Release signing is local-only; keep the keystore path and passwords out of the repo.
  signingConfigs {
    if (hasAndroidReleaseSigning) {
      create("release") {
        storeFile = project.file(checkNotNull(resolvedAndroidStoreFile))
        storePassword = checkNotNull(androidStorePassword)
        keyAlias = checkNotNull(androidKeyAlias)
        keyPassword = checkNotNull(androidKeyPassword)
      }
    }
  }

  sourceSets {
    getByName("main") {
      assets.srcDir(openClawAndroidRuntimeAssetsPath)
    }
  }

  defaultConfig {
    applicationId = "io.github.openclawcn.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 2026042700
    versionName = "2026.4.27"
    ndk {
      // MNN voice runtime is currently bundled from the reference project for arm64 devices.
      abiFilters += listOf("arm64-v8a")
    }
  }

  flavorDimensions += "store"

  productFlavors {
    create("play") {
      dimension = "store"
    }
    create("thirdParty") {
      dimension = "store"
    }
  }

  buildTypes {
    release {
      if (hasAndroidReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = true
      isShrinkResources = true
      ndk {
        debugSymbolLevel = "SYMBOL_TABLE"
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes +=
        setOf(
          "/META-INF/{AL2.0,LGPL2.1}",
          "/META-INF/*.version",
          "/META-INF/LICENSE*.txt",
          "DebugProbesKt.bin",
          "kotlin-tooling-metadata.json",
          "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
          "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
          "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties",
          "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
        )
    }
  }

  lint {
    lintConfig = file("lint.xml")
    warningsAsErrors = true
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

androidComponents {
  onVariants { variant ->
    variant.outputs
      .filterIsInstance<VariantOutputImpl>()
      .forEach { output ->
        val versionName = output.versionName.orNull ?: "0"
        val buildType = variant.buildType
        val flavorName = variant.flavorName?.takeIf { it.isNotBlank() }
        val outputFileName =
          if (flavorName == null) {
            "openclaw-android-$versionName-$buildType.apk"
          } else {
            "openclaw-android-$versionName-$flavorName-$buildType.apk"
          }
        output.outputFileName = outputFileName
      }
  }
}
kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

ktlint {
  android.set(true)
  ignoreFailures.set(false)
  filter {
    exclude("**/build/**")
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.webkit)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // material-icons-extended pulled in full icon set (~20 MB DEX). Only ~18 icons used.
  // R8 will tree-shake unused icons when minify is enabled on release builds.
  implementation(libs.androidx.compose.material.icons.extended)

  debugImplementation(libs.androidx.compose.ui.tooling)

  // Material Components (XML theme + resources)
  implementation(libs.material)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.exifinterface)
  implementation(libs.okhttp)
  implementation(libs.bcprov)
  implementation(libs.commonmark)
  implementation(libs.commonmark.ext.autolink)
  implementation(libs.commonmark.ext.gfm.strikethrough)
  implementation(libs.commonmark.ext.gfm.tables)
  implementation(libs.commonmark.ext.task.list.items)

  // CameraX (for node.invoke camera.* parity)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.video)
  implementation(libs.play.services.code.scanner)

  // Unicast DNS-SD (Wide-Area Bonjour) for tailnet discovery domains.
  implementation(libs.dnsjava)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.robolectric)
  testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

val prepareOpenClawAndroidRuntime =
  tasks.register<Exec>("prepareOpenClawAndroidRuntime") {
    val script = layout.projectDirectory.file("../scripts/prepare-openclaw-android-runtime.mjs")
    inputs.file(script)
    inputs.property("openClawAndroidRuntimeVersion", openClawAndroidRuntimeVersion)
    outputs.dir(openClawAndroidRuntimeWorkDir)
    commandLine(
      "node",
      script.asFile.absolutePath,
      "--version",
      openClawAndroidRuntimeVersion,
      "--out",
      openClawAndroidRuntimeWorkDir.get().asFile.absolutePath,
    )
  }

val zipOpenClawAndroidRuntime =
  tasks.register<Zip>("zipOpenClawAndroidRuntime") {
    dependsOn(prepareOpenClawAndroidRuntime)
    from(openClawAndroidRuntimeWorkDir.map { it.dir("root") })
    archiveFileName.set("openclaw-runtime.zip")
    destinationDirectory.set(openClawAndroidRuntimeAssetsDir.map { it.dir("openclaw/runtime") })
  }

tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("Assets") }.configureEach {
  dependsOn(zipOpenClawAndroidRuntime)
}

androidComponents {
  onVariants(selector().withBuildType("release")) { variant ->
    val variantName = variant.name
    val variantNameCapitalized = variantName.replaceFirstChar(Char::titlecase)
    val stripTaskName = "strip${variantNameCapitalized}DnsjavaServiceDescriptor"
    val mergeTaskName = "merge${variantNameCapitalized}JavaResource"
    val minifyTaskName = "minify${variantNameCapitalized}WithR8"
    val mergedJar =
      layout.buildDirectory.file(
        "intermediates/merged_java_res/$variantName/$mergeTaskName/base.jar",
      )

    val stripTask =
      tasks.register(stripTaskName) {
        inputs.file(mergedJar)
        outputs.file(mergedJar)

        doLast {
          val jarFile = mergedJar.get().asFile
          if (!jarFile.exists()) {
            return@doLast
          }

          val unpackDir = temporaryDir.resolve("merged-java-res")
          delete(unpackDir)
          copy {
            from(zipTree(jarFile))
            into(unpackDir)
            exclude(dnsjavaInetAddressResolverService)
          }
          delete(jarFile)
          ant.invokeMethod(
            "zip",
            mapOf(
              "destfile" to jarFile.absolutePath,
              "basedir" to unpackDir.absolutePath,
            ),
          )
        }
      }

    tasks.matching { it.name == mergeTaskName }.configureEach {
      finalizedBy(stripTask)
    }
    tasks.matching { it.name == minifyTaskName }.configureEach {
      dependsOn(stripTask)
    }
  }
}
