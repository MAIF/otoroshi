package otoroshi.next.plugins

import akka.Done
import com.clevercloud.biscuit.crypto.PublicKey
import com.clevercloud.biscuit.token.builder.Term.Str
import com.clevercloud.biscuit.token.{Authorizer, Biscuit}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.next.plugins.api._
import otoroshi.plugins.biscuit._
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class PreRoutingVerifierContext(ctx: NgPreRoutingContext, apk: ApiKey) extends VerificationContext {
  override def request: RequestHeader        = ctx.request
  override def descriptor: ServiceDescriptor = ctx.route.legacy
  override def apikey: Option[ApiKey]        = apk.some
  override def user: Option[PrivateAppsUser] = None
}

case class AccessValidatorContext(ctx: NgAccessContext) extends VerificationContext {
  override def request: RequestHeader        = ctx.request
  override def descriptor: ServiceDescriptor = ctx.route.legacy
  override def apikey: Option[ApiKey]        = ctx.apikey
  override def user: Option[PrivateAppsUser] = ctx.user
}

case class NgBiscuitConfig(
  legacy: BiscuitConfig = BiscuitConfig(
    publicKey = None,
    checks = Seq.empty,
    facts = Seq.empty,
    resources = Seq.empty,
    rules = Seq.empty,
    revocation_ids = Seq.empty,
    extractor = "header",
    extractorName = "Authorization",
    enforce = false,
  )
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "public_key" -> legacy.publicKey,
    "checks" -> legacy.checks,
    "facts" -> legacy.facts,
    "resources" -> legacy.resources,
    "rules" -> legacy.rules,
    "revocation_ids" -> legacy.revocation_ids,
    "extractor" -> Json.obj(
      "name" -> legacy.extractorName,
      "type" -> legacy.extractor,
    ),
    "enforce" -> legacy.enforce,
  )
}

object NgBiscuitConfig {
  val format = new Format[NgBiscuitConfig] {
    override def writes(o: NgBiscuitConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgBiscuitConfig] = Try {
      NgBiscuitConfig(
        legacy = BiscuitHelper.readConfigFromJson(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgBiscuitExtractor extends NgPreRouting {

  import collection.JavaConverters._

  override def name: String = "Apikey from Biscuit token extractor"
  override def description: Option[String] = "This plugin extract an from a Biscuit token where the biscuit has an #authority fact 'client_id' containing\napikey client_id and an #authority fact 'client_sign' that is the HMAC256 signature of the apikey client_id with the apikey client_secret".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgBiscuitConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)

  // TODO: check if it's a bug, first letter is missing in parsed rule (lient_id instead of client_id)
  // val ruleTuple = Parser.rule("client_id($id) <- client_id(#authority, $id) @ []").get()
  private val client_id_rule = com.clevercloud.biscuit.token.builder.Utils.rule(
    "client_id_res",
    Seq(com.clevercloud.biscuit.token.builder.Utils.`var`("id")).asJava,
    Seq(
      com.clevercloud.biscuit.token.builder.Utils.pred(
        "client_id",
        Seq(
          com.clevercloud.biscuit.token.builder.Utils.s("authority"),
          com.clevercloud.biscuit.token.builder.Utils.`var`("id")
        ).asJava
      )
    ).asJava
  )

  private val client_sign_rule = com.clevercloud.biscuit.token.builder.Utils.rule(
    "client_sign_res",
    Seq(com.clevercloud.biscuit.token.builder.Utils.`var`("sign")).asJava,
    Seq(
      com.clevercloud.biscuit.token.builder.Utils.pred(
        "client_sign",
        Seq(
          com.clevercloud.biscuit.token.builder.Utils.s("authority"),
          com.clevercloud.biscuit.token.builder.Utils.`var`("sign")
        ).asJava
      )
    ).asJava
  )

  def unauthorized(error: JsObject): Future[Either[NgPreRoutingError, Done]] = {
    NgPreRoutingErrorWithResult(Results.Unauthorized(error)).leftf
  }

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {

    val config = ctx.cachedConfig(internalName)(NgBiscuitConfig.format).getOrElse(NgBiscuitConfig())

    def verification(verifier: Authorizer): Future[Either[NgPreRoutingError, Done]] = {
      val client_id: Option[String]   = Try(verifier.query(client_id_rule)).toOption
        .map(_.asScala)
        .flatMap(_.headOption)
        .filter(_.name() == "client_id_res")
        .map(_.terms().asScala)
        .flatMap(_.headOption)
        .flatMap {
          case str: Str => str.getValue().some
          case _        => None
        }
      val client_sign: Option[String] = Try(verifier.query(client_sign_rule)).toOption
        .map(_.asScala)
        .flatMap(_.headOption)
        .filter(_.name() == "client_sign_res")
        .map(_.terms().asScala)
        .flatMap(_.headOption)
        .flatMap {
          case str: Str => str.getValue().some
          case _        => None
        }
      (client_id, client_sign) match {
        case (Some(client_id), Some(client_sign)) => {
          env.datastores.apiKeyDataStore.findById(client_id).flatMap {
            case Some(apikey) if apikey.isInactive() && config.legacy.enforce =>
              unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "bad_apikey"))
            case Some(apikey) if apikey.isInactive()                   => Done.rightf
            case Some(apikey)                                          => {
              val nextSignedOk = apikey.rotation.nextSecret
                .map(s => Signatures.hmacSha256Sign(client_id, s))
                .contains(client_sign)
              val signed       = Signatures.hmacSha256Sign(client_id, apikey.clientSecret)
              if (signed == client_sign || nextSignedOk) {
                BiscuitHelper.verify(verifier, config.legacy, PreRoutingVerifierContext(ctx, apikey)) match {
                  case Left(err) if config.legacy.enforce =>
                    unauthorized(
                      Json.obj("error" -> "unauthorized", "error_description" -> s"verification error: $err")
                    )
                  case Left(_)                     => Done.rightf
                  case Right(_)                    => {
                    // println(biscuit.print())
                    // println(verifier.print_world())
                    ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
                    Done.rightf
                  }
                }
              } else if (config.legacy.enforce) {
                unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "bad_apikey"))
              } else {
                Done.rightf
              }
            }
            case _                                                     => Done.rightf
          }
        }
        case _                                    => Done.rightf
      }
    }

    BiscuitHelper.extractToken(ctx.request, config.legacy) match {
      case Some(PubKeyBiscuitToken(token)) => {
        val pubkey = new PublicKey(biscuit.format.schema.Schema.PublicKey.Algorithm.Ed25519, config.legacy.publicKey.get)
        Try(Biscuit.from_b64url(token, pubkey)).toEither match {
          case Left(err) if config.legacy.enforce =>
            unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"deserialization error: $err"))
          case Left(_)                     => Done.rightf
          case Right(biscuit)              =>
            Try(biscuit.verify(pubkey)).toEither match {
              case Left(err) if config.legacy.enforce =>
                unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"verifier error: $err"))
              case Left(_)                     => Done.rightf
              case Right(biscuit)              => verification(biscuit.authorizer())
            }
        }
      }
      case _                               => Done.rightf
    }
  }
}

class NgBiscuitValidator extends NgAccessValidator {

  override def name: String = "Biscuit token validator"
  override def description: Option[String] = "This plugin validates a Biscuit token".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgBiscuitConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgBiscuitConfig.format).getOrElse(NgBiscuitConfig())
    BiscuitHelper.extractToken(ctx.request, config.legacy) match {
      case Some(PubKeyBiscuitToken(token)) => {
        val pubkey = new PublicKey(biscuit.format.schema.Schema.PublicKey.Algorithm.Ed25519, config.legacy.publicKey.get)
        Try(Biscuit.from_b64url(token, pubkey)).toEither match {
          case Left(_)        => forbidden(ctx)
          case Right(biscuit) =>
            Try(biscuit.verify(pubkey)).toEither match {
              case Left(_)         => forbidden(ctx)
              case Right(verifier) => {
                BiscuitHelper.verify(verifier.authorizer(), config.legacy, AccessValidatorContext(ctx)) match {
                  case Left(_)  => forbidden(ctx)
                  case Right(_) => NgAccess.NgAllowed.vfuture
                }
              }
            }
        }
      }
      case _ if config.legacy.enforce             => forbidden(ctx)
      case _ if !config.legacy.enforce            => NgAccess.NgAllowed.vfuture
    }
  }
}
  