package otoroshi.plugins.biscuit

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.algorithms.Algorithm
import com.clevercloud.biscuit.crypto._
import com.clevercloud.biscuit.token.Biscuit
import com.clevercloud.biscuit.token.builder.Term.Str
import env.Env
import otoroshi.script.{PreRouting, PreRoutingContext, PreRoutingErrorWithResult}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results
import utils.RequestImplicits._

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

case class BiscuitExtractorConfig(publicKey: String)

class BiscuitExtractor extends PreRouting {

  import vavr_implicits._

  import collection.JavaConverters._

  // val ruleTuple = Parser.rule("client_id($id) <- client_id(#authority, $id) @ []").get()
  val client_id_rule = com.clevercloud.biscuit.token.builder.Utils.rule(
    "client_id",
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
    "client_sign",
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

  def readConfig(ctx: PreRoutingContext): BiscuitExtractorConfig = {
    val rawConfig = ctx.configFor("BiscuitExtractor")
    BiscuitExtractorConfig(
      publicKey = (rawConfig \ "publicKey").as[String]
    )
  }

  def unauthorized(error: JsObject): Future[Unit] = {
    FastFuture.failed(PreRoutingErrorWithResult(Results.Unauthorized(error)))
  }

  def testing(): Unit = {

    import com.clevercloud.biscuit.token.builder.Block
    import com.clevercloud.biscuit.token.builder.Utils._

    val client_id = "tdrw4ixcssyvljrq"
    val client_secret = "pdpzme7xpg58y1za0yqyihycschnq74iu7437qqfjor0h3jeo505n6w4ofg1pa17"
    val algo = Algorithm.HMAC256(client_secret)
    val signed = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(algo.sign(client_id.getBytes(StandardCharsets.UTF_8)))
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

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    import com.clevercloud.biscuit.token.builder.Utils._

    val config = readConfig(ctx)
    // testing()
    ctx.request.headers.get("Authorization") match {
      case Some(value) if value.startsWith("Bearer ") => {
        val token = value.replace("Bearer ", "")
        Biscuit.from_b64(token).asScala match {
          case Left(err) => unauthorized(Json.obj("error" -> "unauthorized1", "error_description" -> err.toString))
          case Right(biscuit) => biscuit.verify(new PublicKey(config.publicKey)).asScala match {
            case Left(err) => unauthorized(Json.obj("error" -> "unauthorized2", "error_description" -> err.toString))
            case Right(verifier) => {
              // TODO: here, add ambient stuff, rules from config, query some stuff, etc ..
              verifier.add_operation(readOrWrite(ctx.request.method))
              verifier.add_fact(fact("resource", Seq(s("ambient"), string(ctx.request.method.toLowerCase()), string(ctx.request.theDomain), string(ctx.request.thePath)).asJava))
              verifier.add_fact(fact("req_path", Seq(s("ambient"), string(ctx.request.thePath)).asJava))
              verifier.add_fact(fact("req_domain", Seq(s("ambient"), string(ctx.request.theDomain)).asJava))
              verifier.add_fact(fact("req_method", Seq(s("ambient"), string(ctx.request.method.toLowerCase())).asJava))
              verifier.verify().asScala match {
                case Left(err) => unauthorized(Json.obj("error" -> "unauthorized3", "error_description" -> err.toString))
                case Right(_) => {
                  val client_id: Option[String] = verifier.query(client_id_rule).asScala.toOption.map(_.asScala).flatMap(_.headOption).map(_.ids().asScala).flatMap(_.headOption).flatMap {
                    case str: Str => str.value().some
                    case _ => None
                  }
                  val client_sign: Option[String] = verifier.query(client_sign_rule).asScala.toOption.map(_.asScala).flatMap(_.headOption).map(_.ids().asScala).flatMap(_.headOption).flatMap {
                    case str: Str => str.value().some
                    case _ => None
                  }
                  //println(biscuit.print())
                  //println(verifier.print_world())
                  (client_id, client_sign) match {
                    case (Some(client_id), Some(client_sign)) => {
                      env.datastores.apiKeyDataStore.findById(client_id).map {
                        case Some(apikey) => {
                          val algo = Algorithm.HMAC256(apikey.clientSecret)
                          val signed = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(algo.sign(client_id.getBytes(StandardCharsets.UTF_8)))
                          if (signed == client_sign) {
                            ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
                          }
                          ()
                        }
                        case _ => ()
                      }
                    }
                    case _ => ().future
                  }
                }
              }
            }
          }
        }
      }
      case _ => ()
    }
    funit
  }
}
