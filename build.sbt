ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "fr.maif"
ThisBuild / version := "17.9.0-dev"

lazy val root = (project in file("."))
    .settings(
        name := "otoroshi-root",
        publish / skip := true
    )
    .aggregate(otoroshi)

lazy val otoroshi = project in file("otoroshi")

scalacOptions ++= Seq(
    //  "-Xsource:3",
    //  "-Wconf:cat=scala3-migration:s",
    //  "-Xmigration",
    //  "-deprecation",
    "-rewrite",
    "-source 3.4-migration",
    "-experimental",
    "-explain",
    "-feature",
    "-explain-cyclic",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:existentials",
    "-language:postfixOps",
)