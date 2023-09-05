import Dependencies.munit

ThisBuild / scalaVersion     := "2.12.16"
ThisBuild / version          := "1.0.0-SNAPSHOT"
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
    libraryDependencies ++= Seq(
      munit % Test,
      "com.typesafe.play"     %% "play-ws"        % playWsVersion,
      "com.typesafe.play"     %% "play-json"      % playJsonVersion,
      "com.typesafe.akka"     %% "akka-stream"    % akkaVersion,
      "com.typesafe.akka"     %% "akka-http"      % akkaHttpVersion,
      "com.typesafe.play"     %% "play-json-joda" % playJsonVersion,
      "com.github.blemale"    %% "scaffeine"      % "4.0.2",
      "com.jayway.jsonpath"   % "json-path"       % "2.7.0",
      "commons-lang"          % "commons-lang"    % "2.6",
      "commons-codec"         % "commons-codec"   % "1.16.0",
      "net.java.dev.jna"      % "jna"             % "5.13.0",
      "com.google.code.gson"  % "gson"            % "2.10",
      "io.dropwizard.metrics" % "metrics-json"    % metricsVersion excludeAll (excludesJackson: _*), // Apache 2.0
    )
  )
