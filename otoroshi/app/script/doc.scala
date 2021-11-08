package otoroshi.script

import com.google.common.base.Charsets
import otoroshi.events.CustomDataExporter
import play.api.Logger
import play.api.libs.json.Json

import java.io.File
import java.nio.file.Files
import scala.util.Try
import otoroshi.utils.syntax.implicits._

import scala.collection.JavaConverters._
import java.nio.charset.Charset

class PluginDocumentationGenerator(docPath: String) {

  val logger = Logger("PluginDocumentationGenerator")

  lazy val (transformersNames, validatorsNames, preRouteNames, reqSinkNames, listenerNames, jobNames, exporterNames) =
    Try {
      import io.github.classgraph.{ClassGraph, ClassInfo, ScanResult}

      import collection.JavaConverters._
      val start                  = System.currentTimeMillis()
      val allPackages            = Seq("otoroshi", "otoroshi_plugins")
      val scanResult: ScanResult = new ClassGraph()
        .addClassLoader(this.getClass.getClassLoader)
        .enableClassInfo()
        .acceptPackages(allPackages: _*)
        .scan

      logger.debug(s"classpath scanning in ${System.currentTimeMillis() - start} ms.")
      try {

        def predicate(c: ClassInfo): Boolean = {
          c.isInterface || (
            c.getName == "otoroshi.script.DefaultRequestTransformer$" ||
            c.getName == "otoroshi.script.CompilingRequestTransformer$" ||
            c.getName == "otoroshi.script.CompilingValidator$" ||
            c.getName == "otoroshi.script.CompilingPreRouting$" ||
            c.getName == "otoroshi.script.CompilingRequestSink$" ||
            c.getName == "otoroshi.script.CompilingOtoroshiEventListener$" ||
            c.getName == "otoroshi.script.DefaultValidator$" ||
            c.getName == "otoroshi.script.DefaultPreRouting$" ||
            c.getName == "otoroshi.script.DefaultRequestSink$" ||
            c.getName == "otoroshi.script.FailingPreRoute" ||
            c.getName == "otoroshi.script.FailingPreRoute$" ||
            c.getName == "otoroshi.script.DefaultOtoroshiEventListener$" ||
            c.getName == "otoroshi.script.DefaultJob$" ||
            c.getName == "otoroshi.script.CompilingJob$" ||
            c.getName == "otoroshi.script.NanoApp" ||
            c.getName == "otoroshi.script.NanoApp$"
          )
        }

        val requestTransformers: Seq[String] = (scanResult.getSubclasses(classOf[RequestTransformer].getName).asScala ++
          scanResult.getClassesImplementing(classOf[RequestTransformer].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val validators: Seq[String] = (scanResult.getSubclasses(classOf[AccessValidator].getName).asScala ++
          scanResult.getClassesImplementing(classOf[AccessValidator].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val preRoutes: Seq[String] = (scanResult.getSubclasses(classOf[PreRouting].getName).asScala ++
          scanResult.getClassesImplementing(classOf[PreRouting].getName).asScala).filterNot(predicate).map(_.getName)

        val reqSinks: Seq[String] = (scanResult.getSubclasses(classOf[RequestSink].getName).asScala ++
          scanResult.getClassesImplementing(classOf[RequestSink].getName).asScala).filterNot(predicate).map(_.getName)

        val listenerNames: Seq[String] = (scanResult.getSubclasses(classOf[OtoroshiEventListener].getName).asScala ++
          scanResult.getClassesImplementing(classOf[OtoroshiEventListener].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val jobNames: Seq[String] = (scanResult.getSubclasses(classOf[Job].getName).asScala ++
          scanResult.getClassesImplementing(classOf[Job].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val customExporters: Seq[String] = (scanResult.getSubclasses(classOf[CustomDataExporter].getName).asScala ++
          scanResult.getClassesImplementing(classOf[CustomDataExporter].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        (requestTransformers, validators, preRoutes, reqSinks, listenerNames, jobNames, customExporters)
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          (
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String]
          )
      } finally if (scanResult != null) scanResult.close()
    } getOrElse (Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq
      .empty[String], Seq.empty[String])

  def ensureRootDir(): File = {
    val root = new File(docPath + "/src/main/paradox/plugins")
    if (!root.exists()) {
      root.mkdirs()
    }
    root
  }

  def makePluginContent(plugin: NamedPlugin): String = {
    val description = plugin.description
      .map { dc =>
        var desc = dc.trim
        if (desc.contains("```") && !desc.contains("//")) {
          desc = desc
            .split("```")(0)
            .replace("This plugin can accept the following configuration", "")
            .replace("The plugin accepts the following configuration", "")
            .trim
        }
        s"""## Description
         |
         |${desc}
         |
         |""".stripMargin
      }
      .getOrElse("")

    val defaultConfig = plugin.defaultConfig
      .map { dc =>
        s"""## Default configuration
         |
         |```json
         |${dc.prettify}
         |```
         |
         |""".stripMargin
      }
      .getOrElse("")

    val documentation = plugin.documentation
      .map { dc =>
        s"""## Documentation
         |
         |${dc}
         |
         |""".stripMargin
      }
      .getOrElse("")

    s"""
         |# ${plugin.name}
         |
         |## Infos
         |
         |* plugin type: `${plugin.pluginType.name}`
         |* configuration root: `${plugin.configRoot.getOrElse("`none`")}`
         |
         |$description
         |
         |$defaultConfig
         |
         |$documentation
         |""".stripMargin
  }

  def makePluginPage(plugin: NamedPlugin, root: File): (String, String) = {
    // logger.info(plugin.name)
    val file = new File(root, plugin.getClass.getName.toLowerCase().replace(".", "-") + ".md")
    if (file.exists()) {
      file.delete()
    }
    file.createNewFile()

    //val description = plugin.description
    //  .map { dc =>
    //    var desc = dc.trim
    //    if (desc.contains("```") && !desc.contains("//")) {
    //      desc = desc
    //        .split("```")(0)
    //        .replace("This plugin can accept the following configuration", "")
    //        .replace("The plugin accepts the following configuration", "")
    //        .trim
    //    }
    //    s"""## Description
    //     |
    //     |${desc}
    //     |
    //     |""".stripMargin
    //  }
    //  .getOrElse("")

    //val defaultConfig = plugin.defaultConfig
    //  .map { dc =>
    //    s"""## Default configuration
    //     |
    //     |```json
    //     |${dc.prettify}
    //     |```
    //     |
    //     |""".stripMargin
    //  }
    //  .getOrElse("")

    //val documentation = plugin.documentation
    //  .map { dc =>
    //    s"""## Documentation
    //     |
    //     |${dc}
    //     |
    //     |""".stripMargin
    //  }
    //  .getOrElse("")
//
    Files.write(
      file.toPath,
      Seq(makePluginContent(plugin)).asJava,
      //Seq(s"""
      //   |# ${plugin.name}
      //   |
      //   |## Infos
      //   |
      //   |* plugin type: `${plugin.pluginType.name}`
      //   |* configuration root: `${plugin.configRoot.getOrElse("`none`")}`
      //   |
      //   |$description
      //   |
      //   |$defaultConfig
      //   |
      //   |$documentation
      //   |""".stripMargin).asJava,
      Charsets.UTF_8
    )

    (plugin.name, file.getName)
  }

  def run(): Unit = {
    val root                         = ensureRootDir()
    val plugins                      =
      (transformersNames ++ validatorsNames ++ preRouteNames ++ reqSinkNames ++ listenerNames ++ jobNames ++ exporterNames).distinct
    val names: Seq[(String, String)] = plugins
      .map { pl =>
        this.getClass.getClassLoader.loadClass(pl).newInstance()
      }
      .map(_.asInstanceOf[NamedPlugin])
      .filterNot(_.core)
      .filterNot(_.deprecated)
      .filterNot(p => p.isInstanceOf[Job] && p.asInstanceOf[Job].visibility == JobVisibility.Internal)
      .map { pl =>
        makePluginPage(pl, root)
      }
    val index                        = new File(root, "index.md")
    if (index.exists()) {
      index.delete()
    }
    index.createNewFile()
    Files.write(
      index.toPath,
      Seq(s"""# Otoroshi plugins
        |
        |Otoroshi provides some plugins out of the box
        |
        |${names.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(t => s"* @ref:[${t._1}](./${t._2})").mkString("\n")}
        |
        |@@@ index
        |
        |${names.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(t => s"* [${t._1}](./${t._2})").mkString("\n")}
        |
        |@@@
        |
        |""".stripMargin).asJava,
      Charsets.UTF_8
    )
  }

  def runOnePage(): Unit = {
    val root                  = ensureRootDir()
    val plugins               =
      (transformersNames ++ validatorsNames ++ preRouteNames ++ reqSinkNames ++ listenerNames ++ jobNames ++ exporterNames).distinct
    val contents: Seq[String] = plugins
      .map { pl =>
        this.getClass.getClassLoader.loadClass(pl).newInstance()
      }
      .map(_.asInstanceOf[NamedPlugin])
      .filterNot(_.core)
      .filterNot(_.deprecated)
      .filterNot(p => p.isInstanceOf[Job] && p.asInstanceOf[Job].visibility == JobVisibility.Internal)
      .map { pl =>
        makePluginContent(pl).replace("\n## ", "\n### ").replace("\n# ", "\n## ")
      }
    val index                 = new File(root, "built-in-plugins.md")
    if (index.exists()) {
      index.delete()
    }
    index.createNewFile()
    Files.write(
      index.toPath,
      Seq(s"""# Otoroshi built-in plugins
        |
        |Otoroshi provides some plugins out of the box. Here is the available plugins with their documentation and reference configuration
        |
        |${contents.mkString("\n")}
        |
        |
        |""".stripMargin).asJava,
      Charsets.UTF_8
    )
  }
}

class PluginDocumentationGeneratorApp extends App {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.run()
}
