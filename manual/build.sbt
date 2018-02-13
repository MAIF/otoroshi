name := """otoroshi-manual"""
organization := "fr.maif"
version := "1.0.2"
scalaVersion := "2.11.8"

lazy val docs = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Otoroshi",
    paradoxTheme := Some(builtinParadoxTheme("generic"))
  )