name := """otoroshi"""
organization := "fr.maif.otoroshi"
version := "1.5.0-dev"
scalaVersion := scalaLangVersion

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, PlayAkkaHttp2Support)
  .disablePlugins(PlayFilters)

lazy val scalaLangVersion    = "2.12.13"
// lazy val scalaLangVersion = "2.13.2"
// * https://github.com/propensive/kaleidoscope/issues/24
// * https://github.com/risksense/ipaddr/issues/11
lazy val metricsVersion      = "4.1.18"
lazy val acme4jVersion       = "2.11"
lazy val prometheusVersion   = "0.10.0"
lazy val playJsonVersion     = "2.8.1"
lazy val webAuthnVersion     = "1.7.0" // breaks jackson modules at 1.7.0
lazy val kubernetesVersion   = "8.0.2"
lazy val bouncyCastleVersion = "1.68"
lazy val pulsarVersion       = "2.6.3"
lazy val excludesJackson = Seq(ExclusionRule(organization = "com.fasterxml.jackson.core"), ExclusionRule(organization = "com.fasterxml.jackson.datatype"), ExclusionRule(organization = "com.fasterxml.jackson.dataformat"))

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.softwaremill.macwire"        %% "macros"                      % "2.3.7" % "provided",
  "com.typesafe.play"               %% "play-json"                   % playJsonVersion,
  "com.typesafe.play"               %% "play-json-joda"              % playJsonVersion,
  "com.github.etaty"                %% "rediscala"                   % "1.9.0",
  "com.github.gphat"                %% "censorinus"                  % "2.1.16",
  "com.typesafe.akka"               %% "akka-stream-kafka"           % "2.0.7",
  "com.spotify.metrics"              % "semantic-metrics-core"       % "1.1.7",
//  "io.dropwizard.metrics"    % "metrics-jvm"                 % metricsVersion,    // Apache 2.0
  "io.dropwizard.metrics"            % "metrics-jmx"                 % metricsVersion, // Apache 2.0
  "io.dropwizard.metrics"            % "metrics-json"                % metricsVersion, // Apache 2.0
  "io.prometheus"                    % "simpleclient_common"         % prometheusVersion, // Apache 2.0
  "io.prometheus"                    % "simpleclient_dropwizard"     % prometheusVersion, // Apache 2.0
  "com.auth0"                        % "java-jwt"                    % "3.14.0" excludeAll(excludesJackson: _*),
  "com.auth0"                        % "jwks-rsa"                    % "0.17.0" excludeAll(excludesJackson: _*), // https://github.com/auth0/jwks-rsa-java
  "com.nimbusds"                     % "nimbus-jose-jwt"             % "9.7",
  "de.svenkubiak"                    % "jBCrypt"                     % "0.4.3",
  "com.propensive"                  %% "kaleidoscope"                % "0.1.0",
  "io.github.classgraph"             % "classgraph"                  % "4.8.102",
  "com.risksense"                   %% "ipaddr"                      % "1.0.2",
  "com.yubico"                       % "webauthn-server-core"        % webAuthnVersion excludeAll(excludesJackson: _*),
  "com.yubico"                       % "webauthn-server-attestation" % webAuthnVersion excludeAll(excludesJackson: _*),
  "com.yubico"                       % "yubico-util"                 % webAuthnVersion excludeAll(excludesJackson: _*),
  "com.maxmind.geoip2"               % "geoip2"                      % "2.13.1",
  "com.blueconic"                    % "browscap-java"               % "1.3.3",
  "javax.xml.bind"                   % "jaxb-api"                    % "2.3.1", // https://stackoverflow.com/questions/48204141/replacements-for-deprecated-jpms-modules-with-java-ee-apis/48204154#48204154
  "com.sun.xml.bind"                 % "jaxb-core"                   % "2.3.0.1",
  //"com.sun.xml.bind"         % "jaxb-impl"                   % "2.3.2",
  "com.github.blemale"              %% "scaffeine"                   % "4.0.2",
  "org.shredzone.acme4j"             % "acme4j-client"               % acme4jVersion,
  "org.shredzone.acme4j"             % "acme4j-utils"                % acme4jVersion,
  "org.shredzone.acme4j"             % "acme4j"                      % acme4jVersion,
  "io.lettuce"                       % "lettuce-core"                % "6.0.2.RELEASE",
  "io.vertx"                         % "vertx-pg-client"             % "4.0.3",
  "com.jayway.jsonpath"              % "json-path"                   % "2.5.0",
  "com.cronutils"                    % "cron-utils"                  % "9.1.3",
  "commons-lang"                     % "commons-lang"                % "2.6",
  "com.datastax.oss"                 % "java-driver-core"            % "4.5.1",
  "org.gnieh"                       %% "diffson-play-json"           % "4.0.3" excludeAll ExclusionRule(organization = "com.typesafe.akka"),
  "org.scala-lang"                   % "scala-compiler"              % scalaLangVersion,
  "org.scala-lang"                   % "scala-library"               % scalaLangVersion,
  "io.kubernetes"                    % "client-java"                 % kubernetesVersion,
  "io.kubernetes"                    % "client-java-extended"        % kubernetesVersion,
  "org.bouncycastle"                 % "bcpkix-jdk15on"              % bouncyCastleVersion,
  "org.bouncycastle"                 % "bcprov-ext-jdk15on"          % bouncyCastleVersion,
  "org.bouncycastle"                 % "bcprov-jdk15on"              % bouncyCastleVersion,
  "com.sksamuel.pulsar4s"           %% "pulsar4s-play-json"          % pulsarVersion,
  "com.sksamuel.pulsar4s"           %% "pulsar4s-core"               % pulsarVersion,
  "com.sksamuel.pulsar4s"           %% "pulsar4s-akka-streams"       % pulsarVersion,
  "org.jsoup"                        % "jsoup"                       % "1.13.1",
  "com.clever-cloud"                 % "biscuit-java"                % "1.0.0",
  "org.opensaml"                     % "opensaml-core"               % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-api"           % "4.0.1",
  //"org.opensaml"                     % "opensaml-xmlsec-impl"        % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-impl"          % "4.0.1",
  // fix multiple CVEs
  "com.fasterxml.jackson.core"       % "jackson-databind"            % "2.10.5.1",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml"     % "2.10.5",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"     % "2.10.5",
  "com.fasterxml.jackson.datatype"   % "jackson-datatype-jdk8"     % "2.10.5",
  "org.yaml"                         % "snakeyaml"                   % "1.28",
// tests
  "org.scalatestplus.play"          %% "scalatestplus-play"          % "5.1.0" % Test,
  // do not update because the feature is deprecated and will be removed
  "org.reactivemongo"               %% "reactivemongo"               % "0.20.13",
  "org.iq80.leveldb"                 % "leveldb"                     % "0.12"
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
  case PathList("org", "apache", "commons", "lang", xs @ _*)          => MergeStrategy.first
  case PathList("org", "apache", "commons", "collections", xs @ _*)   => MergeStrategy.first
  case PathList("io", "sundr", xs @ _*)                               => MergeStrategy.first
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("reference-overrides.conf")   => MergeStrategy.concat
  case PathList(ps @ _*) if ps.contains("field_mask.proto")           => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("plugin.proto")               =>
    MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
  case PathList(ps @ _*) if ps.contains("descriptor.proto")           =>
    MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
  case PathList(ps @ _*) if ps.contains("module-info.class")          => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("ModuleUtil.class")           => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("GuardedBy.class")            => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("nowarn$.class")              => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("nowarn.class")               => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("reflection-config.json")     => MergeStrategy.first // ???
  case PathList(ps @ _*) if ps.contains("metadata.json")              => MergeStrategy.first // ??? hope webauthn comes first
  case PathList(ps @ _*) if ps.contains("version.conf")               => MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("native-image.properties")    => MergeStrategy.first
  case PathList(ps @ _*) if ps.contains("public-suffix-list.txt")     => MergeStrategy.first
  case path if path.contains("org/bouncycastle")                      => MergeStrategy.first
  case PathList("javax", xs @ _*)                                     => MergeStrategy.first
  case x                                                              =>
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
