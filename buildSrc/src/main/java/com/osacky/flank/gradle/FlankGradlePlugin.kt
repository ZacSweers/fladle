package com.osacky.flank.gradle

import com.android.build.gradle.AppExtension
import com.android.builder.model.TestOptions
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskContainer
import org.gradle.util.GradleVersion

class FlankGradlePlugin : Plugin<Project> {

  override fun apply(target: Project) {
    checkMinimumGradleVersion()

    // Create Configuration to store flank dependency
    target.configurations.create(FLADLE_CONFIG)

    val extension = target.extensions.create("fladle", FlankGradleExtension::class.java, target)

    configureTasks(target, extension)
  }

  private fun checkMinimumGradleVersion() {
    // Gradle 4.9 is required because we use the lazy task configuration API.
    if (GRADLE_MIN_VERSION > GradleVersion.current()) {
      throw GradleException("Fladle requires at minimum version $GRADLE_MIN_VERSION. Detected version ${GradleVersion.current()}.")
    }
  }

  private fun configureTasks(project: Project, base: FlankGradleExtension) {
    if (GradleVersion.current() > GradleVersion.version("6.1")) {
      base.flankVersion.finalizeValueOnRead()
      base.flankCoordinates.finalizeValueOnRead()
      base.serviceAccountCredentials.finalizeValueOnRead()
    }
    project.afterEvaluate {
      // Add Flank dependency to Fladle Configuration
      // Must be done afterEvaluate otherwise extension values will not be set.
      project.dependencies.add(FLADLE_CONFIG, "${base.flankCoordinates.get()}:${base.flankVersion.get()}")

      // Only use automatic apk path detection for 'com.android.application' projects.
      project.pluginManager.withPlugin("com.android.application") {
        if (!base.debugApk.isPresent || !base.instrumentationApk.isPresent) {
          findDebugAndInstrumentationApk(project, base)
        }
      }
      tasks.apply {
        createTasksForConfig(base, base, project, "")

        base.configs.forEach { config ->
          createTasksForConfig(base, config, project, config.name.capitalize())
        }
      }
    }
  }

  private fun TaskContainer.createTasksForConfig(base: FlankGradleExtension, config: FladleConfig, project: Project, name: String) {
    register("printYml$name") {
      description = "Print the flank.yml file to the console."
      group = TASK_GROUP
      doLast {
        println(YamlWriter().createConfigProps(config, base))
      }
    }

    val writeConfigProps = project.tasks.register("writeConfigProps$name", YamlConfigWriterTask::class.java, config, base)

    project.tasks.register("flankDoctor$name", JavaExec::class.java) {
      description = "Finds problems with the current configuration."
      group = TASK_GROUP
      workingDir(project.fladleDir)
      classpath = project.fladleConfig
      main = "ftl.Main"
      args = listOf("firebase", "test", "android", "doctor")
      dependsOn(writeConfigProps)
    }

    val execFlank = project.tasks.register("execFlank$name", JavaExec::class.java) {
      description = "Runs instrumentation tests using flank on firebase test lab."
      group = TASK_GROUP
      workingDir(project.fladleDir)
      classpath = project.fladleConfig
      main = "ftl.Main"
      if (project.hasProperty("dumpShards")) {
        args = listOf("firebase", "test", "android", "run", "--dump-shards")
      } else {
        args = listOf("firebase", "test", "android", "run")
      }
      if (config.serviceAccountCredentials.isPresent) {
        environment(mapOf("GOOGLE_APPLICATION_CREDENTIALS" to config.serviceAccountCredentials.get()))
      }
      dependsOn(named("writeConfigProps$name"))
      doFirst {
        checkFilesExist(base, project)
      }
    }

    register("runFlank$name", RunFlankTask::class.java).configure {
      dependsOn(execFlank)
    }
  }

  private fun checkFilesExist(base: FlankGradleExtension, project: Project) {
    if (base.serviceAccountCredentials.isPresent) {
      check(project.file(base.serviceAccountCredentials.get()).exists()) { "serviceAccountCredential file doesn't exist ${base.serviceAccountCredentials.get()}" }
    }
    check(base.debugApk.isPresent) { "debugApk file must be specified ${base.debugApk.orNull}" }
    check(base.instrumentationApk.isPresent) { "instrumentationApk file must be specified ${base.instrumentationApk.orNull}" }
    base.additionalTestApks.forEach {
      check(it.value.isNotEmpty()) { "must provide at least one instrumentation apk for ${it.key}" }
    }
  }

  private fun automaticallyConfigureTestOrchestrator(project: Project, extension: FlankGradleExtension, androidExtension: AppExtension) {
    project.afterEvaluate {
      val useOrchestrator = androidExtension.testOptions.executionEnum == TestOptions.Execution.ANDROIDX_TEST_ORCHESTRATOR || androidExtension.testOptions.executionEnum == TestOptions.Execution.ANDROID_TEST_ORCHESTRATOR
      if (useOrchestrator) {
        log("Automatically detected the use of Android Test Orchestrator")
      }
      extension.useOrchestrator = useOrchestrator
    }
  }

  private fun findDebugAndInstrumentationApk(project: Project, extension: FlankGradleExtension) {
    val baseExtension = requireNotNull(project.extensions.findByType(AppExtension::class.java)) { "Could not find AppExtension in ${project.name}" }
    automaticallyConfigureTestOrchestrator(project, extension, baseExtension)
    baseExtension.applicationVariants.all {
      if (testVariant != null) {
        outputs.all debug@{
          if (extension.variant == null || (extension.variant != null && extension.variant == name)) {
            testVariant.outputs.all test@{
              project.log("Configuring fladle.debugApk from variant ${this@debug.name}")
              project.log("Configuring fladle.instrumentationApk from variant ${this@test.name}")
              extension.debugApk.set(this@debug.outputFile.absolutePath)
              extension.instrumentationApk.set(this@test.outputFile.absolutePath)
            }
          }
        }
      }
    }
  }

  private val Project.fladleConfig: Configuration
    get() = configurations.getByName(FLADLE_CONFIG)

  companion object {
    val GRADLE_MIN_VERSION = GradleVersion.version("5.1")
    const val TASK_GROUP = "fladle"
    const val FLADLE_CONFIG = "fladle"
    fun Project.log(message: String) {
      logger.info("Fladle: $message")
    }
  }
}
