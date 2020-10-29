name := """otoroshi-manual"""
organization := "fr.maif"
version := "1.5.0-alpha02-dev"
scalaVersion := "2.13.1"

lazy val docs = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Otoroshi",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxRoots := List("index.html")
  )