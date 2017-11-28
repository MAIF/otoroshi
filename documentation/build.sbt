name := """otoroshi-docs"""
organization := "fr.maif.otoroshi"
version := "1.0.0"
scalaVersion := "2.11.8"

lazy val docs = (project in file("."))
  .enablePlugins(ParadoxPlugin)
  .settings(
    name := "Otoroshi",
    paradoxTheme := Some(builtinParadoxTheme("generic"))
  )