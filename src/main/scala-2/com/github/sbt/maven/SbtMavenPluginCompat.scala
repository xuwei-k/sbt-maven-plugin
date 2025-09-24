/*
 * Copyright (C) from 2023 The sbt contributors <https://github.com/sbt>
 */

package com.github.sbt.maven

import java.io.File

import sbt.Classpaths
import sbt.Configuration
import sbt.Keys.Classpath
import sbt.UpdateReport

private[maven] object SbtMavenPluginCompat {
  def managedJars(
      config: Configuration,
      jarTypes: Set[String],
      up: UpdateReport
  ): Seq[File] =
    Classpaths.managedJars(config, jarTypes, up).map(_.data)
}
