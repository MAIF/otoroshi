package otoroshi.utils.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import play.api.libs.json.{JsValue, Json}

import scala.util.Try

object Yaml {

  private val yamlReader               = new ObjectMapper(new YAMLFactory())
  private val yamlMinimizeQuotesReader = new ObjectMapper(
    new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
  )
  private val jsonWriter               = new ObjectMapper()

  def parse(content: String): Option[JsValue] = {
    Try {
      val obj = yamlReader.readValue(content, classOf[Object])
      Some(Json.parse(jsonWriter.writeValueAsString(obj)))
    } recover { case _ =>
      None
    } get
  }

  def write(value: JsValue): String = {
    val read = jsonWriter.readValue(Json.stringify(value), classOf[Object])
    yamlReader.writeValueAsString(read)
  }
}
