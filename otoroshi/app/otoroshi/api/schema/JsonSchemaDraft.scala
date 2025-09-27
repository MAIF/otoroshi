package otoroshi.api.schema

// JSON Schema versions
sealed trait JsonSchemaDraft {
  def uri: String
}

object JsonSchemaDraft {
  case object Draft04 extends JsonSchemaDraft {
    val uri = "http://json-schema.org/draft-04/schema#"
  }

  case object Draft06 extends JsonSchemaDraft {
    val uri = "http://json-schema.org/draft-06/schema#"
  }

  case object Draft07 extends JsonSchemaDraft {
    val uri = "http://json-schema.org/draft-07/schema#"
  }

  case object Draft2019_09 extends JsonSchemaDraft {
    val uri = "https://json-schema.org/draft/2019-09/schema"
  }

  case object Draft2020_12 extends JsonSchemaDraft {
    val uri = "https://json-schema.org/draft/2020-12/schema"
  }

  case object OpenApi30 extends JsonSchemaDraft {
    val uri = "https://spec.openapis.org/oas/3.0/schema/2021-09-28"
  }

  case object OpenApi31 extends JsonSchemaDraft {
    val uri = "https://spec.openapis.org/oas/3.1/schema/2022-10-07"
  }
}
