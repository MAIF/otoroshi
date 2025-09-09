package otoroshi.next.workflow

import akka.util.ByteString
import io.otoroshi.wasm4s.scaladsl.{WasmFunctionParameters, WasmSource, WasmSourceKind}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.BodyHelper
import otoroshi.utils.mailer._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.http.ResponseImplicits._
import otoroshi.wasm.WasmConfig
import play.api.Logger
import play.api.libs.json._

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object WorkflowFunctionsInitializer {
  def initDefaults(): Unit = {
    WorkflowFunction.registerFunction("core.log", new LogFunction())
    WorkflowFunction.registerFunction("core.hello", new HelloFunction())
    WorkflowFunction.registerFunction("core.http_client", new HttpClientFunction())
    WorkflowFunction.registerFunction("core.wasm_call", new WasmCallFunction())
    WorkflowFunction.registerFunction("core.workflow_call", new WorkflowCallFunction())
    WorkflowFunction.registerFunction("core.system_call", new SystemCallFunction())
    WorkflowFunction.registerFunction("core.store_keys", new StoreKeysFunction())
    WorkflowFunction.registerFunction("core.store_mget", new StoreMgetFunction())
    WorkflowFunction.registerFunction("core.store_match", new StoreMatchFunction())
    WorkflowFunction.registerFunction("core.store_get", new StoreGetFunction())
    WorkflowFunction.registerFunction("core.store_set", new StoreSetFunction())
    WorkflowFunction.registerFunction("core.store_del", new StoreDelFunction())
    WorkflowFunction.registerFunction("core.emit_event", new EmitEventFunction())
    WorkflowFunction.registerFunction("core.file_read", new FileReadFunction())
    WorkflowFunction.registerFunction("core.file_write", new FileWriteFunction())
    WorkflowFunction.registerFunction("core.file_del", new FileDeleteFunction())
    WorkflowFunction.registerFunction("core.state_get_all", new StateGetAllFunction())
    WorkflowFunction.registerFunction("core.state_get", new StateGetOneFunction())
    WorkflowFunction.registerFunction("core.send_mail", new SendMailFunction())
    WorkflowFunction.registerFunction("core.env_get", new EnvGetFunction())
    WorkflowFunction.registerFunction("core.config_read", new ConfigReadFunction())
    WorkflowFunction.registerFunction("core.compute_resume_token", new ComputeResumeTokenFunction())
    WorkflowFunction.registerFunction("core.memory_get", new MemoryGetFunction())
    WorkflowFunction.registerFunction("core.memory_set", new MemorySetFunction())
    WorkflowFunction.registerFunction("core.memory_del", new MemoryDelFunction())
    WorkflowFunction.registerFunction("core.memory_rename", new MemoryRenameFunction())
  }
}

class MemoryRenameFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.memory_rename"
  override def documentationDisplayName: String           = "Rename a value in the memory"
  override def documentationIcon: String                  = "fas fa-layer-group"
  override def documentationDescription: String           = "This function renames a value in the memory"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("old_name", "new_name"),
      "properties" -> Json.obj(
        "old_name" -> Json.obj("type" -> "string", "description" -> "The old name of the memory"),
        "new_name" -> Json.obj("type" -> "string", "description" -> "The new name of the memory")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.memory_rename",
      "args"     -> Json.obj(
        "old_name" -> "my_memory",
        "new_name" -> "my_new_memory"
      )
    )
  )
  override def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val oldName = args.select("old_name").asString
    val newName = args.select("new_name").asString
    if (wfr.memory.contains(oldName)) {
      wfr.memory.set(newName, wfr.memory.get(oldName).get)
      wfr.memory.remove(oldName)
    } 
    JsNull.rightf    
  } 
}

class MemoryDelFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.memory_del"
  override def documentationDisplayName: String           = "Delete a value from the memory"
  override def documentationIcon: String                  = "fas fa-layer-group"
  override def documentationDescription: String           = "This function deletes a value from the memory"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("name"),
      "properties" -> Json.obj(
        "name" -> Json.obj("type" -> "string", "description" -> "The name of the memory")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.memory_del",
      "args"     -> Json.obj(
        "name" -> "my_memory",
      )
    )
  )
  override def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val name = args.select("name").asString
    wfr.memory.remove(name)
    JsNull.rightf
  }
}

class MemorySetFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.memory_set"
  override def documentationDisplayName: String           = "Set a value in the memory"
  override def documentationIcon: String                  = "fas fa-layer-group"
  override def documentationDescription: String           = "This function sets a value in the memory"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("name", "value"),
      "properties" -> Json.obj(
        "name" -> Json.obj("type" -> "string", "description" -> "The name of the memory"),
        "value" -> Json.obj("type" -> "any", "description" -> "The value to set in the memory")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.memory_set",
      "args"     -> Json.obj(
        "name" -> "my_memory",
        "value" -> "my_value"
      )
    )
  )
  override def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val name = args.select("name").asString
    val value = args.select("value").asValue
    wfr.memory.set(name, value)
    JsNull.rightf
  }
}

class MemoryGetFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.memory_get"
  override def documentationDisplayName: String           = "Get a value from the memory"
  override def documentationIcon: String                  = "fas fa-layer-group"
  override def documentationDescription: String           = "This function gets a value from the memory"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("name", "path"),
      "properties" -> Json.obj(
        "name" -> Json.obj("type" -> "string", "description" -> "The name of the memory"),
        "path" -> Json.obj("type" -> "string", "description" -> "The path of the memory")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.memory_get",
      "args"     -> Json.obj(
        "name" -> "my_memory",
        "path" -> "my_path"
      )
    )
  )
  override def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val name = args.select("name").asString
    val path = args.select("path").asOptString
    val value = wfr.memory.get(name) match {
      case None                          => JsNull
      case Some(value) if path.isEmpty   => value
      case Some(value) if path.isDefined => value.at(path.get).asValue
    }
    value.rightf
  }
}

class ComputeResumeTokenFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.compute_resume_token"
  override def documentationDisplayName: String           = "Compute a resume token for the current workflow"
  override def documentationIcon: String                  = "fas fa-cogs"
  override def documentationDescription: String           = "This function computes a resume token for the current workflow"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj()
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.compute_resume_token",
      "args"     -> Json.obj()
    )
  )

  override def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    PausedWorkflowSession.computeToken(wfr.workflow_ref, wfr.id, env).json.rightf
  }
}

class ConfigReadFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.config_read"
  override def documentationDisplayName: String           = "Read from Otoroshi config."
  override def documentationIcon: String                  = "fas fa-cogs"
  override def documentationDescription: String           = "This function retrieves values from otoroshi config."
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("path"),
      "properties" -> Json.obj(
        "path" -> Json.obj("type" -> "string", "description" -> "The path of the config. to read")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.config_read",
      "args"     -> Json.obj(
        "path" -> "otoroshi.domain"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val path = args.select("path").asOptString.getOrElse("foo")
    env.configurationJson.at(path).asOpt[JsValue].filterNot(_ == JsNull) match {
      case None        => WorkflowError.apply("no value found", None, None).leftf
      case Some(value) => value.rightf
    }
  }
}

class EnvGetFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.env_get"
  override def documentationDisplayName: String           = "Get environment variable"
  override def documentationIcon: String                  = "fas fa-leaf"
  override def documentationDescription: String           = "This function retrieves values from environment variables"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("name"),
      "properties" -> Json.obj(
        "name" -> Json.obj("type" -> "string", "description" -> "The environment variable name")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.env_get",
      "args"     -> Json.obj(
        "name" -> "OPENAI_APIKEY"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val name = args.select("name").asOptString.getOrElse("--")
    sys.env.get(name) match {
      case None        => WorkflowError.apply("no value found", None, None).leftf
      case Some(value) => value.json.rightf
    }
  }
}

class SendMailFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.send_mail"
  override def documentationDisplayName: String           = "Send an email"
  override def documentationIcon: String                  = "fas fa-envelope"
  override def documentationDescription: String           = "This function sends an email"
  override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
      "from"      -> Json.obj(
        "type"    -> "string",
        "label"   -> "From",
        "props"   -> Json.obj("description" -> "The sender email address")
      ),
      "to"        -> Json.obj(
          "type"  -> "array",
          "label" -> "To",
          "props" -> Json.obj("description" -> "The recipient email addresses")
        ),
      "subject"   -> Json.obj(
          "type"  -> "string",
          "label" -> "Subject",
          "props" -> Json.obj("description" -> "The email subject")
        ),
      "html"      -> Json.obj(
          "type"  -> "code",
          "label" -> "HTML",
          "props" -> Json.obj(
            "editorOnly"  -> true,
            "description" -> "The email HTML content"
          )
        ),
      "mailer_config" -> Json.obj(
          "type"    -> "form",
          "label"   -> "Mailer config",
          "props"   -> Json.obj("description" -> "The mailer configuration"),
          "schema" -> Json.obj(
            "kind" -> Json.obj(
              "type"  -> "select",
              "label" -> "Kind",
              "props" -> Json.obj(
                "options" -> Seq(
                  Json.obj("label" -> "Mailjet", "value" -> "mailjet"),
                  Json.obj("label" -> "Mailgun", "value" -> "mailgun"),
                  Json.obj("label" -> "SendGrid", "value" -> "sendgrid"),
                )
              )
            ),
            "api_key" -> Json.obj(
              "type"  -> "string",
              "label" -> "API key"
            ),
            "domain"  -> Json.obj(
              "type"  -> "string",
              "label" -> "Domain"
            )
          )
        )
  ))
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("from", "to", "subject", "html", "mailer_config"),
      "properties" -> Json.obj(
        "from"          -> Json.obj("type" -> "string", "description" -> "The sender email address"),
        "to"            -> Json.obj("type" -> "array", "description" -> "The recipient email addresses"),
        "subject"       -> Json.obj("type" -> "string", "description" -> "The email subject"),
        "html"          -> Json.obj("type" -> "string", "description" -> "The email HTML content"),
        "mailer_config" -> Json.obj("type" -> "object", "description" -> "The mailer configuration")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.send_mail",
      "args"     -> Json.obj(
        "from"          -> "sender@example.com",
        "to"            -> Seq("recipient@example.com"),
        "subject"       -> "Test email",
        "html"          -> "Hello, this is a test email",
        "mailer_config" -> Json.obj(
          "kind"    -> "mailgun",
          "api_key" -> "your_api_key",
          "domain"  -> "your_domain"
        )
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val config                 = args.select("mailer_config").asOpt[JsObject].getOrElse(Json.obj())
    val from: EmailLocation    = EmailLocation.format.reads(args.select("from").asValue).get
    val to: Seq[EmailLocation] =
      args.select("from").asOpt[Seq[JsValue]].map(_.map(v => EmailLocation.format.reads(v).get)).getOrElse(Seq.empty)
    val subject                = args.select("subject").asString
    val html                   = args.select("html").asString
    args.select("mailer_config").select("kind").asOptString.getOrElse("mailgun").toLowerCase match {
      case "mailgun"  => {
        val mailer = new MailgunMailer(
          env,
          env.datastores.globalConfigDataStore.latest(),
          MailgunSettings.format.reads(config).get
        )
        mailer.send(from, to, subject, html).map { _ =>
          Json.obj("sent" -> true).right
        }
      }
      case "mailjet"  => {
        val mailer = new MailjetMailer(
          env,
          env.datastores.globalConfigDataStore.latest(),
          MailjetSettings.format.reads(config).get
        )
        mailer.send(from, to, subject, html).map { _ =>
          Json.obj("sent" -> true).right
        }
      }
      case "sendgrid" => {
        val mailer = new SendgridMailer(
          env,
          env.datastores.globalConfigDataStore.latest(),
          SendgridSettings.format.reads(config).get
        )
        mailer.send(from, to, subject, html).map { _ =>
          Json.obj("sent" -> true).right
        }
      }
      case v          => WorkflowError(s"mailer '${v}' not supported", None, None).leftf
    }
  }
}

class StateGetAllFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.state_get_all"
  override def documentationDisplayName: String           = "Get all resources from the state"
  override def documentationIcon: String                  = "fas fa-layer-group"
  override def documentationDescription: String           = "This function gets all resources from the state"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("name", "group", "version"),
      "properties" -> Json.obj(
        "name"    -> Json.obj("type" -> "string", "description" -> "The name of the resource"),
        "group"   -> Json.obj("type" -> "string", "description" -> "The group of the resource"),
        "version" -> Json.obj("type" -> "string", "description" -> "The version of the resource")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.state_get_all",
      "args"     -> Json.obj(
        "name"    -> "my_resource",
        "group"   -> "my_group",
        "version" -> "my_version"
      )
    )
  )

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val name    = args.select("name").asString
    val group   = args.select("group").asOptString.getOrElse("any")
    val version = args.select("version").asOptString.getOrElse("any")
    env.allResources.resources.find { res =>
      res.group == group && res.version.name == version && res.pluralName == name
    } match {
      case None           =>
        WorkflowError(
          s"resources not found",
          Some(Json.obj("name" -> name, "group" -> group, "version" -> version)),
          None
        ).leftf
      case Some(resource) => JsArray(resource.access.allJson()).rightf
    }
  }
}

class StateGetOneFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.state_get"
  override def documentationDisplayName: String           = "Get a resource from the state"
  override def documentationIcon: String                  = "fas fa-cube"
  override def documentationDescription: String           = "This function gets a resource from the state"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("id", "name", "group", "version"),
      "properties" -> Json.obj(
        "id"      -> Json.obj("type" -> "string", "description" -> "The ID of the resource"),
        "name"    -> Json.obj("type" -> "string", "description" -> "The name of the resource"),
        "group"   -> Json.obj("type" -> "string", "description" -> "The group of the resource"),
        "version" -> Json.obj("type" -> "string", "description" -> "The version of the resource")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.state_get_one",
      "args"     -> Json.obj(
        "id"      -> "my_id",
        "name"    -> "my_resource",
        "group"   -> "my_group",
        "version" -> "my_version"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val id      = args.select("id").asString
    val name    = args.select("name").asString
    val group   = args.select("group").asOptString.getOrElse("any")
    val version = args.select("version").asOptString.getOrElse("any")
    env.allResources.resources.find { res =>
      res.group == group && res.version.name == version && res.singularName == name
    } match {
      case None           =>
        WorkflowError(
          s"resources not found",
          Some(Json.obj("name" -> name, "group" -> group, "version" -> version)),
          None
        ).leftf
      case Some(resource) => resource.access.oneJson(id).getOrElse(JsNull).rightf
    }
  }
}

class FileDeleteFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.file_delete"
  override def documentationDisplayName: String           = "Delete a file"
  override def documentationIcon: String                  = "fas fa-trash"
  override def documentationDescription: String           = "This function deletes a file"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("path"),
      "properties" -> Json.obj(
        "path" -> Json.obj("type" -> "string", "description" -> "The path of the file to delete")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.file_delete",
      "args"     -> Json.obj(
        "path" -> "/path/to/file.txt"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val path = args.select("path").asString
    try {
      val f = new File(path)
      f.delete()
      Json.obj("file_path" -> f.getAbsolutePath).rightf
    } catch {
      case t: Throwable => WorkflowError(t.getMessage, None, None).leftf
    }
  }
}

class FileReadFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.file_read"
  override def documentationDisplayName: String           = "Read a file"
  override def documentationIcon: String                  = "fas fa-file-alt"
  override def documentationDescription: String           = "This function reads a file"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("path"),
      "properties" -> Json.obj(
        "path"          -> Json.obj("type" -> "string", "description" -> "The path of the file to read"),
        "parse_json"    -> Json.obj("type" -> "boolean", "description" -> "Whether to parse the file as JSON"),
        "encode_base64" -> Json.obj(
          "type"        -> "boolean",
          "description" -> "Whether to encode the file content in base64"
        )
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.file_read",
      "args"     -> Json.obj(
        "path"          -> "/path/to/file.txt",
        "parse_json"    -> true,
        "encode_base64" -> true
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val path         = args.select("path").asString
    val parseJson    = args.select("parse_json").asOptBoolean.getOrElse(false)
    val encodeBase64 = args.select("encode_base64").asOptBoolean.getOrElse(false)
    try {
      val content = Files.readAllBytes(new File(path).toPath)
      if (parseJson) {
        Json.parse(content).rightf
      } else if (encodeBase64) {
        ByteString(content).encodeBase64.utf8String.json.rightf
      } else {
        ByteString(content).utf8String.json.rightf
      }
    } catch {
      case t: Throwable => WorkflowError(t.getMessage, None, None).leftf
    }
  }
}

class FileWriteFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.file_write"
  override def documentationDisplayName: String           = "Write a file"
  override def documentationIcon: String                  = "fas fa-file-signature"
  override def documentationDescription: String           = "This function writes a file"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("path", "value"),
      "properties" -> Json.obj(
        "path"        -> Json.obj("type" -> "string", "description" -> "The path of the file to write"),
        "value"       -> Json.obj("type" -> "string", "description" -> "The value to write"),
        "prettify"    -> Json.obj("type" -> "boolean", "description" -> "Whether to prettify the JSON"),
        "from_base64" -> Json.obj("type" -> "boolean", "description" -> "Whether to decode the base64 content")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.file_write",
      "args"     -> Json.obj(
        "path"        -> "/path/to/file.txt",
        "value"       -> "my_value",
        "prettify"    -> true,
        "from_base64" -> true
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val path         =
      args.select("path").asOptString.getOrElse(Files.createTempFile("llm-ext-fw-", ".tmp").toFile.getAbsolutePath)
    val value        = args.select("value").asValue
    val prettify     = args.select("prettify").asOptBoolean.getOrElse(false)
    val decodeBase64 = args.select("from_base64").asOptBoolean.getOrElse(false)
    try {
      val f = new File(path)
      if (!f.exists()) {
        f.createNewFile()
      }
      if (prettify) {
        Files.writeString(f.toPath, value.prettify)
        Json.obj("file_path" -> f.getAbsolutePath).rightf
      } else if (decodeBase64) {
        Files.write(f.toPath, value.asString.byteString.decodeBase64.toArray)
        Json.obj("file_path" -> f.getAbsolutePath).rightf
      } else {
        Files.writeString(
          f.toPath,
          value match {
            case JsString(s)  => s
            case JsNumber(s)  => s.toString()
            case JsBoolean(s) => s.toString()
            case JsArray(_)   => value.stringify
            case JsObject(_)  => value.stringify
            case JsNull       => "null"
          }
        )
        Json.obj("file_path" -> f.getAbsolutePath).rightf
      }
    } catch {
      case t: Throwable => WorkflowError(t.getMessage, None, None).leftf
    }
  }
}

class EmitEventFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.emit_event"
  override def documentationDisplayName: String           = "Emit an event"
  override def documentationIcon: String                  = "fas fa-bullhorn"
  override def documentationDescription: String           = "This function emits an event"
  override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
    "event" -> Json.obj(
      "type"  -> "object",
      "label" -> "Event",
      "props" -> Json.obj(
        "description" -> "The event to emit"
      )
    )
  ))
  
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("event"),
      "properties" -> Json.obj(
        "event" -> Json.obj("type" -> "object", "description" -> "The event to emit")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.emit_event",
      "args"     -> Json.obj(
        "event" -> Json.obj(
          "type"       -> "object",
          "properties" -> Json.obj(
            "name" -> Json.obj("type" -> "string", "description" -> "The name of the event")
          )
        )
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val event = args.select("event").asOpt[JsObject].getOrElse(Json.obj())
    WorkflowEmitEvent(event, env).toAnalytics()
    JsNull.rightf
  }
}

object LogFunction {
  val logger = Logger("otoroshi-workflow-log")
}

class LogFunction extends WorkflowFunction {

  override def documentationName: String                  = "core.log"
  override def documentationDisplayName: String           = "Log a message"
  override def documentationIcon: String                  = "fas fa-clipboard-list"
  override def documentationDescription: String           = "This function writes whatever the user want to the otoroshi logs"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "message" -> Json.obj(
        "type"  -> "string",
        "label" -> "Message",
        "props" -> Json.obj(
          "description" -> "The message to log"
        )
      ),
      "params"  -> Json.obj(
        "type"  -> "array",
        "props" -> Json.obj(
          "description" -> "The parameters to log"
        ),
        "label" -> "Parameters"
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("message"),
      "properties" -> Json.obj(
        "message" -> Json.obj("type" -> "string", "description" -> "The message to log"),
        "params"  -> Json.obj("type" -> "array", "description" -> "The parameters to log")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.log",
      "args"     -> Json.obj(
        "message" -> "Hello",
        "params"  -> Json.arr("World")
      )
    )
  )

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val message = args.select("message").asString
    val params  = args.select("params").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(_.stringify).mkString(" ")
    LogFunction.logger.info(message + " " + params)
    JsNull.rightf
  }
}

class HelloFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.hello"
  override def documentationDisplayName: String           = "Hello function"
  override def documentationIcon: String                  = "fas fa-hand-paper"
  override def documentationDescription: String           = "This function returns a hello message"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "name" -> Json.obj(
        "type"  -> "string",
        "label" -> "Name",
        "props" -> Json.obj(
          "description" -> "The name of the person to great"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("name"),
      "properties" -> Json.obj(
        "name" -> Json.obj("type" -> "string", "description" -> "The name of the person to greet")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.hello",
      "args"     -> Json.obj(
        "name" -> "Otoroshi"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val name    = args.select("name").asOptString.getOrElse("Stranger")
    val message = s"Hello ${name} !"
    println(message)
    message.json.rightf
  }
}

class HttpClientFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.http_client"
  override def documentationDisplayName: String           = "HTTP client"
  override def documentationIcon: String                  = "fas fa-network-wired"
  override def documentationDescription: String           = "This function makes a HTTP request"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "url"         -> Json.obj("label" -> "URL", "type" -> "string", "props" -> Json.obj("description" -> "The URL to call")),
      "method"      -> Json
        .obj("label" -> "Nethod", "type" -> "string", "props" -> Json.obj("description" -> "The HTTP method to use")),
      "headers"     -> Json
        .obj("label" -> "Headers", "type" -> "object", "props" -> Json.obj("description" -> "The headers to send")),
      "timeout"     -> Json.obj(
        "label" -> "Timeout",
        "type"  -> "number",
        "props" -> Json.obj("description" -> "The timeout in milliseconds")
      ),
      "body"        -> Json
        .obj("label" -> "Body", "type" -> "string", "props" -> Json.obj("description" -> "The body (string) to send")),
      "body_str"    -> Json.obj(
        "label" -> "String Body",
        "type"  -> "string",
        "props" -> Json.obj("description" -> "The body (string) to send")
      ),
      "body_json"   -> Json.obj(
        "label" -> "JSON Body",
        "type"  -> "object",
        "props" -> Json.obj("description" -> "The body (json) to send")
      ),
      "body_bytes"  -> Json.obj(
        "label" -> "Bytes Body",
        "type"  -> "array",
        "props" -> Json.obj("description" -> "The body (bytes array) to send")
      ),
      "body_base64" -> Json.obj(
        "label" -> "Base64 body",
        "type"  -> "string",
        "props" -> Json.obj("description" -> "The body (base64) to send")
      ),
      "tls_config"  -> Json.obj(
        "label" -> "TLS Configuration",
        "type"  -> "object",
        "props" -> Json.obj("description" -> "The TLS configuration")
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("url"),
      "properties" -> Json.obj(
        "url"         -> Json.obj("type" -> "string", "description" -> "The URL to call"),
        "method"      -> Json.obj("type" -> "string", "description" -> "The HTTP method to use"),
        "headers"     -> Json.obj("type" -> "object", "description" -> "The headers to send"),
        "timeout"     -> Json.obj("type" -> "number", "description" -> "The timeout in milliseconds"),
        "body"        -> Json.obj("type" -> "string", "description" -> "The body (string) to send"),
        "body_str"    -> Json.obj("type" -> "string", "description" -> "The body (string) to send"),
        "body_json"   -> Json.obj("type" -> "object", "description" -> "The body (json) to send"),
        "body_bytes"  -> Json.obj("type" -> "array", "description" -> "The body (bytes array) to send"),
        "body_base64" -> Json.obj("type" -> "string", "description" -> "The body (base64) to send"),
        "tls_config"  -> Json.obj("type" -> "object", "description" -> "The TLS configuration")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.http_client",
      "args"     -> Json.obj(
        "url"       -> "https://httpbin.org/get",
        "method"    -> "GET",
        "headers"   -> Json.obj(
          "User-Agent" -> "Otoroshi"
        ),
        "timeout"   -> 30000,
        "body_json" -> Json.obj("foo" -> "bar")
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val url       = args.select("url").asString
    val method    = args.select("method").asOptString.getOrElse("GET")
    val headers   = args.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    val timeout   = args.select("timeout").asOpt[Long].map(_.millis).getOrElse(30.seconds)
    val body      = BodyHelper.extractBodyFromOpt(args)
    val tlsConfig =
      args.select("tls_config").asOpt[JsObject].flatMap(v => NgTlsConfig.format.reads(v).asOpt).getOrElse(NgTlsConfig())
    env.MtlsWs
      .url(url, tlsConfig.legacy)
      .withRequestTimeout(timeout)
      .withMethod(method)
      .withHttpHeaders(headers.toSeq: _*)
      .applyOnWithOpt(body) { case (builder, body) =>
        builder.withBody(body)
      }
      .execute()
      .map { resp =>
        val body_str: String   = resp.body
        val body_json: JsValue = if (resp.contentType.contains("application/json")) body_str.parseJson else JsNull
        Json
          .obj(
            "status"    -> resp.status,
            "headers"   -> resp.headers,
            "cookies"   -> JsArray(resp.safeCookies(env).map(_.json)),
            "body_str"  -> body_str,
            "body_json" -> body_json
          )
          .right
      }
      .recover { case t: Throwable =>
        WorkflowError(s"caught exception on http call", None, Some(t)).left
      }
  }
}

class WorkflowCallFunction extends WorkflowFunction {

  override def documentationName: String                  = "core.workflow_call"
  override def documentationDisplayName: String           = "Call a workflow"
  override def documentationIcon: String                  = "fas fa-project-diagram"
  override def documentationDescription: String           = "This function calls another workflow stored in otoroshi"
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("workflow_id", "input"),
      "properties" -> Json.obj(
        "workflow_id" -> Json.obj("type" -> "string", "description" -> "The ID of the workflow to call"),
        "input"       -> Json.obj("type" -> "object", "description" -> "The input of the workflow")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.workflow_call",
      "args"     -> Json.obj(
        "workflow_id" -> "my_workflow_id",
        "input"       -> Json.obj(
          "foo" -> "bar"
        )
      )
    )
  )

  override def callWithRun(
      args: JsObject
  )(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val workflowId = args.select("workflow_id").asString
    val input      = args.selectAsOptObject("input").getOrElse(Json.obj())
    val extension  = env.adminExtensions.extension[WorkflowAdminExtension].get
    extension.states.workflow(workflowId) match {
      case None           => Left(WorkflowError("workflow not found", Some(Json.obj("workflow_id" -> workflowId)), None)).vfuture
      case Some(workflow) => {
        val node = Node.from(workflow.config)
        extension.engine.run(workflowId, node, input, wfr.attrs, workflow.functions).map {
          case res if res.hasError => Left(res.error.get)
          case res                 => Right(res.returned.get)
        }
      }
    }
  }
}

class SystemCallFunction extends WorkflowFunction {

  import scala.sys.process._

  override def documentationName: String                  = "core.system_call"
  override def documentationDisplayName: String           = "System call"
  override def documentationIcon: String                  = "fas fa-terminal"
  override def documentationDescription: String           = "This function calls a system command"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "command" -> Json.obj(
        "type"  -> "array",
        "label" -> "Command",
        "props" -> Json.obj(
          "description" -> "The command to execute"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("command"),
      "properties" -> Json.obj(
        "command" -> Json.obj("type" -> "array", "description" -> "The command to execute")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.system_call",
      "args"     -> Json.obj(
        "command" -> Seq("ls", "-l")
      )
    )
  )

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    try {
      var stdout        = ""
      var stderr        = ""
      val command       = args.select("command").asOpt[Seq[String]].getOrElse(Seq.empty)
      val processLogger = ProcessLogger(
        out => {
          stdout = stdout + out
          println(s"[stdout] $out")
        },
        err => {
          stderr = stderr + err
          println(s"[stderr] $err")
        }
      )
      val code          = command.!(processLogger)
      Json.obj("stdout" -> stdout, "stderr" -> stderr, "code" -> code).rightf
    } catch {
      case t: Throwable => Left(WorkflowError(t.getMessage, None, None)).vfuture
    }
  }
}

class WasmCallFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.wasm_call"
  override def documentationDisplayName: String           = "Wasm call"
  override def documentationIcon: String                  = "fas fa-cube"
  override def documentationDescription: String           = "This function calls a wasm function"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "wasm_plugin" -> Json.obj(
        "type"  -> "string",
        "label" -> "Wasm Plugin",
        "props" -> Json.obj(
          "description" -> "The wasm plugin to user"
        )
      ),
      "function"    -> Json.obj(
        "type"  -> "string",
        "label" -> "Function",
        "props" -> Json.obj(
          "description" -> "The function to call"
        )
      ),
      "params"      -> Json.obj(
        "type"  -> "string",
        "label" -> "Parameters",
        "props" -> Json.obj(
          "description" -> "The parameters to passed to the function"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("wasm_plugin", "function"),
      "properties" -> Json.obj(
        "wasm_plugin" -> Json.obj("type" -> "string", "description" -> "The wasm plugin to use"),
        "function"    -> Json.obj("type" -> "string", "description" -> "The function to call"),
        "params"      -> Json.obj("type" -> "object", "description" -> "The parameters to passed to the function")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.wasm_call",
      "args"     -> Json.obj(
        "wasm_plugin" -> "my_wasm_plugin",
        "function"    -> "my_function",
        "params"      -> Json.obj(
          "foo" -> "bar"
        )
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val wasmSource   = args.select("wasm_plugin").asString
    val functionName = args.select("function").asOptString.getOrElse("call")
    val params       = args.select("params").asValue.stringify

    env.wasmIntegration
      .wasmVmFor(
        WasmConfig(
          WasmSource(WasmSourceKind.Local, wasmSource, Json.obj())
        )
      )
      .flatMap {
        case None                    => WorkflowError(s"wasm plugin not found", Some(Json.obj("wasm_plugin" -> wasmSource)), None).leftf
        case Some((vm, localConfig)) =>
          vm.call(
            WasmFunctionParameters.ExtismFuntionCall(
              functionName,
              params
            ),
            None
          ).map {
            case Right(res)  => Right(Json.parse(res._1))
            case Left(value) =>
              WorkflowError(
                s"error while calling wasm function",
                Some(Json.obj("wasm_plugin" -> wasmSource, "function" -> functionName, "error" -> value)),
                None
              ).left
          }.andThen { case _ =>
            vm.release()
          }
      }
  }
}

class StoreDelFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.store_del"
  override def documentationDisplayName: String           = "Datastore delete"
  override def documentationIcon: String                  = "fas fa-eraser"
  override def documentationDescription: String           = "This function deletes keys from the store"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "keys" -> Json.obj(
        "type"  -> "array",
        "label" -> "Wasm Plugin",
        "props" -> Json.obj(
          "description" -> "The keys to delete"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("keys"),
      "properties" -> Json.obj(
        "keys" -> Json.obj("type" -> "array", "description" -> "The keys to delete")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.store_del",
      "args"     -> Json.obj(
        "keys" -> Seq("key1", "key2")
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val keys = args.select("keys").asOpt[Seq[String]].getOrElse(Seq.empty)
    env.datastores.rawDataStore.del(keys).map { r =>
      Right(r.json)
    }
  }
}

class StoreGetFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.store_get"
  override def documentationDisplayName: String           = "Datastore get"
  override def documentationIcon: String                  = "fas fa-download"
  override def documentationDescription: String           = "This function gets keys from the store"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "key" -> Json.obj(
        "type"  -> "string",
        "label" -> "Key",
        "props" -> Json.obj(
          "description" -> "The key to get"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("key"),
      "properties" -> Json.obj(
        "key" -> Json.obj("type" -> "string", "description" -> "The key to get")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.store_get",
      "args"     -> Json.obj(
        "key" -> "my_key"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    args.select("key").asOptString match {
      case None      => Right(JsNull).vfuture
      case Some(key) =>
        env.datastores.rawDataStore.get(key).map {
          case None        => Right(JsNull)
          case Some(value) => Right(value.utf8String.json)
        }
    }
  }
}

class StoreSetFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.store_set"
  override def documentationDisplayName: String           = "Datastore set"
  override def documentationIcon: String                  = "fas fa-upload"
  override def documentationDescription: String           = "This function sets a key in the datastore"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "key"   -> Json.obj(
        "type"  -> "string",
        "label" -> "Key",
        "props" -> Json.obj(
          "description" -> "The key to set"
        )
      ),
      "value" -> Json.obj(
        "type"  -> "string",
        "label" -> "Value",
        "props" -> Json.obj(
          "description" -> "The value to set"
        )
      ),
      "ttl"   -> Json.obj(
        "type"  -> "number",
        "label" -> "TTL",
        "props" -> Json.obj(
          "description" -> "The optional time to live in seconds"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("key", "value"),
      "properties" -> Json.obj(
        "key"   -> Json.obj("type" -> "string", "description" -> "The key to set"),
        "value" -> Json.obj("type" -> "string", "description" -> "The value to set"),
        "ttl"   -> Json.obj("type" -> "number", "description" -> "The optional time to live in seconds")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.store_set",
      "args"     -> Json.obj(
        "key"   -> "my_key",
        "value" -> "my_value",
        "ttl"   -> 3600
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val key   = args.select("key").asString
    val value = args.select("value").asValue
    val ttl   = args.select("ttl").asOptLong
    env.datastores.rawDataStore.set(key, value.stringify.byteString, ttl).map { _ =>
      Right(JsNull)
    }
  }
}

class StoreKeysFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.store_keys"
  override def documentationDisplayName: String           = "Datastore list keys"
  override def documentationIcon: String                  = "fas fa-key"
  override def documentationDescription: String           = "This function lists keys from the datastore"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "pattern" -> Json.obj(
        "type"  -> "string",
        "label" -> "Pattern",
        "props" -> Json.obj(
          "description" -> "The pattern to match"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("pattern"),
      "properties" -> Json.obj(
        "pattern" -> Json.obj("type" -> "string", "description" -> "The pattern to match")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.store_keys",
      "args"     -> Json.obj(
        "pattern" -> "my_pattern:*"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val pattern = args.select("pattern").asString
    env.datastores.rawDataStore.keys(pattern).map { seq =>
      Right(JsArray(seq.map(_.json)))
    }
  }
}

class StoreMgetFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.store_mget"
  override def documentationDisplayName: String           = "Datastore get multiple keys"
  override def documentationIcon: String                  = "fas fa-boxes"
  override def documentationDescription: String           = "This function gets multiple keys from the datastore"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "keys" -> Json.obj(
        "type"  -> "array",
        "label" -> "Keys",
        "props" -> Json.obj(
          "description" -> "The keys to get"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("keys"),
      "properties" -> Json.obj(
        "keys" -> Json.obj("type" -> "array", "description" -> "The keys to get")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.store_mget",
      "args"     -> Json.obj(
        "keys" -> Seq("key1", "key2")
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val keys = args.select("keys").asOpt[Seq[String]].getOrElse(Seq.empty)
    env.datastores.rawDataStore.mget(keys).map { seq =>
      Right(JsArray(seq.collect { case Some(bs) => bs.utf8String.json }))
    }
  }
}

class StoreMatchFunction extends WorkflowFunction {
  override def documentationName: String                  = "core.store_match"
  override def documentationDisplayName: String           = "Datastore matching keys"
  override def documentationIcon: String                  = "fas fa-search"
  override def documentationDescription: String           = "This function gets keys from the datastore matching a pattern"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "pattern" -> Json.obj(
        "type"  -> "string",
        "label" -> "Pattern",
        "props" -> Json.obj(
          "description" -> "The pattern to match"
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Some(
    Json.obj(
      "type"       -> "object",
      "required"   -> Seq("pattern"),
      "properties" -> Json.obj(
        "pattern" -> Json.obj("type" -> "string", "description" -> "The pattern to match")
      )
    )
  )
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"     -> "call",
      "function" -> "core.store_match",
      "args"     -> Json.obj(
        "pattern" -> "my_pattern:*"
      )
    )
  )
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val pattern = args.select("pattern").asString
    env.datastores.rawDataStore.allMatching(pattern).map { seq =>
      Right(JsArray(seq.map(_.utf8String.json)))
    }
  }
}

case class WorkflowEmitEvent(
    payload: JsObject,
    env: Env
) extends AnalyticEvent {

  val `@id`: String                 = env.snowflakeGenerator.nextIdStr()
  val `@timestamp`: DateTime        = DateTime.now()
  val fromOrigin: Option[String]    = None
  val fromUserAgent: Option[String] = None
  val `@type`: String               = "WorkflowEmitEvent"
  val `@service`: String            = "Otoroshi"
  val `@serviceId`: String          = ""

  override def toJson(implicit _env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"      -> "WorkflowEmitEvent",
      "@product"   -> _env.eventsName,
      "@serviceId" -> "",
      "@service"   -> "Otoroshi",
      "@env"       -> env.env
    ) ++ payload
  }
}
