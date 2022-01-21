package tools

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.stream.Materializer
import akka.util.ByteString
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.plugins.log4j.Log4jExpressionParser
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.{JsArray, JsObject, Json}
import java.nio.file.Files
import java.io.File
import collection.JavaConverters._


object ConfigurationCleanup {
  def cleanup(path: String, newpath: String): Unit = {
    val file = new File(path)
    val newfile = new File(newpath)
    val content = Files.readAllLines(file.toPath).asScala
    val fillContent = content.mkString("\n")
    val newContent = content.flatMap { line =>
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
    }.mkString("\n")
    Files.writeString(newfile.toPath(), newContent)
  }
}

class ConfigurationCleanupSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {
  "ConfigurationCleanup" should {
    "cleanup configuration env. variables" in {
      ConfigurationCleanup.cleanup("./conf/old-application.conf", "./conf/application.conf")
      ConfigurationCleanup.cleanup("./conf/old-base.conf", "./conf/base.conf")
      // diff -u ./otoroshi/conf/old-application.conf ./otoroshi/conf/application.conf > ./otoroshi/conf/application.conf.diff
      // diff -u ./otoroshi/conf/old-base.conf ./otoroshi/conf/base.conf > ./otoroshi/conf/base.conf.diff
    }
  }
}
