/*
 * Copyright (C) from 2023 The sbt contributors <https://github.com/sbt>
 */

package com.github.sbt.maven

import java.io.File

import sbt.Classpaths
import sbt.Configuration
import sbt.Keys.fileConverter
import sbt.Keys.Classpath
import sbt.UpdateReport

private[maven] object SbtMavenPluginCompat {
  inline def managedJars(
      config: Configuration,
      jarTypes: Set[String],
      up: UpdateReport
  ): Seq[File] = {
    val converter = fileConverter.value
    Classpaths.managedJars(config, jarTypes, up, converter).map(x => converter.toPath(x.data).toFile)
  }
}
