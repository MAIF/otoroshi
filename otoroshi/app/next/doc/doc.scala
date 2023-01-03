package otoroshi.next.doc

import com.google.common.base.Charsets
import otoroshi.events.CustomDataExporter
import otoroshi.next.plugins.api.{NgNamedPlugin, NgPluginVisibility}
import play.api.Logger
import play.api.libs.json.Json

import java.io.File
import java.nio.file.Files
import scala.util.Try
import otoroshi.utils.syntax.implicits._

import scala.collection.JavaConverters._
import java.nio.charset.Charset

class NgPluginDocumentationGenerator(docPath: String) {

  val logger = Logger("NgPluginDocumentationGenerator")

  lazy val allPluginNames =
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
      if (logger.isDebugEnabled) logger.debug(s"classpath scanning in ${System.currentTimeMillis() - start} ms.")

      def predicate(c: ClassInfo): Boolean = {
        c.isInterface || c.getName.contains(".NgMerged")
      }

      try {
        val plugins: Seq[String] = (scanResult.getSubclasses(classOf[NgNamedPlugin].getName).asScala ++
          scanResult.getClassesImplementing(classOf[NgNamedPlugin].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        plugins
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          Seq.empty[String]
      } finally if (scanResult != null) scanResult.close()
    } getOrElse Seq.empty[String]

  def ensureRootDir(): File = {
    val root = new File(docPath + "/src/main/paradox/plugins")
    if (!root.exists()) {
      root.mkdirs()
    }
    root
  }

  def makePluginContent(plugin: NgNamedPlugin): String = {

    val desc = {
      val dc = plugin.description.getOrElse("").trim
      if (dc.contains("```") && !dc.contains("//")) {
        dc
          .split("```")(0)
          .replace("This plugin can accept the following configuration", "")
          .replace("The plugin accepts the following configuration", "")
          .trim
      } else {
        dc
      }
    }
    val description = plugin.description
      .map { dc =>
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

    val pluginClazz = "pl" //  plugin.steps.map(s => s"plugin-kind-${s.name.toLowerCase()}").mkString(" ")
    val pluginLogo  = ""
    s"""
       |@@@ div { .ng-plugin .plugin-hidden .${pluginClazz} #${plugin.getClass.getName} }
       |
       |# ${plugin.name}
       |
       |## Defined on steps
       |
       |${plugin.steps.map(s => s"  - `${s.name}`").mkString("\n")}
       |
       |## Plugin reference
       |
       |`cp:${plugin.internalName}`
       |
       |$description
       |
       |$defaultConfig
       |
       |$documentation
       |
       |@@@
       |""".stripMargin
  }

  def makePluginPage(plugin: NgNamedPlugin, root: File): (String, String) = {
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

  def runOnePage(): Unit = {
    val root                  = ensureRootDir()
    val plugins               = allPluginNames.distinct
    val contents: Seq[String] = plugins
      .map { pl =>
        this.getClass.getClassLoader.loadClass(pl).newInstance()
      }
      .map(_.asInstanceOf[NgNamedPlugin])
      .filterNot(_.deprecated)
      .filterNot(_.visibility == NgPluginVisibility.NgInternal)
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
      Seq(s"""# Built-in plugins
             |
             |Otoroshi next provides some plugins out of the box. Here is the available plugins with their documentation and reference configuration
             |
             |${contents.mkString("\n")}
             |
             |
             |""".stripMargin).asJava,
      Charsets.UTF_8
    )
  }
}

class NgPluginDocumentationGeneratorApp extends App {

  val generator = new NgPluginDocumentationGenerator("../manual")
  generator.runOnePage()
}
