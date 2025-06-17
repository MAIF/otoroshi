package otoroshi.next.workflow

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

object WorkflowOperatorsInitializer {
  def initDefaults(): Unit = {
    WorkflowOperator.registerOperator("$mem_ref", new MemRefOperator())
    WorkflowOperator.registerOperator("$array_append", new ArrayAppendOperator())
    WorkflowOperator.registerOperator("$array_prepend", new ArrayPrependOperator())
    WorkflowOperator.registerOperator("$array_at", new ArrayAtOperator())
    WorkflowOperator.registerOperator("$array_del", new ArrayDelOperator())
    WorkflowOperator.registerOperator("$array_page", new ArrayPageOperator())
    WorkflowOperator.registerOperator("$projection", new ProjectionOperator())
    WorkflowOperator.registerOperator("$map_put", new MapPutOperator())
    WorkflowOperator.registerOperator("$map_get", new MapGetOperator())
    WorkflowOperator.registerOperator("$map_del", new MapDelOperator())
    WorkflowOperator.registerOperator("$json_parse", new JsonParseOperator())
    WorkflowOperator.registerOperator("$str_concat", new StrConcatOperator())
    WorkflowOperator.registerOperator("$is_truthy", new IsTruthyOperator())
    WorkflowOperator.registerOperator("$is_falsy", new IsFalsyOperator())
    WorkflowOperator.registerOperator("$contains", new ContainsOperator())
    WorkflowOperator.registerOperator("$eq", new EqOperator())
    WorkflowOperator.registerOperator("$neq", new NeqOperator())
    WorkflowOperator.registerOperator("$gt", new GtOperator())
    WorkflowOperator.registerOperator("$lt", new LtOperator())
    WorkflowOperator.registerOperator("$gte", new GteOperator())
    WorkflowOperator.registerOperator("$lte", new LteOperator())
    WorkflowOperator.registerOperator("$encode_base64", new EncodeBase64Operator())
    WorkflowOperator.registerOperator("$decode_base64", new DecodeBase64Operator())
    WorkflowOperator.registerOperator("$basic_auth", new BasicAuthOperator())
    WorkflowOperator.registerOperator("$now", new NowOperator())
    WorkflowOperator.registerOperator("$not", new NotOperator())
    WorkflowOperator.registerOperator("$parse_datetime", new ParseDateTimeOperator())
    WorkflowOperator.registerOperator("$parse_date", new ParseDateOperator())
    WorkflowOperator.registerOperator("$parse_time", new ParseTimeOperator())
    WorkflowOperator.registerOperator("$add", new AddOperator())
    WorkflowOperator.registerOperator("$subtract", new SubtractOperator())
    WorkflowOperator.registerOperator("$multiply", new MultiplyOperator())
    WorkflowOperator.registerOperator("$divide", new DivideOperator())
    WorkflowOperator.registerOperator("$incr", new IncrementOperator())
    WorkflowOperator.registerOperator("$decr", new DecrementOperator())
    WorkflowOperator.registerOperator("$str_upper_case", new UppercaseOperator())
    WorkflowOperator.registerOperator("$str_lower_case", new LowercaseOperator())
    WorkflowOperator.registerOperator("$str_split", new StringSplitOperator())
    WorkflowOperator.registerOperator("$expression_language", new ExpressionLanguageOperator())
  }
}

class UppercaseOperator extends WorkflowOperator {
  override def documentationName: String = "$str_upper_case"
  override def documentationDescription: String = "This operator converts a string to uppercase"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The string to convert to uppercase"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$str_upper_case" -> Json.obj(
      "value" -> "hello"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").as[String].toUpperCase().json
  }
}

class LowercaseOperator extends WorkflowOperator {
  override def documentationName: String = "$str_lower_case"
  override def documentationDescription: String = "This operator converts a string to lowercase"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The string to convert to lowercase"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$str_lower_case" -> Json.obj(
      "value" -> "hello"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").as[String].toLowerCase().json
  }
}

class StringSplitOperator extends WorkflowOperator {
  override def documentationName: String = "$str_split"
  override def documentationDescription: String = "This operator splits a string into an array based on a regex"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "regex"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The string to split"),
      "regex" -> Json.obj("type" -> "string", "description" -> "The regex to use for splitting"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$str_split" -> Json.obj(
      "value" -> "hello,world",
      "regex" -> ","
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    JsArray(opts.select("value").as[String].split(opts.select("regex").as[String]).toSeq.map(_.json))
  }
}

class IncrementOperator extends WorkflowOperator {
  override def documentationName: String = "$incr"
  override def documentationDescription: String = "This operator increments a value by a given amount"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "increment"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "number", "description" -> "The value to increment"),
      "increment" -> Json.obj("type" -> "number", "description" -> "The amount to increment by"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$incr" -> Json.obj(
      "value" -> 10,
      "increment" -> 5
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    JsNumber(opts.select("value").as[BigDecimal].+(opts.select("increment").as[BigDecimal]))
  }
}

class DecrementOperator extends WorkflowOperator {
  override def documentationName: String = "$decr"
  override def documentationDescription: String = "This operator decrements a value by a given amount"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "decrement"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "number", "description" -> "The value to decrement"),
      "decrement" -> Json.obj("type" -> "number", "description" -> "The amount to decrement by"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$decr" -> Json.obj(
      "value" -> 10,
      "decrement" -> 5
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    JsNumber(opts.select("value").as[BigDecimal].-(opts.select("decrement").as[BigDecimal]))
  }
}

class ExpressionLanguageOperator extends WorkflowOperator {
  override def documentationName: String = "$expression_language"
  override def documentationDescription: String = "This operator evaluates an expression language"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("expression"),
    "properties" -> Json.obj(
      "expression" -> Json.obj("type" -> "string", "description" -> "The expression to evaluate"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$expression_language" -> Json.obj(
      "expression" -> "${req.headers.X-Custom-Header}"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("expression").asOpt[String] match {
      case Some(expression) =>
        GlobalExpressionLanguage
          .apply(
            value = expression,
            req = wfr.attrs.get(otoroshi.plugins.Keys.RequestKey),
            service = wfr.attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy),
            route = wfr.attrs.get(otoroshi.next.plugins.Keys.RouteKey),
            apiKey = wfr.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
            user = wfr.attrs.get(otoroshi.plugins.Keys.UserKey),
            context = wfr.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            attrs = wfr.attrs,
            env = env
          )
          .json
      case _                => JsNull
    }
  }
}

class AddOperator extends WorkflowOperator {
  override def documentationName: String = "$add"
  override def documentationDescription: String = "This operator adds a list of numbers"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("values"),
    "properties" -> Json.obj(
      "values" -> Json.obj("type" -> "array", "description" -> "The list of numbers to add"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$add" -> Json.obj(
      "values" -> Seq(1, 2, 3)
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("values").asOpt[Seq[JsNumber]] match {
      case Some(numbers) => JsNumber(numbers.foldLeft(BigDecimal(0))((a, b) => a + b.value))
      case _             => 0.json
    }
  }
}

class SubtractOperator extends WorkflowOperator {
  override def documentationName: String = "$subtract"
  override def documentationDescription: String = "This operator subtracts a list of numbers"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("values"),
    "properties" -> Json.obj(
      "values" -> Json.obj("type" -> "array", "description" -> "The list of numbers to subtract"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$subtract" -> Json.obj(
      "values" -> Seq(1, 2, 3)
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("values").asOpt[Seq[JsNumber]] match {
      case Some(numbers) => JsNumber(numbers.foldLeft(BigDecimal(0))((a, b) => a - b.value))
      case _             => 0.json
    }
  }
}

class MultiplyOperator extends WorkflowOperator {
  override def documentationName: String = "$multiply"
  override def documentationDescription: String = "This operator multiplies a list of numbers"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("values"),
    "properties" -> Json.obj(
      "values" -> Json.obj("type" -> "array", "description" -> "The list of numbers to multiply"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$multiply" -> Json.obj(
      "values" -> Seq(1, 2, 3)
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("values").asOpt[Seq[JsNumber]] match {
      case Some(numbers) => JsNumber(numbers.foldLeft(BigDecimal(0))((a, b) => a * b.value))
      case _             => 0.json
    }
  }
}

class DivideOperator extends WorkflowOperator {
  override def documentationName: String = "$divide"
  override def documentationDescription: String = "This operator divides a list of numbers"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("values"),
    "properties" -> Json.obj(
      "values" -> Json.obj("type" -> "array", "description" -> "The list of numbers to divide"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$divide" -> Json.obj(
      "values" -> Seq(1, 2, 3)
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("values").asOpt[Seq[JsNumber]] match {
      case Some(numbers) => JsNumber(numbers.foldLeft(BigDecimal(0))((a, b) => a / b.value))
      case _             => 0.json
    }
  }
}

class ParseDateTimeOperator extends WorkflowOperator {
  override def documentationName: String = "$parse_datetime"
  override def documentationDescription: String = "This operator parses a datetime string into a timestamp"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "pattern"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The datetime string to parse"),
      "pattern" -> Json.obj("type" -> "string", "description" -> "The pattern to use for parsing"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$parse_datetime" -> Json.obj(
      "value" -> "2023-01-01T00:00:00",
      "pattern" -> "yyyy-MM-dd'T'HH:mm:ss"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val pattern = opts
      .select("pattern")
      .asOpt[String]
      .map(p => DateTimeFormat.forPattern(p))
      .getOrElse(ISODateTimeFormat.dateTimeParser.withOffsetParsed)
    opts.select("value").asOpt[String] match {
      case Some(dateStr) => DateTime.parse(dateStr, pattern).toDate.getTime.json
      case _             => JsBoolean(false)
    }
  }
}

class ParseDateOperator extends WorkflowOperator {
  override def documentationName: String = "$parse_date"
  override def documentationDescription: String = "This operator parses a date string into a timestamp"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "pattern"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The date string to parse"),
      "pattern" -> Json.obj("type" -> "string", "description" -> "The pattern to use for parsing"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$parse_date" -> Json.obj(
      "value" -> "2023-01-01",
      "pattern" -> "yyyy-MM-dd"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val pattern = opts
      .select("pattern")
      .asOpt[String]
      .map(p => DateTimeFormat.forPattern(p))
      .getOrElse(DateTimeFormat.forPattern("yyyy-MM-dd"))
    opts.select("value").asOpt[String] match {
      case Some(dateStr) => DateTime.parse(dateStr, pattern).withTimeAtStartOfDay().toDate.getTime.json
      case _             => JsNull
    }
  }
}

class ParseTimeOperator extends WorkflowOperator {
  override def documentationName: String = "$parse_time"
  override def documentationDescription: String = "This operator parses a time string into a timestamp"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "pattern"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The time string to parse"),
      "pattern" -> Json.obj("type" -> "string", "description" -> "The pattern to use for parsing"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$parse_time" -> Json.obj(
      "value" -> "00:00:00",
      "pattern" -> "HH:mm:ss"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val pattern = opts
      .select("pattern")
      .asOpt[String]
      .map(p => DateTimeFormat.forPattern(s"yyyy-MM-dd ${p}"))
      .getOrElse(DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss"))
    opts.select("value").asOpt[String] match {
      case Some(timeStr) =>
        DateTime
          .parse(s"${DateTime.now().toString("yyyy-MM-dd")} ${timeStr}", pattern)
          .withTimeAtStartOfDay()
          .toDate
          .getTime
          .json
      case _             => JsNull
    }
  }
}

class NotOperator extends WorkflowOperator {
  override def documentationName: String = "$not"
  override def documentationDescription: String = "This operator negates a boolean value"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "boolean", "description" -> "The boolean value to negate"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$not" -> Json.obj(
      "value" -> true
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").asOpt[JsValue] match {
      case Some(JsBoolean(b)) => JsBoolean(!b)
      case _                  => JsNull
    }
  }
}

class NowOperator extends WorkflowOperator {
  override def documentationName: String = "$now"
  override def documentationDescription: String = "This operator returns the current timestamp"
  override def documentationInputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$now" -> Json.obj()
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    System.currentTimeMillis().json
  }
}

class BasicAuthOperator extends WorkflowOperator {
  override def documentationName: String = "$basic_auth"
  override def documentationDescription: String = "This operator returns a basic authentication header"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("user", "password"),
    "properties" -> Json.obj(
      "user" -> Json.obj("type" -> "string", "description" -> "The username"),
      "password" -> Json.obj("type" -> "string", "description" -> "The password"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$basic_auth" -> Json.obj(
      "user" -> "username",
      "password" -> "password"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val user     = opts.select("user").asString
    val password = opts.select("password").asString
    s"Basic ${s"${user}:${password}".base64}".json
  }
}

class EncodeBase64Operator extends WorkflowOperator {
  override def documentationName: String = "$encode_base64"
  override def documentationDescription: String = "This operator encodes a string in base64"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The string to encode in base64"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$encode_base64" -> Json.obj(
      "value" -> "Hello, World!"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").asString.base64.json
  }
}

class DecodeBase64Operator extends WorkflowOperator {
  override def documentationName: String = "$decode_base64"
  override def documentationDescription: String = "This operator decodes a base64 string"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The base64 string to decode"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$decode_base64" -> Json.obj(
      "value" -> "SGVsbG8sIFdvcmxkIQ=="
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").asString.decodeBase64.json
  }
}

class GtOperator extends WorkflowOperator { 
  override def documentationName: String = "$gt"
  override def documentationDescription: String = "This operator checks if a number is greater than another number"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("a", "b"),
    "properties" -> Json.obj(
      "a" -> Json.obj("type" -> "number", "description" -> "The first number"),
      "b" -> Json.obj("type" -> "number", "description" -> "The second number"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$gt" -> Json.obj(
      "a" -> 1,
      "b" -> 0
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value > b.value).json
  }
}

class GteOperator extends WorkflowOperator { 
  override def documentationName: String = "$gte"
  override def documentationDescription: String = "This operator checks if a number is greater than or equal to another number"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("a", "b"),
    "properties" -> Json.obj(
      "a" -> Json.obj("type" -> "number", "description" -> "The first number"),
      "b" -> Json.obj("type" -> "number", "description" -> "The second number"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$gte" -> Json.obj(
      "a" -> 1,
      "b" -> 0
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value >= b.value).json
  }
}

class LtOperator extends WorkflowOperator { 
  override def documentationName: String = "$lt"
  override def documentationDescription: String = "This operator checks if a number is less than another number"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("a", "b"),
    "properties" -> Json.obj(
      "a" -> Json.obj("type" -> "number", "description" -> "The first number"),
      "b" -> Json.obj("type" -> "number", "description" -> "The second number"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$lt" -> Json.obj(
      "a" -> 1,
      "b" -> 0
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value < b.value).json
  }
}

class LteOperator extends WorkflowOperator { 
  override def documentationName: String = "$lte"
  override def documentationDescription: String = "This operator checks if a number is less than or equal to another number"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("a", "b"),
    "properties" -> Json.obj(
      "a" -> Json.obj("type" -> "number", "description" -> "The first number"),
      "b" -> Json.obj("type" -> "number", "description" -> "The second number"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$lte" -> Json.obj(
      "a" -> 1,
      "b" -> 0
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value <= b.value).json
  }
}

class EqOperator extends WorkflowOperator { 
  override def documentationName: String = "$eq"
  override def documentationDescription: String = "This operator checks if two values are equal"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("a", "b"),
    "properties" -> Json.obj(
      "a" -> Json.obj("type" -> "any", "description" -> "The first value"),
      "b" -> Json.obj("type" -> "any", "description" -> "The second value"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$eq" -> Json.obj(
      "a" -> 1,
      "b" -> 1
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").asValue
    val b = opts.select("b").asValue
    (a == b).json
  }
}

class NeqOperator extends WorkflowOperator { 
  override def documentationName: String = "$neq"
  override def documentationDescription: String = "This operator checks if two values are not equal"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("a", "b"),
    "properties" -> Json.obj(
      "a" -> Json.obj("type" -> "any", "description" -> "The first value"),
      "b" -> Json.obj("type" -> "any", "description" -> "The second value"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$neq" -> Json.obj(
      "a" -> 1,
      "b" -> 2
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").asValue
    val b = opts.select("b").asValue
    (a != b).json
  }
}

class ContainsOperator extends WorkflowOperator { 
  override def documentationName: String = "$contains"
  override def documentationDescription: String = "This operator checks if a value is contained in a container"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "container"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "any", "description" -> "The value to check"),
      "container" -> Json.obj("type" -> "any", "description" -> "The container to check"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$contains" -> Json.obj(
      "value" -> 1,
      "container" -> Seq(1, 2, 3)
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value              = opts.select("value").asValue
    val container: JsValue = opts.select("container").asOpt[JsValue] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    (container match {
      case JsObject(values) if value.isInstanceOf[JsString] => values.contains(value.asString)
      case JsArray(values)                                  => values.contains(value)
      case JsString(str) if value.isInstanceOf[JsString]    => str.contains(value.asString)
      case _                                                => false
    }).json
  }
}

class IsTruthyOperator extends WorkflowOperator { 
  override def documentationName: String = "$is_truthy"
  override def documentationDescription: String = "This operator checks if a value is truthy"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "any", "description" -> "The value to check"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$is_truthy" -> Json.obj(
      "value" -> 1
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOpt[JsValue] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    (value match {
      case JsNull                                                   => false
      case JsString(str) if str.isEmpty                             => false
      case JsBoolean(false)                                         => false
      case JsNumber(v) if v.bigDecimal == java.math.BigDecimal.ZERO => false
      case _                                                        => true
    }).json
  }
}

class IsFalsyOperator extends WorkflowOperator { 
  override def documentationName: String = "$is_falsy"
  override def documentationDescription: String = "This operator checks if a value is falsy"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "any", "description" -> "The value to check"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$is_falsy" -> Json.obj(
      "value" -> 0
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOpt[JsValue] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    val res            = value match {
      case JsNull                                                   => false
      case JsString(str) if str.isEmpty                             => false
      case JsBoolean(false)                                         => false
      case JsNumber(v) if v.bigDecimal == java.math.BigDecimal.ZERO => false
      case _                                                        => true
    }
    (!res).json
  }
}

class MemRefOperator extends WorkflowOperator { 
  override def documentationName: String = "$memref"
  override def documentationDescription: String = "This operator gets a value from the memory"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("name", "path"),
    "properties" -> Json.obj(
      "name" -> Json.obj("type" -> "string", "description" -> "The name of the memory entry"),
      "path" -> Json.obj("type" -> "string", "description" -> "The path of the memory entry"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$memref" -> Json.obj(
      "name" -> "my_memory",
      "path" -> "my_path"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val name = opts.select("name").asString
    val path = opts.select("path").asOptString
    wfr.memory.get(name) match {
      case None                          => JsNull
      case Some(value) if path.isEmpty   => value
      case Some(value) if path.isDefined => value.at(path.get).asValue
    }
  }
}

class JsonParseOperator extends WorkflowOperator { 
  override def documentationName: String = "$json_parse"
  override def documentationDescription: String = "This operator parses a JSON string"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "string", "description" -> "The JSON string to parse"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$json_parse" -> Json.obj(
      "value" -> "{}"
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOptString match {
      case Some(v) => v.json
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsString(str) => str.parseJson
      case _             => JsNull
    }
  }
}

class StrConcatOperator extends WorkflowOperator { 
  override def documentationName: String = "$str_concat"
  override def documentationDescription: String = "This operator concatenates a list of strings"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("values", "separator"),
    "properties" -> Json.obj(
      "values" -> Json.obj("type" -> "array", "description" -> "The list of strings to concatenate"),
      "separator" -> Json.obj("type" -> "string", "description" -> "The separator to use"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$str_concat" -> Json.obj(
      "values" -> Seq("Hello", "World"),
      "separator" -> " "
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val values    = opts.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    val separator = opts.select("separator").asOptString.getOrElse(" ")
    values.mkString(separator).json
  }
}

class MapGetOperator extends WorkflowOperator { 
  override def documentationName: String = "$map_get"
  override def documentationDescription: String = "This operator gets a value from a map"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("key", "map"),
    "properties" -> Json.obj(
      "key" -> Json.obj("type" -> "string", "description" -> "The key to get"),
      "map" -> Json.obj("type" -> "object", "description" -> "The map to get the value from"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$map_get" -> Json.obj(
      "key" -> "my_key",
      "map" -> Json.obj("my_key" -> "my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key            = opts.select("key").asString
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsObject(underlying) => underlying.get(key).getOrElse(JsNull)
      case _                    => JsNull
    }
  }
}

class MapDelOperator extends WorkflowOperator { 
  override def documentationName: String = "$map_del"
  override def documentationDescription: String = "This operator deletes a key from a map"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("key", "map"),
    "properties" -> Json.obj(
      "key" -> Json.obj("type" -> "string", "description" -> "The key to delete"),
      "map" -> Json.obj("type" -> "object", "description" -> "The map to delete the key from"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$map_del" -> Json.obj(
      "key" -> "my_key",
      "map" -> Json.obj("my_key" -> "my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key            = opts.select("key").asString
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case obj @ JsObject(_) => obj - key
      case _                 => JsNull
    }
  }
}

class MapPutOperator extends WorkflowOperator { 
  override def documentationName: String = "$map_put"
  override def documentationDescription: String = "This operator puts a key-value pair in a map"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("key", "value", "map"),
    "properties" -> Json.obj(
      "key" -> Json.obj("type" -> "string", "description" -> "The key to put"),
      "value" -> Json.obj("type" -> "any", "description" -> "The value to put"),
      "map" -> Json.obj("type" -> "object", "description" -> "The map to put the key-value pair in"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$map_put" -> Json.obj(
      "key" -> "my_key",
      "value" -> "my_value",
      "map" -> Json.obj("my_key" -> "my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key            = opts.select("key").asString
    val v              = opts.select("value").asValue
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case obj @ JsObject(_) => obj ++ Json.obj(key -> v)
      case _                 => JsNull
    }
  }
}

class ArrayAppendOperator extends WorkflowOperator { 
  override def documentationName: String = "$array_append"
  override def documentationDescription: String = "This operator appends a value to an array"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "array"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "any", "description" -> "The value to append"),
      "array" -> Json.obj("type" -> "array", "description" -> "The array to append the value to"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$array_append" -> Json.obj(
      "value" -> "my_value",
      "array" -> Seq("my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val v              = opts.select("value").asValue
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) if v.isInstanceOf[JsArray] => arr ++ v.asArray
      case arr @ JsArray(_)                            => arr.append(v)
      case _                                           => JsNull
    }
  }
}

class ArrayPrependOperator extends WorkflowOperator { 
  override def documentationName: String = "$array_prepend"
  override def documentationDescription: String = "This operator prepends a value to an array"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("value", "array"),
    "properties" -> Json.obj(
      "value" -> Json.obj("type" -> "any", "description" -> "The value to prepend"),
      "array" -> Json.obj("type" -> "array", "description" -> "The array to prepend the value to"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$array_prepend" -> Json.obj(
      "value" -> "my_value",
      "array" -> Seq("my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val v              = opts.select("value").asValue
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) if v.isInstanceOf[JsArray] => v.asArray ++ arr
      case arr @ JsArray(_)                            => arr.prepend(v)
      case _                                           => JsNull
    }
  }
}

class ArrayDelOperator extends WorkflowOperator { 
  override def documentationName: String = "$array_del"
  override def documentationDescription: String = "This operator deletes an element from an array"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("idx", "array"),
    "properties" -> Json.obj(
      "idx" -> Json.obj("type" -> "integer", "description" -> "The index of the element to delete"),
      "array" -> Json.obj("type" -> "array", "description" -> "The array to delete the element from"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$array_del" -> Json.obj(
      "idx" -> 0,
      "array" -> Seq("my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val idx            = opts.select("idx").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) => JsArray(arr.value.zipWithIndex.filterNot(_._2 == idx).map(_._1))
      case _                => JsNull
    }
  }
}

class ArrayAtOperator extends WorkflowOperator { 
  override def documentationName: String = "$array_at"
  override def documentationDescription: String = "This operator gets an element from an array"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("idx", "array"),
    "properties" -> Json.obj(
      "idx" -> Json.obj("type" -> "integer", "description" -> "The index of the element to get"),
      "array" -> Json.obj("type" -> "array", "description" -> "The array to get the element from"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$array_at" -> Json.obj(
      "idx" -> 0,
      "array" -> Seq("my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val idx            = opts.select("idx").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsArray(arr) => arr.apply(idx)
      case _            => JsNull
    }
  }
}

class ArrayPageOperator extends WorkflowOperator { 
  override def documentationName: String = "$array_page"
  override def documentationDescription: String = "This operator gets a page of an array"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("page", "page_size", "array"),
    "properties" -> Json.obj(
      "page" -> Json.obj("type" -> "integer", "description" -> "The page number"),
      "page_size" -> Json.obj("type" -> "integer", "description" -> "The page size"),
      "array" -> Json.obj("type" -> "array", "description" -> "The array to get the page from"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$array_page" -> Json.obj(
      "page" -> 1,
      "page_size" -> 10,
      "array" -> Seq("my_value")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val page           = opts.select("page").asInt
    val pageSize       = opts.select("page_size").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsArray(arr) => {
        val paginationPosition = page * pageSize
        val content            = arr.slice(paginationPosition, paginationPosition + pageSize)
        JsArray(content)
      }
      case _            => JsNull
    }
  }
}

class ProjectionOperator extends WorkflowOperator { 
  override def documentationName: String = "$projection"
  override def documentationDescription: String = "This operator projects a value"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("projection", "value"),
    "properties" -> Json.obj(
      "projection" -> Json.obj("type" -> "object", "description" -> "The projection to apply"),
      "value" -> Json.obj("type" -> "object", "description" -> "The value to project"),
    ))
  )
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "$projection" -> Json.obj(
      "projection" -> Json.obj("name" -> "my_name"),
      "value" -> Json.obj("name" -> "my_name")
    )
  ))
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val blueprint      = opts.select("projection").asObject
    val value: JsValue = opts.select("value").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    otoroshi.utils.Projection.project(value, blueprint, identity)
  }
}
