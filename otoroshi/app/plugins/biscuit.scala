package otoroshi.plugins.biscuit

import java.security.SecureRandom

import akka.http.scaladsl.util.FastFuture
import com.clevercloud.biscuit.crypto._
import com.clevercloud.biscuit.token.Biscuit
import env.Env
import models.{ApiKey, ServiceDescriptorIdentifier}
import otoroshi.script.{AccessContext, PreRouting, PreRoutingContext, PreRoutingErrorWithResult}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results
import otoroshi.utils.syntax.implicits._

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

    import com.clevercloud.biscuit.token.builder.Utils._
    import collection.JavaConverters._
    import com.clevercloud.biscuit.token.builder.Block

    val rng = new SecureRandom()
    val root = new KeyPair(rng)
    val symbols = Biscuit.default_symbol_table()
    val authority_builder = new Block(0, symbols)
    authority_builder.add_fact(fact("right", Seq(s("authority"), s("file1"), s("read")).asJava))
    authority_builder.add_fact(fact("client_id", Seq(s("authority"), string("tdrw4ixcssyvljrq")).asJava))
    val biscuit = Biscuit.make(rng, root, Biscuit.default_symbol_table(), authority_builder.build()).get()
    // tdrw4ixcssyvljrq:pdpzme7xpg58y1za0yqyihycschnq74iu7437qqfjor0h3jeo505n6w4ofg1pa17
    println(s"public_key: ${root.public_key().toHex}")
    println(s"biscuit: ${biscuit.serialize_b64().get()}")
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = readConfig(ctx)
    // testing()
    ctx.request.headers.get("Authorization") match {
      case Some(value) if value.startsWith("Bearer ") => {
        val token = value.replace("Bearer ", "")
        Biscuit.from_b64(token).asScala match {
          case Left(err) => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> err.toString))
          case Right(biscuit) => biscuit.verify(new PublicKey(config.publicKey)).asScala match {
            case Left(err) => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> err.toString))
            case Right(verifier) => verifier.verify().asScala match {
              case Left(err) => unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> err.toString))
              case Right(_) => {
                println("passed !!!!")
                // TODO: here, add ambient stuff, rules from config, query some stuff, etc ...
                println(biscuit.print())
                println(verifier.print_world())
                ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> ApiKey(
                  clientName = "biscuit_apikey",
                  authorizedEntities = Seq(ServiceDescriptorIdentifier(ctx.descriptor.id))
                ))
                ().future
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
