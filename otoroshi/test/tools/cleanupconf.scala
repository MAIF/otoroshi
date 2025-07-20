package tools

import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters._

object ConfigurationCleanup {
  def cleanup(path: String, newpath: String): Unit = {
    val file        = new File(path)
    val newfile     = new File(newpath)
    val content     = Files.readAllLines(file.toPath).asScala
    val fillContent = content.mkString("\n")
    val newContent  = content
        .flatMap { line =>
          if (line.contains("${?APP_") && !line.contains("${?OTOROSHI_")) {
            val replacedLine = line.replace("${?APP_", "${?OTOROSHI_")
            if (fillContent.contains(replacedLine)) {
              Seq(line)
            } else {
              Seq(
                line,
                replacedLine
              )
            }

          } else if (line.contains("${?PLAY_") && !line.contains("${?OTOROSHI_")) {
            val replacedLine = line.replace("${?PLAY_", "${?OTOROSHI_")
            if (fillContent.contains(replacedLine)) {
              Seq(line)
            } else {
              Seq(
                line,
                replacedLine
              )
            }

          } else if (line.contains("${?") && !line.contains("${?OTOROSHI_")) {
            val replacedLine = line.replace("${?APP_", "${?OTOROSHI_").replace("${?", "${?OTOROSHI_")
            if (fillContent.contains(replacedLine)) {
              Seq(line)
            } else {
              Seq(
                line,
                replacedLine
              )
            }

          } else {
            Seq(line)
          }
        }
        .mkString("\n")
    Files.writeString(newfile.toPath, newContent)
  }
}

class ConfigurationCleanupSpec
    extends AnyWordSpec
        with Matchers
        with OptionValues
        with ScalaFutures
        with IntegrationPatience {

  private def findFile(relativePath: String): Option[File] = {
    // Try various possible locations
    val possiblePaths = Seq(
      relativePath,                    // ./conf/old-application.conf
      s"../$relativePath",            // ../conf/old-application.conf
      s"otoroshi/$relativePath",      // otoroshi/conf/old-application.conf
      s"../otoroshi/$relativePath",   // ../otoroshi/conf/old-application.conf
      s"../../$relativePath",         // ../../conf/old-application.conf
      s"../../otoroshi/$relativePath" // ../../otoroshi/conf/old-application.conf
    )

    possiblePaths.map(new File(_)).find(_.exists())
  }

  "ConfigurationCleanup" should {
    "cleanup configuration env. variables" in {
      // Print current working directory for debugging
      println(s"Current working directory: ${new File(".").getCanonicalPath}")

      val configs = Seq(
        ("conf/old-application.conf", "conf/application.conf"),
        ("conf/old-base.conf", "conf/base.conf")
      )

      configs.foreach { case (oldPath, newPath) =>
        findFile(oldPath) match {
          case Some(oldFile) =>
            // Calculate the new file path based on where we found the old file
            val oldFilePath = oldFile.getPath
            val prefix = oldFilePath.substring(0, oldFilePath.length - oldPath.length)
            val newFile = new File(prefix + newPath)

            println(s"Found $oldPath at: ${oldFile.getCanonicalPath}")
            println(s"Will write to: ${newFile.getCanonicalPath}")

            ConfigurationCleanup.cleanup(oldFile.getPath, newFile.getPath)

          case None =>
            // List files in current and parent directories to help debug
            println(s"Could not find $oldPath")
            println("Files in current directory:")
            new File(".").listFiles().foreach(f => println(s"  ${f.getName}"))
            println("Files in parent directory:")
            new File("..").listFiles().foreach(f => println(s"  ${f.getName}"))

            // Skip the test if files don't exist
            println(s"Skipping cleanup for $oldPath - file not found")
        }
      }
    }
  }
}