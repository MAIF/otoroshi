import xerial.sbt.Sonatype.*

name := """otoroshi"""
organization := "fr.maif"
version := "17.5.0-dev"
scalaVersion := scalaLangVersion

ThisBuild / evictionErrorLevel := Level.Warn

//dependencyOverrides ++= Seq(
//  "joda-time" % "joda-time" % "2.14.0"
//)

inThisBuild(
  List(
    description := "Lightweight api management on top of a modern http reverse proxy",
    startYear := Some(2017),
    organization := "fr.maif",
    homepage := Some(url("https://github.com/MAIF/otoroshi")),
    licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    sonatypeProfileName := "fr.maif",
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
      ),
      Developer(
        "quentinovega",
        "Quentin Aubert",
        "",
        url("https://github.com/quentinovega")
      ),
      Developer(
        "Zwiterrion",
        "Etienne Anne",
        "",
        url("https://github.com/Zwiterrion")
      ),
      Developer(
        "baudelotphilippe",
        "Philippe Baudelot",
        "",
        url("https://github.com/baudelotphilippe")
      )
    )
  )
)

//lazy val root = (project in file("."))
//  .enablePlugins(PlayScala, PlayAkkaHttp2Support)
//  .disablePlugins(PlayFilters)

enablePlugins(PlayScala)
disablePlugins(PlayFilters)

lazy val scalaLangVersion    = "2.13.16"
val playVersion = "3.0.8"
lazy val metricsVersion          = "4.2.33"
lazy val acme4jVersion           = "3.5.1" // "2.14"
lazy val prometheusVersion       = "0.16.0"
lazy val playJsonVersion         = "3.0.5"
lazy val webAuthnVersion         = "2.7.0" //"1.7.0" //"2.1.0"
lazy val kubernetesVersion       = "16.0.1"
lazy val bouncyCastleVersion     = "1.78.1"
lazy val pulsarVersion           = "2.12.0.1"
lazy val openTelemetryVersion    = "1.52.0"
lazy val jacksonVersion          = "2.19.2"
lazy val pekkoVersion            = "1.1.5"
lazy val pekkoHttpVersion        = "1.2.0"
lazy val pekkoConnectorsVersion  = "1.1.0"
lazy val reactorNettyVersion     = "1.2.8"
lazy val nettyVersion            = "4.2.3.Final"
lazy val excludesJackson         = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)
lazy val excludeScalaJava8Compat = Seq(
  ExclusionRule(organization = "org.scala-lang.modules")
)
lazy val excludeSlf4jAndJackson  = excludesJackson ++ Seq(
  ExclusionRule(organization = "org.slf4j")
)

dependencyOverrides ++= Seq(
  "org.apache.pekko" %% "pekko-actor"         % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"        % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j"         % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-typed"   % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
  "org.apache.pekko" %% "pekko-http"          % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-core"     % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-xml"      % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-parsing"       % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
  "org.scala-lang.modules" %% "scala-xml" % "2.4.0"
)

// Migrated from Akka to Pekko

libraryDependencies ++= Seq(
  ws,
  filters,
  "com.softwaremill.macwire"        %% "macros"                                    % "2.6.6" % "provided",
  "org.playframework"               %% "play-json"                                 % playJsonVersion,
  "org.playframework"               %% "play-json-joda"                            % playJsonVersion,
  "joda-time"                        % "joda-time"                                 % "2.14.0",
  "io.github.rediscala" %% "rediscala" % "1.17.0",
  "com.github.gphat"                %% "censorinus"                                % "2.1.16",
  "org.apache.pekko"                %% "pekko-connectors-kafka"                    % pekkoConnectorsVersion,
  "org.apache.pekko"                %% "pekko-connectors-s3"                       % pekkoConnectorsVersion,
  "org.apache.pekko" %% "pekko-actor"       % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"      % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j"       % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-typed"  % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
  "org.apache.pekko" %% "pekko-http"         % pekkoHttpVersion,
  "org.apache.pekko"                %% "pekko-http-xml"                            % pekkoHttpVersion,
  "com.spotify.metrics"              % "semantic-metrics-core"                     % "1.2.0",
  "io.dropwizard.metrics"            % "metrics-jmx"                               % metricsVersion excludeAll (excludesJackson *), // Apache 2.0
  "io.dropwizard.metrics"            % "metrics-json"                              % metricsVersion excludeAll (excludesJackson *), // Apache 2.0
  "io.prometheus"                    % "simpleclient_common"                       % prometheusVersion excludeAll (excludesJackson *), // Apache 2.0
  "io.prometheus"                    % "simpleclient_dropwizard"                   % prometheusVersion excludeAll (excludesJackson *), // Apache 2.0
  "com.auth0"                        % "java-jwt"                                  % "4.5.0" excludeAll (excludesJackson *),
  "com.auth0"                        % "jwks-rsa"                                  % "0.22.2" excludeAll (excludesJackson *), // https://github.com/auth0/jwks-rsa-java
  "com.nimbusds"                     % "nimbus-jose-jwt"                           % "9.48",
  "de.svenkubiak"                    % "jBCrypt"                                   % "0.4.3",
  "com.propensive"                  %% "kaleidoscope-core"                         % "0.5.0",
  "io.github.classgraph"             % "classgraph"                                % "4.8.181" excludeAll (excludesJackson *),
  "com.comcast"                     %% "ip4s-core"                                 % "3.7.0",
  "com.yubico"                       % "webauthn-server-core"                      % webAuthnVersion excludeAll (excludesJackson *),
  "com.yubico"                       % "webauthn-server-attestation"               % webAuthnVersion excludeAll (excludesJackson *),
  "com.yubico"                       % "yubico-util"                               % webAuthnVersion excludeAll (excludesJackson *),
  "com.maxmind.geoip2"               % "geoip2"                                    % "3.0.2",
  "com.blueconic"                    % "browscap-java"                             % "1.5.1",
  "javax.xml.bind"                   % "jaxb-api"                                  % "2.3.1", // https://stackoverflow.com/questions/48204141/replacements-for-deprecated-jpms-modules-with-java-ee-apis/48204154#48204154
  "com.sun.xml.bind"                 % "jaxb-core"                                 % "2.3.0.1",
  "com.github.blemale" %% "scaffeine" % "5.3.0",
  "org.shredzone.acme4j"             % "acme4j-client"                             % acme4jVersion excludeAll (excludeSlf4jAndJackson *),
  "io.lettuce"                       % "lettuce-core"                              % "6.7.1.RELEASE" excludeAll (excludesJackson *),
  "io.vertx"                         % "vertx-pg-client"                           % "4.5.16",
  "com.ongres.scram"                 % "common"                                    % "2.1",
  "com.ongres.scram"                 % "client"                                    % "2.1",
  "com.jayway.jsonpath"              % "json-path"                                 % "2.9.0",
  "com.cronutils"                    % "cron-utils"                                % "9.2.1",
  "commons-lang"                     % "commons-lang"                              % "2.6",
  "com.datastax.oss"                 % "java-driver-core"                          % "4.17.0" excludeAll (excludesJackson *),
  "org.gnieh"                       %% "diffson-play-json"                         % "4.6.0" excludeAll ExclusionRule(organization = "org.apache.pekko"),
  "org.scala-lang"                   % "scala-compiler"                            % scalaLangVersion,
  "org.scala-lang"                   % "scala-library"                             % scalaLangVersion,
  "org.scala-lang"                   % "scala-reflect"                             % scalaLangVersion,
  "io.kubernetes"                    % "client-java"                               % kubernetesVersion excludeAll (excludesJackson *),
  "io.kubernetes"                    % "client-java-extended"                      % kubernetesVersion excludeAll (excludesJackson *),
  "org.bouncycastle"                 % "bcpkix-jdk18on"                            % bouncyCastleVersion excludeAll (excludesJackson *),
  "org.bouncycastle"                 % "bcprov-ext-jdk18on"                        % bouncyCastleVersion excludeAll (excludesJackson *),
  "org.bouncycastle"                 % "bcprov-jdk18on"                            % bouncyCastleVersion excludeAll (excludesJackson *),
  "com.clever-cloud.pulsar4s"       %% "pulsar4s-core"                             % pulsarVersion excludeAll (excludesJackson *),
  "com.clever-cloud.pulsar4s"       %% "pulsar4s-pekko-streams"                    % pulsarVersion excludeAll (excludesJackson *),
  "org.jsoup"                        % "jsoup"                                     % "1.21.1",
  "org.biscuitsec"                   % "biscuit"                                   % "4.0.1",
  "org.opensaml"                     % "opensaml-core"                             % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-api"                         % "4.0.1",
  //"org.opensaml"                     % "opensaml-xmlsec-impl"        % "4.0.1",
  "org.opensaml"                     % "opensaml-saml-impl"                        % "4.0.1",
  "org.openjdk.jol"                  % "jol-core"                                  % "0.17",
  "org.typelevel"                   %% "squants"                                   % "1.8.3" excludeAll (excludesJackson *),
  // fix multiple CVEs
  "com.fasterxml.jackson.core"       % "jackson-core"                              % jacksonVersion,
  "com.fasterxml.jackson.core"       % "jackson-annotations"                       % jacksonVersion,
  "com.fasterxml.jackson.core"       % "jackson-databind"                          % jacksonVersion,
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml"                   % jacksonVersion,
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"                   % jacksonVersion,
  "com.fasterxml.jackson.datatype"   % "jackson-datatype-jsr310"                   % jacksonVersion,
  "com.fasterxml.jackson.module"    %% "jackson-module-scala"                      % jacksonVersion,
  "org.yaml"                         % "snakeyaml"                                 % "1.33" excludeAll (excludesJackson *),
  // "com.arakelian"                    % "java-jq"                                   % "1.3.0" excludeAll (excludesJackson: _*),
  "io.opentelemetry"                 % "opentelemetry-api"                         % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-bom"                         % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-context"                     % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-sdk"                         % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-sdk-common"                  % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-sdk-logs"                    % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-sdk-metrics"                 % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-sdk-trace"                   % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-exporter-logging"            % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-exporter-otlp"               % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-exporter-zipkin"             % openTelemetryVersion excludeAll (excludesJackson *),
  "io.opentelemetry"                 % "opentelemetry-exporter-sender-okhttp"      % openTelemetryVersion excludeAll (excludesJackson *),
  // "io.opentelemetry"                 % "opentelemetry-exporter-prometheus"         % "1.28.0-alpha" excludeAll (excludesJackson: _*),
//  "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0"        % "1.28.0-alpha" excludeAll (excludesJackson *),
//  "com.amazonaws"                    % "aws-java-sdk-secretsmanager"               % "1.12.326" excludeAll (excludesJackson *),
//  "org.apache.logging.log4j"         % "log4j-api"                                 % "2.19.0",
//  "org.sangria-graphql"             %% "sangria"                                   % "3.4.0",
  "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0"        % "1.28.0-alpha" excludeAll (excludesJackson *),
  "com.amazonaws"                    % "aws-java-sdk-secretsmanager"               % "1.12.788" excludeAll (excludesJackson *),
  "org.apache.logging.log4j"         % "log4j-api"                                 % "2.25.1",
  "org.sangria-graphql"             %% "sangria"                                   % "3.5.3",
  "org.bigtesting"                   % "routd"                                     % "1.0.7",
  "com.nixxcode.jvmbrotli"           % "jvmbrotli"                                 % "0.2.0",
  "io.azam.ulidj"                    % "ulidj"                                     % "1.1.0",
  // Updated to use Pekko-based wasm4s with Scala 3 support and comprehensive integration tests
  "fr.maif"                         %% "wasm4s"                                    % "5.0.0-SNAPSHOT" classifier "bundle",
  "com.google.crypto.tink"           % "tink"                                      % "1.18.0",
  "org.reflections"                  % "reflections"                               % "0.10.2",
  "org.json4s"                      %% "json4s-jackson"                            % "4.0.7",
  "org.json4s"                      %% "json4s-ast"                                % "4.0.7",
  "org.json4s"                      %% "json4s-ext"                                % "4.0.7",
  // using a custom one right now as current build is broken
  //   "org.extism.sdk"                   % "extism"                                    % "0.3.2",
  /*"org.sangria-graphql"             %% "sangria-play-json"              % "2.0.1" excludeAll ExclusionRule(
    organization = "com.typesafe.play"
  )*/ // TODO - check if needed
  // new http stack ;)
  "io.projectreactor.netty"          % "reactor-netty-core"                        % reactorNettyVersion,
  "io.projectreactor.netty"          % "reactor-netty-http"                        % reactorNettyVersion,
  "io.netty"                         % "netty-transport-native-kqueue"             % nettyVersion,
  "io.netty"                         % "netty-transport-native-kqueue"             % nettyVersion classifier "osx-aarch_64" classifier "osx-x86_64",
  "io.netty"                         % "netty-transport-native-epoll"              % nettyVersion,
  "io.netty"                         % "netty-transport-native-epoll"              % nettyVersion classifier "linux-x86_64" classifier "linux-aarch_64",
  "io.netty.incubator"               % "netty-incubator-transport-native-io_uring" % "0.0.26.Final",
  "io.netty.incubator"               % "netty-incubator-transport-native-io_uring" % "0.0.26.Final" classifier "linux-x86_64" classifier "linux-aarch_64",
  "io.netty.incubator"               % "netty-incubator-codec-native-quic"         % "0.0.73.Final",
  "io.netty.incubator"               % "netty-incubator-codec-native-quic"         % "0.0.73.Final" classifier "linux-x86_64" classifier "osx-x86_64",
  "io.netty.incubator"               % "netty-incubator-codec-http3"               % "0.0.30.Final",
  // tests
  "org.scalatestplus.play"          %% "scalatestplus-play"                        % "5.1.0" % Test,
  "com.networknt"                    % "json-schema-validator"                     % "1.3.0" excludeAll (
    ExclusionRule("org.slf4j"),
    ExclusionRule(organization = "com.fasterxml.jackson.core"),
    ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
    ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
  ),
  "jakarta.jms"                      % "jakarta.jms-api"                           % "3.1.0",
  "org.apache.activemq"              % "artemis-jakarta-client"                    % "2.41.0" excludeAll (
    ExclusionRule("org.slf4j"),
    ExclusionRule(organization = "com.fasterxml.jackson.core"),
    ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
    ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
  )
  // https://github.com/mvel/mvel
  // "org.mvel"                         % "mvel2"                                     % "2.5.2.Final"
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

// resolvers += "jitpack" at "https://jitpack.io"

PlayKeys.devSettings := Seq("play.server.http.port" -> "9999")

// sources in (Compile, doc) := Seq.empty
// publishArtifact in (Compile, packageDoc) := false
// scalafmtVersion in ThisBuild := "1.2.0"

Test / parallelExecution := false
IntegrationTest / testForkedParallel := false

usePgpKeyHex("4EFDC6FC2DEC936B13B7478C2F8C0F4E1D397E7F")
sonatypeProjectHosting := Some(GitHubHosting("MAIF", "otoroshi", "mathieu.ancelin@serli.com"))
sonatypeRepository := "https://ossrh-staging-api.central.sonatype.com/service/local/"
sonatypeCredentialHost := sonatypeCentralHost
licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))
//githubOwner := "MAIF"
//githubRepository := "otoroshi"
// githubTokenSource := TokenSource.Environment("GITHUB_PACKAGES_TOKEN")

// assembly
assembly /mainClass := Some("play.core.server.ProdServerStart")
assembly / test := {}
assembly /assemblyJarName := "otoroshi.jar"
assembly / fullClasspath += Attributed.blank(PlayKeys.playPackageAssets.value)
assembly / assemblyMergeStrategy := {
  case path if path.contains("com/upokecenter/util") => MergeStrategy.first
  case path if path.contains("org/slf4j/impl") => MergeStrategy.first
  case path if path.contains("edu/umd/cs/findbugs/annotations") => MergeStrategy.first
  case PathList("scala", xs@_*) => MergeStrategy.first
  case PathList("org", "apache", "commons", "logging", xs@_*) => MergeStrategy.first
  case PathList("org", "apache", "commons", "lang", xs@_*) => MergeStrategy.first
  case PathList("org", "apache", "commons", "collections", xs@_*) => MergeStrategy.first
  case PathList(
  "org",
  "apache",
  "maven",
  "surefire",
  "shade",
  "org",
  "apache",
  "maven",
  "shared",
  "utils",
  "StringUtils.class"
  ) =>
    MergeStrategy.first
  case PathList("io", "sundr", xs@_*) => MergeStrategy.first
  case PathList("com", "sun", "xml", xs@_*) => MergeStrategy.first
  case PathList("com", "sun", "istack", xs@_*) => MergeStrategy.first
  case PathList(ps@_*) if ps.last == "io.netty.versions.properties" => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("reference-overrides.conf") => MergeStrategy.concat
  case PathList(ps@_*) if ps.contains("field_mask.proto") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("plugin.proto") =>
    MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
  case PathList(ps@_*) if ps.contains("descriptor.proto") =>
    MergeStrategy.first // ??? not sure if it uses the latest version for biscuit
  case PathList(ps@_*) if ps.contains("module-info.class") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("ModuleUtil.class") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("GuardedBy.class") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("nowarn$.class") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("nowarn.class") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("reflection-config.json") => MergeStrategy.first // ???
  case PathList(ps@_*) if ps.contains("metadata.json") => MergeStrategy.first // ??? hope webauthn comes first
  case PathList(ps@_*) if ps.contains("version.conf") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("any.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("api.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("duration.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("empty.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("struct.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("type.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("timestamp.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("wrappers.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("source_context.proto") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("native-image.properties") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("library.properties") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("public-suffix-list.txt") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("jna") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("findbugsExclude.xml") => MergeStrategy.first
  case PathList(ps@_*) if ps.contains("okio.kotlin_module") => MergeStrategy.first
  case path if path.contains("pekko/stream") => MergeStrategy.first
  case path if path.contains("org/bouncycastle") => MergeStrategy.first
  case PathList("javax", xs@_*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

lazy val packageAll = taskKey[Unit]("PackageAll")
packageAll := {
  (Compile / dist).value
  (Compile / assembly).value
}

import play.sbt.PlayImport.PlayKeys._

publish / packagedArtifacts := {
  val artifacts: Map[sbt.Artifact, java.io.File] = (publishLocal / packagedArtifacts).value
  val assets: java.io.File                       = (Compile / playPackageAssets).value
  artifacts + (Artifact(moduleName.value, "jar", "jar", "assets") -> assets)
}

resolvers += Resolver.mavenLocal

bashScriptExtraDefines += """
addJava "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED"
addJava "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
addJava "--add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED"
addJava "--add-exports=java.base/sun.security.x509=ALL-UNNAMED" 
addJava "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED" 
addJava "-Dlog4j2.formatMsgNoLookups=true"
"""

Revolver.enableDebugging(port = Integer.parseInt(sys.props.getOrElse("otoroshi.sbt.port", "5005")), suspend = false)

// run with: ~reStart
reStart / mainClass := Some("play.core.server.ProdServerStart")
reStart / javaOptions ++= Seq(
  "-Xms2g",
  "-Xmx8g",
  "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
  "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
  "-Dlog4j2.formatMsgNoLookups=true",
  //"-Dapp.rootScheme=https",
  "-Dotoroshi.revolver=true",
  "-Dotoroshi.env=dev",
  "-Dotoroshi.http.port=9999",
  "-Dotoroshi.https.port=9998",
  "-Dotoroshi.liveJs=true",
  "-Dotoroshi.adminPassword=password",
  "-Dotoroshi.domain=oto.tools",
  "-Dotoroshi.events.maxSize=0",
  "-Dotoroshi.cluster.mode=Leader",
  "-Dotoroshi.cluster.leader.name=otoroshi-leader-dev",
  "-Dotoroshi.tunnels.enabled=false",
  "-Dotoroshi.tunnels.default.enabled=false",
  "-Dotoroshi.tunnels.default.url=http://127.0.0.1:9999",
  "-Dotoroshi.instance.name=dev",
  "-Dotoroshi.vaults.enabled=true",
  "-Dotoroshi.privateapps.session.enabled=true",
  //"-Dotoroshi.loggers.otoroshi-papps-session-manager=DEBUG",
  //"-Dotoroshi.privateapps.subdomain=otoroshi",
  "-Dotoroshi.ssl.fromOutside.clientAuth=None",
  //"-Dotoroshi.ssl.fromOutside.clientAuth=Need",
  "-Dotoroshi.inmemory.modern=true",
  "-Dotoroshi.wasm.cache.ttl=2000",
  "-Dotoroshi.next.experimental.netty-server.enabled=true",
  "-Dotoroshi.next.experimental.netty-server.accesslog=true",
  "-Dotoroshi.next.experimental.netty-server.wiretap=false",
  "-Dotoroshi.next.experimental.netty-server.http3.enabled=true",
  //"-Dotoroshi.loggers.otoroshi-wasm-debug=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-wasm-vm-pool=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-wasm-integration=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-proxy-wasm=TRACE",
  //"-Dotoroshi.loggers.otoroshi-experimental-netty-http3-client=DEBUG",
  //"-Dotoroshi.loggers.otoroshi-experimental-netty-http3-server=DEBUG",
  "-Dotoroshi.options.enable-json-media-type-with-open-charset=true",
  "-Dotoroshi.next.state-sync-interval=1000",
  // "-Dotoroshi.next.experimental.netty-server.native.driver=IOUring",
  // "-Dotoroshi.storage=ext:foo",
  "-Dotoroshi.storage=file",
  //"-Dotoroshi.storage=inmemory",
  "-DVAULT_VALUE=admin-api-apikey-secret"
  // "-Dotoroshi.storage=redis",
//   "-Dotoroshi.redis.lettuce.uri=redis://localhost:6379/",
)
