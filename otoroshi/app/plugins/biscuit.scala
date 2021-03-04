package otoroshi.plugins.biscuit

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

import akka.http.scaladsl.util.FastFuture
import com.clevercloud.biscuit.crypto._
import com.clevercloud.biscuit.token.builder.Caveat
import com.clevercloud.biscuit.token.builder.Term.Str
import com.clevercloud.biscuit.token.builder.Utils.{fact, s, string}
import com.clevercloud.biscuit.token.builder.parser.Parser
import com.clevercloud.biscuit.token.{Biscuit, Verifier}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.script._
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{RequestHeader, Results}
import otoroshi.utils.http.RequestImplicits._

import scala.concurrent.{ExecutionContext, Future}

object vavr_implicits {
  implicit class BetterVavrEither[L, R](val either: io.vavr.control.Either[L, R]) extends AnyVal {
    def asScala: Either[L, R] = {
      if (either.isLeft) {
        Left[L, R](either.getLeft)
      } else {
        Right[L, R](either.get())
      }
    }
  }
}

case class BiscuitConfig(
  publicKey: Option[String],
  secret: Option[String],
  caveats: Seq[String],
  facts: Seq[String],
  resources: Seq[String],
  rules: Seq[String],
  revocation_ids: Seq[Long],
  extractor: String,
  extractorName: String,
  enforce: Boolean,
  sealedToken: Boolean,
)

object BiscuitConfig {
  val example: JsObject = Json.obj(
    "publicKey" -> "xxxxxx",
    "secret" -> "secret",
    "caveats" -> Json.arr(),
    "facts" -> Json.arr(),
    "resources" -> Json.arr(),
    "rules" -> Json.arr(),
    "revocation_ids" -> Json.arr(),
    "enforce" -> false,
    "sealed" -> false,
    "extractor" -> Json.obj(
      "type" -> "header",
      "name" -> "Authorization"
    )
  )
}

trait VerificationContext {
  def request: RequestHeader
  def descriptor: ServiceDescriptor
  def apikey: Option[ApiKey]
  def user: Option[PrivateAppsUser]
}

case class PreRoutingVerifierContext(ctx: PreRoutingContext, apk: ApiKey) extends VerificationContext {
  override def request: RequestHeader = ctx.request
  override def descriptor: ServiceDescriptor = ctx.descriptor
  override def apikey: Option[ApiKey] = apk.some
  override def user: Option[PrivateAppsUser] = None
}

case class AccessValidatorContext(ctx: AccessContext) extends VerificationContext {
  override def request: RequestHeader = ctx.request
  override def descriptor: ServiceDescriptor = ctx.descriptor
  override def apikey: Option[ApiKey] = ctx.apikey
  override def user: Option[PrivateAppsUser] = ctx.user
}

sealed trait BiscuitToken {
  def token: String
}
case class PubKeyBiscuitToken(token: String) extends BiscuitToken
case class SealedBiscuitToken(token: String) extends BiscuitToken

object BiscuitHelper {

  import vavr_implicits._

  import collection.JavaConverters._

  def readConfig(name: String, ctx: ContextWithConfig): BiscuitConfig = {
    val rawConfig = ctx.configFor(name)
    BiscuitConfig(
      publicKey = (rawConfig \ "publicKey").asOpt[String],
      secret = (rawConfig \ "secret").asOpt[String],
      caveats = (rawConfig \ "caveats").asOpt[Seq[String]].getOrElse(Seq.empty),
      facts = (rawConfig \ "facts").asOpt[Seq[String]].getOrElse(Seq.empty),
      resources = (rawConfig \ "resources").asOpt[Seq[String]].getOrElse(Seq.empty),
      rules = (rawConfig \ "rules").asOpt[Seq[String]].getOrElse(Seq.empty),
      revocation_ids = (rawConfig \ "revocation_ids").asOpt[Seq[Long]].getOrElse(Seq.empty),
      extractor = (rawConfig \ "extractor" \ "type").asOpt[String].getOrElse("header"),
      extractorName = (rawConfig \ "extractor" \ "name").asOpt[String].getOrElse("Authorization"),
      enforce = (rawConfig \ "enforce").asOpt[Boolean].getOrElse(false),
      sealedToken = (rawConfig \ "sealed").asOpt[Boolean].getOrElse(false),
    )
  }

  def readOrWrite(method: String): String = method match {
    case "DELETE" => "write"
    case "GET" => "read"
    case "HEAD" => "read"
    case "OPTIONS" => "read"
    case "PATCH" => "write"
    case "POST" => "write"
    case "PUT" => "write"
    case _ => "none"
  }

  def extractToken(req: RequestHeader, config: BiscuitConfig): Option[BiscuitToken] = {
    (config.extractor match {
      case "header" => req.headers.get(config.extractorName)
      case "query" => req.getQueryString(config.extractorName)
      case "cookie" => req.cookies.get(config.extractorName).map(_.value)
      case _ => None
    }).map { token =>
      val tokenValue = token
        .replace("Bearer ", "")
        .replace("Biscuit ", "")
        .replace("biscuit: ", "")
        .replace("sealed-biscuit: ", "")
        .trim
      if (token.contains("sealed-biscuit:") || config.sealedToken) {
        SealedBiscuitToken(tokenValue)
      } else {
        PubKeyBiscuitToken(tokenValue)
      }
    }
  }

  def verify(verifier: Verifier, config: BiscuitConfig, ctx: VerificationContext)(implicit env: Env): Either[com.clevercloud.biscuit.error.Error, Unit] = {
    verifier.set_time()
    verifier.add_operation(readOrWrite(ctx.request.method))
    verifier.add_fact(fact("resource", Seq(s("ambient"), string(ctx.request.method.toLowerCase()), string(ctx.request.theDomain), string(ctx.request.thePath)).asJava))
    verifier.add_fact(fact("req_path", Seq(s("ambient"), string(ctx.request.thePath)).asJava))
    verifier.add_fact(fact("req_domain", Seq(s("ambient"), string(ctx.request.theDomain)).asJava))
    verifier.add_fact(fact("req_method", Seq(s("ambient"), string(ctx.request.method.toLowerCase())).asJava))
    verifier.add_fact(fact("descriptor_id", Seq(s("ambient"), string(ctx.descriptor.id)).asJava))
    ctx.apikey.foreach { apikey =>
      apikey.tags.foreach(tag => verifier.add_fact(fact("apikey_tag", Seq(s("ambient"), string(tag)).asJava)))
      apikey.metadata.foreach(tuple => verifier.add_fact(fact("apikey_meta", Seq(s("ambient"), string(tuple._1), string(tuple._2)).asJava)))
    }
    ctx.user.foreach { user =>
      user.metadata.foreach(tuple => verifier.add_fact(fact("user_meta", Seq(s("ambient"), string(tuple._1), string(tuple._2)).asJava)))
    }
    config.resources.foreach(r => verifier.add_resource(r))
    // TODO: change when implemented
    // config.caveats.map(Parser.caveat).filter(_.isRight).map(_.get()._2).foreach(r => verifier.add_caveat(r))
    config.caveats.map(Parser.rule).filter(_.isRight).map(_.get()._2).map(r => new Caveat(r)).foreach(r => verifier.add_caveat(r))
    config.facts.map(Parser.fact).filter(_.isRight).map(_.get()._2).foreach(r => verifier.add_fact(r))
    config.rules.map(Parser.rule).filter(_.isRight).map(_.get()._2).foreach(r => verifier.add_rule(r))
    if (config.revocation_ids.nonEmpty) {
      verifier.revocation_check(config.revocation_ids.toList.map(_.asInstanceOf[java.lang.Long]).asJava)
    }
    // TODO: here, add ambient stuff, rules from config, query some stuff, etc ..
    verifier.verify().asScala match {
      case Left(err) => Left[com.clevercloud.biscuit.error.Error, Unit](err)
      case Right(_) => Right(())
    }
  }
}

class BiscuitExtractor extends PreRouting {

  import vavr_implicits._

  import collection.JavaConverters._

  override def name: String = "Apikey from Biscuit token extractor"

  override def defaultConfig: Option[JsObject] = BiscuitConfig.example.some

  override def description: Option[String] = {
    s"""This plugin extract an from a Biscuit token where the biscuit has an #authority fact 'client_id' containing
       |apikey client_id and an #authority fact 'client_sign' that is the HMAC256 signature of the apikey client_id with the apikey client_secret
       |
       |This plugin can accept the following configuration
       |
       |```json
       |${defaultConfig.get.prettify}
       |```
    """.stripMargin.some
  }

  // TODO: check if it's a bug, first letter is missing in parsed rule (lient_id instead of client_id)
  // val ruleTuple = Parser.rule("client_id($id) <- client_id(#authority, $id) @ []").get()
  val client_id_rule = com.clevercloud.biscuit.token.builder.Utils.rule(
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

  val client_sign_rule = com.clevercloud.biscuit.token.builder.Utils.rule(
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

  def testing(): Unit = {

    import com.clevercloud.biscuit.token.builder.Block
    import com.clevercloud.biscuit.token.builder.Utils._

    val client_id = "tdrw4ixcssyvljrq"
    val client_secret = "pdpzme7xpg58y1za0yqyihycschnq74iu7437qqfjor0h3jeo505n6w4ofg1pa17"
    val signed = Signatures.hmacSha256Sign(client_id, client_secret)
    val rng = new SecureRandom()
    val root = new KeyPair(rng)
    val symbols = Biscuit.default_symbol_table()
    val authority_builder = new Block(0, symbols)
    authority_builder.add_fact(fact("client_id", Seq(s("authority"), string(client_id)).asJava))
    authority_builder.add_fact(fact("client_sign", Seq(s("authority"), string(signed)).asJava))
    val biscuit = Biscuit.make(rng, root, Biscuit.default_symbol_table(), authority_builder.build()).get()
    println(s"public_key: ${root.public_key().toHex}")
    println(s"curl http://biscuit.oto.tools:9999 -H 'Authorization: Bearer ${biscuit.serialize_b64().get()}'")
  }

  def unauthorized(error: JsObject): Future[Unit] = {
    FastFuture.failed(PreRoutingErrorWithResult(Results.Unauthorized(error)))
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = BiscuitHelper.readConfig("BiscuitExtractor", ctx)

    def verification(verifier: Verifier): Future[Unit] = {
      val client_id: Option[String] = verifier.query(client_id_rule).asScala.toOption.map(_.asScala).flatMap(_.headOption).filter(_.name() == "client_id_res").map(_.ids().asScala).flatMap(_.headOption).flatMap {
        case str: Str => str.value().some
        case _ => None
      }
      val client_sign: Option[String] = verifier.query(client_sign_rule).asScala.toOption.map(_.asScala).flatMap(_.headOption).filter(_.name() == "client_sign_res").map(_.ids().asScala).flatMap(_.headOption).flatMap {
        case str: Str => str.value().some
        case _ => None
      }
      (client_id, client_sign) match {
        case (Some(client_id), Some(client_sign)) => {
          env.datastores.apiKeyDataStore.findById(client_id).flatMap {
            case Some(apikey) if apikey.isInactive() && config.enforce => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "bad_apikey"))
            case Some(apikey) if apikey.isInactive() => ().future
            case Some(apikey) => {
              val nextSignedOk = apikey.rotation.nextSecret
                .map(s => Signatures.hmacSha256Sign(client_id, s))
                .contains(client_sign)
              val signed = Signatures.hmacSha256Sign(client_id, apikey.clientSecret)
              if (signed == client_sign || nextSignedOk) {
                BiscuitHelper.verify(verifier, config, PreRoutingVerifierContext(ctx, apikey)) match {
                  case Left(err) if config.enforce => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"verification error: $err"))
                  case Left(_) => ().future
                  case Right(_) => {
                    // println(biscuit.print())
                    // println(verifier.print_world())
                    ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
                    ().future
                  }
                }
              } else if (config.enforce) {
                unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "bad_apikey"))
              } else {
                ().future
              }
            }
            case _ => ().future
          }
        }
        case _ => ().future
      }
    }

    BiscuitHelper.extractToken(ctx.request, config) match {
      case Some(SealedBiscuitToken(token)) => {
        Biscuit.from_sealed(Base64.getUrlDecoder.decode(token), config.secret.get.getBytes(StandardCharsets.UTF_8)).asScala match {
          case Left(err) if config.enforce => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"deserialization error: $err"))
          case Left(_) => ().future
          case Right(biscuit) => biscuit.verify_sealed().asScala match {
            case Left(err) if config.enforce => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"verifier error: $err"))
            case Left(_) => ().future
            case Right(verifier) => verification(verifier)
          }
        }
      }
      case Some(PubKeyBiscuitToken(token)) => {
        Biscuit.from_b64(token).asScala match {
          case Left(err) if config.enforce => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"deserialization error: $err"))
          case Left(_) => ().future
          case Right(biscuit) => biscuit.verify(new PublicKey(config.publicKey.get)).asScala match {
            case Left(err) if config.enforce => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> s"verifier error: $err"))
            case Left(_) => ().future
            case Right(verifier) => verification(verifier)
          }
        }
      }
      case _ => ().future
    }
  }
}

class BiscuitValidator extends AccessValidator {

  import vavr_implicits._

  override def name: String = "Biscuit token validator"

  override def defaultConfig: Option[JsObject] = BiscuitConfig.example.some

  override def description: Option[String] = {
    s"""This plugin validates a Biscuit token.
       |
       |This plugin can accept the following configuration
       |
       |```json
       |${defaultConfig.get.prettify}
       |```
    """.stripMargin.some
  }

  override def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val config = BiscuitHelper.readConfig("BiscuitValidator", ctx)
    BiscuitHelper.extractToken(ctx.request, config) match {
      case Some(SealedBiscuitToken(token)) => {
        Biscuit.from_sealed(Base64.getUrlDecoder.decode(token), config.secret.get.getBytes(StandardCharsets.UTF_8)).asScala match {
          case Left(_) => false.future
          case Right(biscuit) => biscuit.verify_sealed().asScala match {
            case Left(_) => false.future
            case Right(verifier) => {
              BiscuitHelper.verify(verifier, config, AccessValidatorContext(ctx)) match {
                case Left(_) => false.future
                case Right(_) => true.future
              }
            }
          }
        }
      }
      case Some(PubKeyBiscuitToken(token)) => {
        Biscuit.from_b64(token).asScala match {
          case Left(_) => false.future
          case Right(biscuit) => biscuit.verify(new PublicKey(config.publicKey.get)).asScala match {
            case Left(_) => false.future
            case Right(verifier) => {
              BiscuitHelper.verify(verifier, config, AccessValidatorContext(ctx)) match {
                case Left(_) => false.future
                case Right(_) => true.future
              }
            }
          }
        }
      }
      case _ if config.enforce => false.future
      case _ if !config.enforce => true.future
    }
  }
}