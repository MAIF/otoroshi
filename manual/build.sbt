name := """otoroshi-manual"""
organization := "fr.maif"
version := "1.2.0-dev"
scalaVersion := "2.12.4"

lazy val docs = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Otoroshi",
    paradoxTheme := Some(builtinParadoxTheme("generic"))
  )