name := """otoroshi"""
organization := "fr.maif"
version := "1.0.0"
scalaVersion := "2.11.8"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  ws,
  cache,
  filters,
  "org.gnieh"                %% "diffson-play-json"        % "2.1.0" excludeAll (ExclusionRule(organization = "com.typesafe.akka")),
  "org.iq80.leveldb"         % "leveldb"                   % "0.9",
  "com.softwaremill.macwire" %% "macros"                   % "2.3.0" % "provided",
  "com.typesafe.akka"        %% "akka-http-core"           % "2.4.11",
  "com.typesafe.akka"        %% "akka-stream-kafka"        % "0.17",
  "com.github.etaty"         %% "rediscala"                % "1.8.0",
  "com.github.mkroli"        %% "dns4s-akka"               % "0.10" excludeAll (ExclusionRule(organization = "com.typesafe.akka")),
  "com.github.gphat"         %% "censorinus"               % "2.1.6",
  "io.dropwizard.metrics"    % "metrics-core"              % "3.1.2",
  "com.auth0"                % "java-jwt"                  % "3.1.0",
  "com.yubico"               % "u2flib-server-core"        % "0.16.0",
  "com.yubico"               % "u2flib-server-attestation" % "0.16.0",
  "de.svenkubiak"            % "jBCrypt"                   % "0.4.1",
  "org.typelevel"            %% "cats"                     % "0.9.0",
  "com.chuusai"              %% "shapeless"                % "2.3.2",
  "org.scalatestplus.play"   %% "scalatestplus-play"       % "1.5.1" % Test,
  "com.datastax.cassandra"   % "cassandra-driver-core"     % "3.3.0" classifier "shaded" excludeAll (
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
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (dist in Compile).value
  (assembly in Compile).value
}
