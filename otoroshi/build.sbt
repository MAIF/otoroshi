name := """otoroshi"""
organization := "fr.maif.otoroshi"
version := "1.4.23-dev"
scalaVersion := "2.12.11"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, PlayAkkaHttp2Support)
  .disablePlugins(PlayFilters)

lazy val metricsVersion = "4.1.5"

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.softwaremill.macwire" %% "macros"                   % "2.3.3" % "provided",
  "com.typesafe.play"        %% "play-json"                % "2.8.1",
  "com.typesafe.play"        %% "play-json-joda"           % "2.8.1",
  "com.github.etaty"         %% "rediscala"                % "1.9.0",
  "com.github.gphat"         %% "censorinus"               % "2.1.15",
  "com.typesafe.akka"        %% "akka-stream-kafka"        % "2.0.2",
  "io.dropwizard.metrics"    % "metrics-core"              % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-jvm"               % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-jmx"               % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"    % "metrics-json"              % metricsVersion, // Apache 2.0
  "io.prometheus"            % "simpleclient_common"       % "0.8.1", // Apache 2.0
  "io.prometheus"            % "simpleclient_dropwizard"   % "0.8.1", // Apache 2.0
  "com.auth0"                % "java-jwt"                  % "3.10.1",
  "de.svenkubiak"            % "jBCrypt"                   % "0.4.1",
  "com.propensive"           %% "kaleidoscope"             % "0.1.0",
  "io.github.classgraph"     % "classgraph"                % "4.8.65",
  "com.auth0"                % "jwks-rsa"                  % "0.11.0", // https://github.com/auth0/jwks-rsa-java
  "com.nimbusds"             % "nimbus-jose-jwt"           % "8.10",
  "com.risksense"            %% "ipaddr"                   % "1.0.2",
  "com.yubico"               % "webauthn-server-core"      % "1.6.1",
  "com.yubico"               % "webauthn-server-attestation" % "1.6.1",
  "com.yubico"               % "yubico-util"               % "1.6.1",
  "com.maxmind.geoip2"       % "geoip2"                    % "2.13.1",
  "com.blueconic"            % "browscap-java"             % "1.2.15",
  "javax.xml.bind"           % "jaxb-api"                  % "2.3.1", // https://stackoverflow.com/questions/48204141/replacements-for-deprecated-jpms-modules-with-java-ee-apis/48204154#48204154
  "com.sun.xml.bind"         % "jaxb-core"                 % "2.3.0.1",
  //"com.sun.xml.bind"         % "jaxb-impl"                 % "2.3.2",
  "com.github.blemale"       %% "scaffeine"                % "3.1.0",
  "org.shredzone.acme4j"     % "acme4j-client"             % "2.8",
  "org.shredzone.acme4j"     % "acme4j-utils"              % "2.8",
  "org.shredzone.acme4j"     % "acme4j"                    % "2.8",
  "io.lettuce"               % "lettuce-core"              % "5.2.2.RELEASE",
  "com.jayway.jsonpath"      % "json-path"                 % "2.4.0",
  "com.cronutils"            % "cron-utils"                % "9.0.2",
  "commons-lang"             % "commons-lang"              % "2.6",
  // "com.datastax.oss"         % "java-driver-core-shaded"   % "4.5.1",
  "com.datastax.oss"         % "java-driver-core"   % "4.5.1",
  "org.scalatestplus.play"   %% "scalatestplus-play"       % "5.0.0" % Test,
  // need to be updated, but later
  "org.scala-lang"           %  "scala-compiler"            % "2.12.11",
  "org.scala-lang"           %  "scala-library"             % "2.12.11",
  "org.gnieh"                %% "diffson-play-json"         % "2.2.6" excludeAll (ExclusionRule(organization = "com.typesafe.akka")),
  // do not update because the feature is deprecated and will be removed
  "com.yubico"               %  "u2flib-server-core"        % "0.16.0",
  "com.yubico"               %  "u2flib-server-attestation" % "0.16.0",
  "org.reactivemongo"        %% "reactivemongo"             % "0.20.3",
  "org.iq80.leveldb"         %  "leveldb"                   % "0.12",
  //"io.kubernetes"            % "client-java"               % "7.0.0",
  //"io.kubernetes"            % "client-java-extended"      % "7.0.0",
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

resolvers += "bintray" at "https://jcenter.bintray.com"

PlayKeys.devSettings := Seq("play.server.http.port" -> "9999")

sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

// scalafmtVersion in ThisBuild := "1.2.0"

parallelExecution in Test := false

bintrayOrganization := Some("maif")
bintrayRepository := "maven"
licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))

// assembly
mainClass in assembly := Some("play.core.server.ProdServerStart")
test in assembly := {}
assemblyJarName in assembly := "otoroshi.jar"
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)
assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", "logging", xs @ _*)       => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("reference-overrides.conf")   => MergeStrategy.concat
  case PathList(ps @ _*) if ps.contains("module-info.class")          => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("ModuleUtil.class")           => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("reflection-config.json")     => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("metadata.json")              => MergeStrategy.first // ??? hope webauthn comes first
  case PathList("javax", xs @ _*)                                     => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (dist in Compile).value
  (assembly in Compile).value
}

import play.sbt.PlayImport.PlayKeys._

packagedArtifacts in publish := {
  val artifacts: Map[sbt.Artifact, java.io.File] = (packagedArtifacts in publishLocal).value
  val assets: java.io.File                       = (playPackageAssets in Compile).value
  artifacts + (Artifact(moduleName.value, "jar", "jar", "assets") -> assets)
}

// should fix issues with https targets on jdk8u161 and beyond
// if production fails wihtout reason with weird TLS issues on JDK8, just update the following dependency to latest version ... trust me
libraryDependencies += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.10"
javaAgents += "org.mortbay.jetty.alpn"          % "jetty-alpn-agent" % "2.0.10" % "runtime"
