package otoroshi.utils.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import play.api.libs.json.{JsValue, Json}

object Yaml {

  private val yamlReader = new ObjectMapper(new YAMLFactory())
  private val jsonWriter = new ObjectMapper()

  def parse(content: String): JsValue = {
    val obj = yamlReader.readValue(content, classOf[Object])
    Json.parse(jsonWriter.writeValueAsString(obj))
  }
}
