ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.opengpgpu"

val chiselVersion = "6.2.0"

lazy val root = (project in file("."))
  .settings(
    name := "OpenGPGPU-Memory-Model",
    target := baseDirectory.value / "build" / "target",
    Compile / scalaSource := baseDirectory.value / "src",
    Test / scalaSource := baseDirectory.value / "tests",
    Compile / unmanagedSourceDirectories += baseDirectory.value / "utils",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % "test"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
Test / parallelExecution := false
