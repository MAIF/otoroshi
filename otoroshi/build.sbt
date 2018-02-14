name := """otoroshi"""
organization := "fr.maif"
version := "1.0.3"
scalaVersion := "2.12.4"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, PlayNettyServer)
  .disablePlugins(PlayAkkaHttpServer)
  //.disablePlugins(PlayNettyServer)

libraryDependencies ++= Seq(
  ws,
  filters,
  "org.gnieh"                %% "diffson-play-json"        % "2.2.5" excludeAll (ExclusionRule(organization = "com.typesafe.akka")),
  "org.iq80.leveldb"         % "leveldb"                   % "0.9",
  "com.softwaremill.macwire" %% "macros"                   % "2.3.0" % "provided",
  "com.typesafe.akka"        %% "akka-http-core"           % "10.0.11",
  "com.typesafe.play"        %% "play-json"                % "2.6.8",
  "com.typesafe.play"        %% "play-json-joda"           % "2.6.8",
  "com.typesafe.akka"        %% "akka-stream-kafka"        % "0.18",
  "com.github.etaty"         %% "rediscala"                % "1.8.0",
  "com.github.gphat"         %% "censorinus"               % "2.1.8",
  "io.dropwizard.metrics"    % "metrics-core"              % "4.0.2",
  "com.auth0"                % "java-jwt"                  % "3.3.0",
  "com.yubico"               % "u2flib-server-core"        % "0.16.0",
  "com.yubico"               % "u2flib-server-attestation" % "0.16.0",
  "de.svenkubiak"            % "jBCrypt"                   % "0.4.1",
  "org.scalatestplus.play"   %% "scalatestplus-play"       % "3.1.2" % Test,
  "com.datastax.cassandra"   % "cassandra-driver-core"     % "3.4.0" classifier "shaded" excludeAll (
    ExclusionRule(organization = "io.netty"),
    ExclusionRule(organization = "com.typesafe.akka")
  )
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

resolvers += "bintray" at "http://jcenter.bintray.com"

PlayKeys.devSettings := Seq("play.server.http.port" -> "9999")

sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

scalafmtVersion in ThisBuild := "1.2.0"

// assembly
mainClass in assembly := Some("play.core.server.ProdServerStart")
test in assembly := {}
assemblyJarName in assembly := "otoroshi.jar"
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)
assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", "logging", xs @ _*)       => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("reference-overrides.conf")   => MergeStrategy.concat
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (dist in Compile).value
  (assembly in Compile).value
}
