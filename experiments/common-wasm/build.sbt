import Dependencies.munit

lazy val scala212 = "2.12.16"
lazy val scala213 = "2.13.11"
lazy val supportedScalaVersions = List(scala212, scala213)

ThisBuild / scalaVersion     := scala212
ThisBuild / version          := "16.8.0-dev"
ThisBuild / organization     := "io.otoroshi.common"
ThisBuild / organizationName := "wasm"


lazy val playJsonVersion = "2.9.3"
lazy val playWsVersion = "2.8.19"
lazy val akkaVersion = "2.6.20"
lazy val akkaHttpVersion = "10.2.10"
lazy val metricsVersion = "4.2.12"
lazy val excludesJackson = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

lazy val root = (project in file("."))
  .settings(
    name := "common-wasm",
    crossScalaVersions := supportedScalaVersions,
    libraryDependencies ++= Seq(
      munit % Test,
      "com.typesafe.play"     %% "play-ws"        % playWsVersion % "provided",
      "com.typesafe.play"     %% "play-json"      % playJsonVersion % "provided",
      "com.typesafe.akka"     %% "akka-stream"    % akkaVersion % "provided",
      "com.typesafe.akka"     %% "akka-http"      % akkaHttpVersion % "provided",
      "com.typesafe.play"     %% "play-json-joda" % playJsonVersion % "provided",
      "commons-codec"         % "commons-codec"   % "1.16.0" % "provided",
      "net.java.dev.jna"      % "jna"             % "5.13.0" % "provided",
      "com.google.code.gson"  % "gson"            % "2.10" % "provided",
      "io.dropwizard.metrics" % "metrics-json"    % metricsVersion % "provided" excludeAll (excludesJackson: _*), // Apache 2.0
    ),
  )

assembly / test := {}
assembly / assemblyJarName := s"common-wasm_${scalaVersion.value.split("\\.").init.mkString(".")}-${version.value}.jar"
assembly / assemblyMergeStrategy := {
  case PathList("org", "apache", "commons", "logging", xs@_*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
