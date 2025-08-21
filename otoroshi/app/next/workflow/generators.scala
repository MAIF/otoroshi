package otoroshi.next.workflow

import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

object WorkflowGenerators {

  def generateJsonDescriptor(): JsValue = {
    Json.obj(
      "categories"-> JsArray(Node.categories.map { case (key, informations) =>
        Json.obj("id" -> key).deepMerge(informations.json)
      }.toSeq),
      "nodes"     -> JsArray(Node.nodes.map { case (key, value) =>
        val node = value.apply(Json.obj())
        Json.obj(
          "name"        -> node.documentationName,
          "display_name" -> node.documentationDisplayName,
          "icon" -> node.documentationIcon,
          "description" -> node.documentationDescription,
          "schema"      -> node.documentationInputSchema,
          "form_schema" -> node.documentationFormSchema,
          "category"    -> node.documentationCategory,
          "example"     -> node.documentationExample,
        )
      }.toSeq),
      "functions" -> JsArray(WorkflowFunction.functions.map { case (key, value) =>
        Json.obj(
          "name"        -> key,
          "description" -> value.documentationDescription,
          "schema"      -> value.documentationInputSchema,
          "form_schema" -> value.documentationFormSchema,
          "category"    -> "functions",
          "example"     -> value.documentationExample,
          "display_name" -> value.documentationDisplayName,
          "icon" -> value.documentationIcon,
        )
      }.toSeq),
      "operators" -> JsArray(WorkflowOperator.operators.map { case (key, value) =>
        Json.obj(
          "name"        -> key,
          "description" -> value.documentationDescription,
          "schema"      -> value.documentationInputSchema,
          "form_schema" -> value.documentationFormSchema,
          "category"    -> "operators",
          "example"     -> value.documentationExample,
          "display_name" -> value.documentationDisplayName,
          "icon" -> value.documentationIcon,
        )
      }.toSeq)
    )
  }

  def schemaToMarkdown(schema: JsObject, leftPad: Int = 0): String = {
    if (schema.fields.isEmpty) {
      ""
    } else {
      s"""${schema
        .select("properties")
        .asOpt[Map[String, JsObject]]
        .map { properties =>
          properties
            .map {
              case (k, v) if v.select("items").isDefined =>
                s"${(0 to leftPad).map(_ => " ").mkString("")}- `${k}` (`${v
                   .select("type")
                   .asOpt[String]
                   .getOrElse("any")}`) - ${v.select("description").asOpt[String].getOrElse("")}\n" +
                  schemaToMarkdown(v.select("items").asObject, leftPad + 4)
              case (k, v)                                =>
                s"${(0 to leftPad).map(_ => " ").mkString("")}- `${k}` (`${v
                   .select("type")
                   .asOpt[String]
                   .getOrElse("any")}`) - ${v.select("description").asOpt[String].getOrElse("")}"
            }
            .mkString("\n")
        }
        .getOrElse("")}
         |${(0 to leftPad).map(_ => " ").mkString("")}- required fields are: ${schema
        .select("required")
        .asOpt[Seq[String]]
        .getOrElse(Seq.empty)
        .map(v => s"**$v**")
        .mkString(", ")}""".stripMargin
    }
  }

  def generateMarkdownDescriptor(): String = {

    val nodes = Node.nodes.map { case (key, value) =>
      val node    = value.apply(Json.obj())
      val example = node.documentationExample match {
        case None          => ""
        case Some(example) =>
          s"""
               |
               |Usage example
               |
               |```json
               |${example.prettify}
               |```
               |""".stripMargin
      }
      s"""
           |#### <span class="${node.documentationIcon}"></span> `${node.documentationDisplayName} (${node.documentationName})`
           |
           |${node.documentationDescription}
           |
           |expected configuration:
           |
           |${schemaToMarkdown(node.documentationInputSchema.getOrElse(Json.obj()))}${example}""".stripMargin
    }.toSeq

    val functions = WorkflowFunction.functions.map { case (key, function) =>
      val example = function.documentationExample match {
        case None          => ""
        case Some(example) =>
          s"""
               |
               |Usage example
               |
               |```json
               |${example.prettify}
               |```
               |""".stripMargin
      }
      s"""
           |#### <span class="${function.documentationIcon}"></span>`${function.documentationDisplayName} ($key)`
           |
           |${function.documentationDescription}
           |
           |expected configuration:
           |
           |${schemaToMarkdown(function.documentationInputSchema.getOrElse(Json.obj()))}${example}""".stripMargin
    }.toSeq

    val operators = WorkflowOperator.operators.map { case (key, operator) =>
      val example = operator.documentationExample match {
        case None          => ""
        case Some(example) =>
          s"""
               |
               |Usage example
               |
               |```json
               |${example.prettify}
               |```
               |""".stripMargin
      }
      s"""
           |#### <span class="${operator.documentationIcon}"></span> `${operator.documentationDisplayName} ($key)`
           |
           |${operator.documentationDescription}
           |
           |expected configuration:
           |
           |${schemaToMarkdown(operator.documentationInputSchema.getOrElse(Json.obj()))}${example}""".stripMargin
    }.toSeq

    s"""
      |# Otoroshi Workflows documentation
      |
      |## Nodes
      |
      |Each node must declare a `kind` field and can optionally define:
      |
      |${schemaToMarkdown(Node.baseInputSchema)}
      |
      |### Full List of Nodes
      |
      |${nodes.mkString("\n\n---\n\n")}
      |
      |## Functions
      |
      |Workflow functions are reusable logic blocks invoked via `call` nodes.
      |
      |Prototype:
      |
      |```json
      |{
      |  "kind": "call",
      |  "function": "<function_name>",
      |  "args": { ... },
      |  "result": "<memory_var_name>"
      |}
      |```
      |
      |### Full List of Functions
      |
      |${functions.mkString("\n\n---\n\n")}
      |
      |## Operators
      |
      |Operators are one-key JSON objects (e.g. `{ "$$eq": { ... } }`) used to manipulate data. They can:
      |
      |* Navigate and extract memory (`$$mem_ref`)
      |* Transform strings (`$$str_concat`, `$$encode_base64`, `$$decode_base64`)
      |* Compare values (`$$eq`, `$$gt`, `$$lt`, `$$gte`, `$$lte`, `$$neq`)
      |* Evaluate truthiness (`$$is_truthy`, `$$is_falsy`, `$$not`)
      |* Work with arrays/maps (`$$array_append`, `$$array_prepend`, `$$array_at`, `$$array_del`, `$$array_page`, `$$map_get`, `$$map_put`, `$$map_del`)
      |* Perform math (`$$add`, `$$subtract`, `$$multiply`, `$$divide`)
      |* Parse and format dates/times (`$$parse_datetime`, `$$parse_date`, `$$parse_time`, `$$now`)
      |* Parse and project JSON (`$$json_parse`, `$$projection`)
      |* Perform expression evaluation (`$$expression_language`)
      |* Create auth headers (`$$basic_auth`)
      |* Check containment (`$$contains`)
      |
      |Example:
      |
      |```json
      |{
      |  "$$eq": {
      |    "a": "foo",
      |    "b": "bar"
      |  }
      |}
      |```
      |
      |Result: `false`
      |
      |### Full List of Operators
      |
      |${operators.mkString("\n\n---\n\n")}
      |""".stripMargin
  }

}
