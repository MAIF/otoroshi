package controllers

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import auth.{BasicAuthModuleConfig, GenericOauth2ModuleConfig, LdapAuthModuleConfig}
import env.Env
import models.{GlobalConfig, GlobalJwtVerifier}
import org.mindrot.jbcrypt.BCrypt
import otoroshi.script.{Script, TransformerType}
import otoroshi.ssl.pki.models.GenCsrQuery
import otoroshi.tcp._
import play.api.Logger
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import security.IdGenerator
import ssl.ClientAuth

class TemplatesController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-templates-api")

  def process(json: JsValue, req: RequestHeader): JsValue = {
    val over = req.queryString
      .filterNot(_._1 == "rawPassword")
      .map(t => Json.obj(t._1 -> t._2.head))
      .foldLeft(Json.obj())(_ ++ _)
    json.as[JsObject] ++ over
  }

  def initiateApiKey(groupId: Option[String]) = ApiAction.async { ctx =>
    groupId match {
      case Some(gid) => {
        env.datastores.serviceGroupDataStore.findById(gid).map {
          case Some(group) => {
            val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid)
            Ok(process(apiKey.toJson, ctx.request))
          }
          case None => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
        }
      }
      case None => {
        val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
        FastFuture.successful(Ok(process(apiKey.toJson, ctx.request)))
      }
    }
  }

  def initiateServiceGroup() = ApiAction { ctx =>
    val group = env.datastores.serviceGroupDataStore.initiateNewGroup()
    Ok(process(group.toJson, ctx.request))
  }

  def initiateService() = ApiAction { ctx =>
    val desc = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
    Ok(process(desc.toJson, ctx.request))
  }

  def initiateTcpService() = ApiAction { ctx =>
    Ok(
      process(
        TcpService(
          id = IdGenerator.token,
          enabled = true,
          tls = TlsMode.Disabled,
          sni = SniSettings(false, false),
          clientAuth = ClientAuth.None,
          port = 4200,
          rules = Seq(
            TcpRule(
              domain = "*",
              targets = Seq(
                TcpTarget(
                  "42.42.42.42",
                  None,
                  4200,
                  false
                )
              )
            )
          )
        ).json,
        ctx.request
      )
    )
  }

  def initiateCertificate() = ApiAction.async { ctx =>
    env.pki
      .genSelfSignedCert(
        GenCsrQuery(
          hosts = Seq("www.oto.tools"),
          subject = Some("C=FR, OU=Foo, O=Bar")
        )
      )
      .map { c =>
        Ok(process(c.toOption.get.toCert.toJson, ctx.request))
      }
  }

  def initiateGlobalConfig() = ApiAction { ctx =>
    Ok(process(GlobalConfig().toJson, ctx.request))
  }

  def initiateJwtVerifier() = ApiAction { ctx =>
    Ok(
      process(GlobalJwtVerifier(
                id = IdGenerator.token,
                name = "New jwt verifier",
                desc = "New jwt verifier"
              ).asJson,
              ctx.request)
    )
  }

  def initiateAuthModule() = ApiAction { ctx =>
    ctx.request.getQueryString("mod-type") match {
      case Some("oauth2") =>
        Ok(
          process(GenericOauth2ModuleConfig(
                    id = IdGenerator.token,
                    name = "New auth. module",
                    desc = "New auth. module"
                  ).asJson,
                  ctx.request)
        )
      case Some("oauth2-global") =>
        Ok(
          process(GenericOauth2ModuleConfig(
                    id = IdGenerator.token,
                    name = "New auth. module",
                    desc = "New auth. module"
                  ).asJson,
                  ctx.request)
        )
      case Some("basic") =>
        Ok(
          process(BasicAuthModuleConfig(
                    id = IdGenerator.token,
                    name = "New auth. module",
                    desc = "New auth. module"
                  ).asJson,
                  ctx.request)
        )
      case Some("ldap") =>
        Ok(
          process(
            LdapAuthModuleConfig(
              id = IdGenerator.token,
              name = "New auth. module",
              desc = "New auth. module",
              serverUrl = "ldap://ldap.forumsys.com:389",
              searchBase = "dc=example,dc=com",
              searchFilter = "(uid=${username})",
              adminUsername = Some("cn=read-only-admin,dc=example,dc=com"),
              adminPassword = Some("password")
            ).asJson,
            ctx.request
          )
        )
      case _ =>
        Ok(
          process(BasicAuthModuleConfig(
                    id = IdGenerator.token,
                    name = "New auth. module",
                    desc = "New auth. module"
                  ).asJson,
                  ctx.request)
        )
    }
  }

  def initiateScript() = ApiAction { ctx =>
    Ok(
      process(
        Script(
          id = IdGenerator.token,
          name = "New request transformer",
          desc = "New request transformer",
          code = """import akka.stream.Materializer
          |import env.Env
          |import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
          |import otoroshi.script._
          |import play.api.Logger
          |import play.api.mvc.{Result, Results}
          |import scala.util._
          |import scala.concurrent.{ExecutionContext, Future}
          |
          |/**
          | * Your own request transformer
          | */
          |class MyTransformer extends RequestTransformer {
          |
          |  val logger = Logger("my-transformer")
          |
          |  override def transformRequestSync(
          |    snowflake: String,
          |    rawRequest: HttpRequest,
          |    otoroshiRequest: HttpRequest,
          |    desc: ServiceDescriptor,
          |    apiKey: Option[ApiKey],
          |    user: Option[PrivateAppsUser]
          |  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, HttpRequest] = {
          |    logger.info(s"Request incoming with id: $snowflake")
          |    // Here add a new header to the request between otoroshi and the target
          |    Right(otoroshiRequest.copy(
          |      headers = otoroshiRequest.headers + ("Hello" -> "World")
          |    ))
          |  }
          |}
          |
          |// don't forget to return an instance of the transformer to make it work
          |new MyTransformer()
        """.stripMargin,
          `type` = TransformerType
        ).toJson,
        ctx.request
      )
    )
  }

  def initiateSimpleAdmin() = ApiAction { ctx =>
    val pswd: String = ctx.request
      .getQueryString("rawPassword")
      .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
      .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
    Ok(
      process(Json.obj(
                "username"        -> "user@otoroshi.io",
                "password"        -> pswd,
                "label"           -> "user@otoroshi.io",
                "authorizedGroup" -> JsNull
              ),
              ctx.request)
    )
  }

  def initiateWebauthnAdmin() = ApiAction { ctx =>
    val pswd: String = ctx.request
      .getQueryString("rawPassword")
      .map(v => BCrypt.hashpw(v, BCrypt.gensalt()))
      .getOrElse(BCrypt.hashpw("password", BCrypt.gensalt()))
    Ok(
      process(Json.obj(
                "username"        -> "user@otoroshi.io",
                "password"        -> pswd,
                "label"           -> "user@otoroshi.io",
                "authorizedGroup" -> JsNull
              ),
              ctx.request)
    )
  }

}
