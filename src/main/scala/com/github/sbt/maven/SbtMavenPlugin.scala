/*
 * Copyright (C) from 2023 The sbt contributors <https://github.com/sbt>
 */

package com.github.sbt.maven

import java.io.File
import java.nio.file.Files

import sbt.*
import sbt.io.PathFinder
import sbt.librarymanagement.Configurations
import sbt.librarymanagement.Configurations.Compile
import sbt.librarymanagement.CrossVersion
import sbt.Def.settingKey
import sbt.Def.taskKey
import sbt.Keys.*
import sbt.Keys.sourceDirectory

import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.model.Build
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.project.MavenProject
import org.apache.maven.tools.plugin.extractor.annotations.scanner.DefaultMojoAnnotationsScanner
import org.apache.maven.tools.plugin.extractor.annotations.JavaAnnotationsMojoDescriptorExtractor
import org.apache.maven.tools.plugin.extractor.MojoDescriptorExtractor
import org.apache.maven.tools.plugin.generator.GeneratorUtils.toComponentDependencies
import org.apache.maven.tools.plugin.generator.PluginDescriptorFilesGenerator
import org.apache.maven.tools.plugin.DefaultPluginToolsRequest
import org.codehaus.plexus.logging
import org.codehaus.plexus.util.ReflectionUtils.setVariableValueInObject

object SbtMavenPlugin extends AutoPlugin {

  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val mavenPluginGoalPrefix   = settingKey[String]("Maven Plugin goal prefix")
    val mavenVersion            = settingKey[String]("Maven version")
    val mavenPluginToolsVersion = settingKey[String]("Maven Plugin Tools version")

    val MavenConf = Configurations.config("scripted-maven").hide
    @transient
    val mavenClasspath  = taskKey[PathFinder]("")
    val mavenLaunchOpts =
      settingKey[Seq[String]]("options to pass to jvm launching Maven tasks")
    val scripted = inputKey[Unit]("")
  }

  import autoImport.*
  override lazy val globalSettings: Seq[Setting[?]] = Seq(
    mavenVersion            := "3.9.4",
    mavenPluginToolsVersion := "3.9.0",
    mavenLaunchOpts         := Seq()
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(mavenPluginSettings) ++
      dependenciesSettings ++
      scriptedMavenSettings

  private def mavenPluginSettings: Seq[Setting[?]] = Seq(
    Compile / resourceGenerators += generateMavenPluginXml.taskValue,
  )

  private def scriptedMavenSettings: Seq[Setting[?]] = Seq(
    ivyConfigurations += MavenConf,
    mavenClasspath := Def.task {
      PathFinder(SbtMavenPluginCompat.managedJars(MavenConf, classpathTypes.value, Keys.update.value))
    }.value,
    scripted / sourceDirectory := sourceDirectory.value / "maven-test",
    scripted                   := scriptedTask.evaluated,
    libraryDependencies ++= Seq(
      "org.apache.maven" % "apache-maven" % mavenVersion.value % MavenConf
    )
  )

  private lazy val dependenciesSettings: Seq[Setting[?]] = Seq(
    libraryDependencies ++= Seq(
      "org.apache.maven"              % "maven-core"               % mavenVersion.value            % Provided,
      "org.apache.maven"              % "maven-plugin-api"         % mavenVersion.value            % Provided,
      "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mavenPluginToolsVersion.value % Provided
    )
  )

  private def createPluginDescriptor(
      goalPrefix: String,
      id: ModuleID,
      info: ModuleInfo,
      artifacts: Seq[Artifact]
  ): PluginDescriptor = {
    val pluginDescriptor = new PluginDescriptor()
    pluginDescriptor.setName(info.nameFormal)
    pluginDescriptor.setDescription(info.description)
    pluginDescriptor.setGroupId(id.organization)
    pluginDescriptor.setArtifactId(id.name)
    pluginDescriptor.setVersion(id.revision)
    pluginDescriptor.setGoalPrefix(goalPrefix)
    pluginDescriptor.setDependencies(toComponentDependencies(scala.collection.JavaConverters.seqAsJavaList(artifacts)))
    pluginDescriptor
  }

  private def findRuntimeDependencies(
      report: UpdateReport,
      dependencies: Seq[ModuleID],
      crossVersion: ModuleID => ModuleID
  ): Seq[Artifact] = {
    val artifacts = report.configurations
      .filter { c => isAnalyzedDependency(c.configuration) }
      .flatMap { c => c.modules }
      .flatMap { m => m.artifacts }
      .map(_._1)

    dependencies
      .filter { p => isAnalyzedDependency(p.configurations.map(ConfigRef(_)).getOrElse(Compile)) }
      .map { dependency =>
        val moduleId = crossVersion(dependency)
        val artifact = artifacts.find { a => a.name == dependency.name }
        new DefaultArtifact(
          moduleId.organization,
          moduleId.name,
          moduleId.revision,
          moduleId.configurations.orNull,
          artifact.map { i => i.`type` }.getOrElse("jar"),
          artifact.flatMap { i => i.classifier }.getOrElse(""),
          null
        )
      }
  }

  private def createMavenProject(projectId: ModuleID, pluginXMLDirectory: File, classDirectory: File): MavenProject = {
    val build = new Build()
    build.setDirectory(pluginXMLDirectory.getAbsolutePath)
    build.setOutputDirectory(classDirectory.getAbsolutePath)

    val project = new MavenProject()
    project.setGroupId(projectId.organization)
    project.setArtifactId(projectId.name)
    project.setBuild(build)
    project.setArtifact(
      new DefaultArtifact(projectId.organization, projectId.name, projectId.revision, null, "jar", "", null)
    )
    project
  }

  private def createMojoExtractor(logger: logging.Logger): MojoDescriptorExtractor = {
    val extractor = new JavaAnnotationsMojoDescriptorExtractor()
    val scanner   = new DefaultMojoAnnotationsScanner
    scanner.enableLogging(logger)
    setVariableValueInObject(extractor, "mojoAnnotationsScanner", scanner)
    extractor
  }

  private val generateMavenPluginXml = Def.task {
    compile.value // generation must be started after compilation
    val crossVersion       = CrossVersion(scalaVersion.value, scalaBinaryVersion.value)
    val projectId          = if (crossPaths.value) crossVersion(projectID.value) else projectID.value
    val pluginXMLDirectory = (Compile / resourceManaged).value / "META-INF" / "maven"
    val project            = createMavenProject(projectId, pluginXMLDirectory, (Compile / classDirectory).value)
    val artifacts          = findRuntimeDependencies(update.value, allDependencies.value, crossVersion)
    val pluginDescriptor   = createPluginDescriptor(mavenPluginGoalPrefix.value, projectId, projectInfo.value, artifacts)
    val request            = new DefaultPluginToolsRequest(project, pluginDescriptor)
    val extractor          = createMojoExtractor(new SbtLogger(streams.value.log))
    val generator          = new PluginDescriptorFilesGenerator()

    extractor.execute(request).forEach { mojo => pluginDescriptor.addMojo(mojo) }
    generator.execute(pluginXMLDirectory, request)

    Seq(pluginXMLDirectory / "plugin.xml")
  }

  private def isAnalyzedDependency(configuration: ConfigRef) = {
    Seq(Compile, Runtime).contains(configuration)
  }

  private def scriptedTask = Def.inputTask {
    import sbt.complete.Parsers.*

    // Publish Maven Plugin into local Maven Repo
    publishM2.value

    val scriptedSourceDir = (scripted / sourceDirectory).value
    if (scriptedSourceDir.isDirectory && scriptedSourceDir.exists()) {
      val tests: Seq[File] = (OptSpace ~> StringBasic).?.parsed
        .fold(scriptedSourceDir.listFiles().toSeq.filter(_.isDirectory)) { dir =>
          Seq(scriptedSourceDir / dir)
        }
        .filter(testDir => (testDir / "test").exists)

      val results = tests.map { directory =>
        runTest(
          directory,
          mavenClasspath.value.get(),
          mavenLaunchOpts.value,
          streams.value.log
        )
      }
      results.collect {
        case (name, false) => name
      } match {
        case Nil         => // success
        case failedTests => sys.error(failedTests.mkString("Maven tests failed: ", ",", ""))
      }
    }
  }

  private def runTest(
      directory: File,
      classpath: Seq[File],
      opts: Seq[String],
      log: Logger
  ): (String, Boolean) = {
    log.info(s"${scala.Console.BOLD} Executing Maven: ${directory} ${scala.Console.RESET}")

    val executions = IO
      .readLines(directory / "test")
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.startsWith("#")) // comments

    val testDir = Files.createTempDirectory("maven-test-").toFile
    try {
      IO.copyDirectory(directory, testDir)

      val args = Seq(
        "-cp",
        classpath.map(_.getAbsolutePath).mkString(File.pathSeparator),
        s"-Dmaven.multiModuleProjectDirectory=${testDir.getAbsolutePath}"
      ) ++
        opts ++
        Seq(
          "org.apache.maven.cli.MavenCli",
          "--no-transfer-progress", // Do not show Maven download progress
        )

      log.info(
        s"Running maven test ${directory.getName} with arguments ${args.mkString(" ")}"
      )

      directory.getName -> executions.foldLeft(true) { (success, execution) =>
        if (success) {
          val mavenParams = execution.substring(execution.indexOf('>') + 1).trim
          log.info(s"Executing mvn ${mavenParams}")
          val rc = Fork.java(ForkOptions().withWorkingDirectory(testDir), args ++ mavenParams.split("\\s+"))
          if (execution.startsWith("->")) rc != 0 else rc == 0
        } else {
          false
        }
      }
    } finally {
      IO.delete(testDir)
    }
  }

}
