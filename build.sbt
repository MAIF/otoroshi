ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "fr.maif"
ThisBuild / version := "17.5.0-dev"

lazy val root = (project in file("."))
    .settings(
        name := "otoroshi-root",
        publish / skip := true
    )
    .aggregate(otoroshi)

lazy val otoroshi = project in file("otoroshi")