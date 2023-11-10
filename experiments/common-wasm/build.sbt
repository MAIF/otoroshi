import Dependencies.munit

lazy val scala212 = "2.12.16"
lazy val scala213 = "2.13.11"
lazy val supportedScalaVersions = List(scala212, scala213)

ThisBuild / scalaVersion     := scala212
ThisBuild / version          := "16.11.1-dev"
ThisBuild / organization     := "io.otoroshi.common"
ThisBuild / organizationName := "wasm"

inThisBuild(
  List(
    description := "Library to run wasm vm in a play scala app",
    startYear := Some(2023),
    organization := "io.otoroshi.common",
    homepage := Some(url("https://github.com/MAIF/otoroshi/experiments/common-wasm")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/MAIF/otoroshi"),
        "scm:git@github.com:MAIF/otoroshi.git"
      )
    ),
    publishMavenStyle := true,
    developers := List(
      Developer(
        "mathieuancelin",
        "Mathieu Ancelin",
        "mathieu.ancelin@serli.com",
        url("https://github.com/mathieuancelin")
      )
    )
  )
)


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
    githubOwner := "MAIF",
    githubRepository := "otoroshi",
    githubTokenSource := TokenSource.Environment("GITHUB_PACKAGES_TOKEN"),
    libraryDependencies ++= Seq(
      munit % Test,
      "com.typesafe.play"     %% "play-ws"        % playWsVersion % "provided",
      "com.typesafe.play"     %% "play-json"      % playJsonVersion % "provided",
      "com.typesafe.akka"     %% "akka-stream"    % akkaVersion % "provided",
      "com.typesafe.akka"     %% "akka-http"      % akkaHttpVersion % "provided",
      "com.typesafe.play"     %% "play-json-joda" % playJsonVersion % "provided",
      "com.auth0"             % "java-jwt"        % "4.2.0" excludeAll (excludesJackson: _*),
      "commons-codec"         % "commons-codec"   % "1.16.0" % "provided",
      "net.java.dev.jna"      % "jna"             % "5.13.0" % "provided",
      "com.google.code.gson"  % "gson"            % "2.10" % "provided",
      "io.dropwizard.metrics" % "metrics-json"    % metricsVersion % "provided" excludeAll (excludesJackson: _*), // Apache 2.0
    ),
  )

assembly / test := {}
assembly / assemblyJarName := s"common-wasm_${scalaVersion.value.split("\\.").init.mkString(".")}-${version.value}.jar"
