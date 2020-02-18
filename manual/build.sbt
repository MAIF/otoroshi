name := """otoroshi-manual"""
organization := "fr.maif"
version := "1.4.21-dev"
scalaVersion := "2.12.10"

lazy val docs = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Otoroshi",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxRoots := List("index.html")
  )