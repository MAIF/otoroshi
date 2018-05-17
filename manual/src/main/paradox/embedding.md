# Embedding Otoroshi

Otoroshi provides an API to start Otoroshi instances programmatically from any JVM app.

## Getting the dependencies

You can get the Otoroshi dependency from bintray

Sbt
:   @@snip [build.sbt](./snippets/build.sbt)

Gradle
:   @@snip [build.gradle](./snippets/build.gradle)

## Starting Otoroshi

Now just instanciate an Otoroshi proxy with the configuration you like and you will be able to control it using the internal APIs of Otoroshi.

Scala
:   @@snip [embed.scala](./snippets/embed.scala)

Java
:   @@snip [embed.java](./snippets/embed.java)
