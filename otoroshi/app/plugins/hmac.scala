package otoroshi.plugins.hmac

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

object HMACUtils {
  val Algo = Map(
    "HMAC-SHA1" -> "HmacSHA1",
    "HMAC-SHA256" -> "HmacSHA256",
    "HMAC-SHA384" -> "HmacSHA384",
    "HMAC-SHA512" -> "HmacSHA512"
  )
}

class HMACCallerPlugin extends RequestTransformer {

  private val logger  = Logger("otoroshi-plugins-hmac-caller-plugin")

  override def name: String = "HMAC caller plugin"

  override def configFlow: Seq[String] = Seq(
    "secret",
    "algo",
    "authorizationHeader"
  )

  override def configSchema =
    Some(
      Json.obj(
        "secret"     -> Json.obj(
          "type" -> "string",
          "props" -> Json.obj(
            "label" -> "Secret to sign and verify signed content of headers. By default, the secret of the api key is used."
          )
        ),
        "algo"     -> Json.obj(
          "type" -> "select",
          "props" -> Json.obj(
            "label" -> "Algo to sign requests",
            "defaultValue" -> "HmacSHA512",
            "possibleValues" -> Seq(
              Json.obj("value" -> "HMAC-SHA1", "label" -> "HMAC-SHA1"),
              Json.obj("value" -> "HMAC-SHA256", "label" -> "HMAC-SHA256"),
              Json.obj("value" -> "HMAC-SHA384", "label" -> "HMAC-SHA384"),
              Json.obj("value" -> "HMAC-SHA512", "label" -> "HMAC-SHA512")
            )
          )
        ),
        "authorizationHeader" -> Json.obj(
          "type" -> "select",
          "props" -> Json.obj(
            "label" -> "Header used to send HMAC signature",
            "defaultValue" -> "Authorization",
            "possibleValues" -> Seq(
              Json.obj("value" -> "Authorization", "label" -> "Authorization"),
              Json.obj("value" -> "Proxy-Authorization", "label" -> "Proxy-Authorization")
            )
          )
        )
      )
    )

  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to call a "protected" api by an HMAC signature. It will adds a signature with the secret configured on the plugin.
         | The signature string will always the content of the header list listed in the plugin configuration.
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def defaultConfig: Option[JsObject] = Some(
    Json.obj(
      "HMACCallerPlugin" -> Json.obj(
      "secret" -> "my-defaut-secret",
      "algo" -> "HMAC-SHA512"
      )
    )
  )

  override def configRoot: Option[String] = "HMACCallerPlugin".some

  override def transformRequestWithCtx(
                                        context: TransformerRequestContext
                                      )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = context.configFor("HMACCallerPlugin")
    (config \ "secret").asOpt[String] match {
      case None =>
        logger.debug("No api key found and no secret found in configuration of the plugin")
        FastFuture.successful(Left(BadRequest(Json.obj("error" -> "Bad parameters"))))
      case Some(secret) =>
        val algorithm = (config \ "algorithm").asOpt[String].getOrElse("HMAC-SHA512")

        val authorizationHeader = (config \ "authorizationHeader").asOpt[String].getOrElse("Authorization")

        val signingString = System.currentTimeMillis().toString
        val signature = Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo(algorithm), signingString, secret))

        logger.debug(s"Secret used : $secret")
        logger.debug(s"Signature send : $signature")
        logger.debug(s"Algorithm used : $algorithm")
        logger.debug(s"Date generated : $signingString")
        logger.debug(s"Send Authorization header : ${s"$authorizationHeader"-> s"""hmac algorithm="${algorithm.toLowerCase}", headers="Date", signature="$signature""""}")

        context.otoroshiRequest
          .copy(headers = context.otoroshiRequest.headers ++ Map(
            "Date" -> signingString,
            authorizationHeader -> s"""hmac algorithm="${algorithm.toLowerCase}", headers="Date", signature="$signature""""
          ))
          .right.future
    }
  }
}

class HMACValidator extends AccessValidator {

  private val logger  = Logger("otoroshi-plugins-hmac-access-validator-plugin")

  override def name: String = "HMAC access validator"

  override def configFlow: Seq[String] = Seq("secret")

  override def configSchema =
    Some(
      Json.obj(
        "secret"     -> Json.obj(
          "type" -> "string",
          "props" -> Json.obj(
            "label" -> "[Optional] Secret to sign and verify signed content of headers"
          )
        )
      )
    )

  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to check if a HMAC signature is present and valid in Authorization header.
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def defaultConfig: Option[JsObject] = Some(Json.obj(
    "HMACAccessValidator" -> Json.obj(
    "secret" -> ""
    )
  ))

  override def configRoot: Option[String] = "HMACAccessValidator".some

  private def checkHMACSignature(authorization: String, context: AccessContext, secret: String) = {
    val params = authorization
      .replace("hmac ","")
      .replace("\"", "")
      .trim()
      .split(",")
      .toSeq
      .map(_.split("=", 2))
      .map(r => r(0).trim -> r(1).trim)
      .toMap

    val algorithm = params.getOrElse("algorithm", "HMAC-SHA256")
    val signature = params("signature")
    val headers: Seq[String] = params.get("headers").map(_.split(" ").toSeq).getOrElse(Seq.empty[String])
    val signingValues = context.request.headers.headers.filter(p => headers.contains(p._1)).map(_._2)
    val signingString = signingValues.mkString(" ")

    logger.debug(s"Secret used : $secret")
    logger.debug(s"Signature generated : ${Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo(algorithm.toUpperCase), signingString, secret))}")
    logger.debug(s"Signature received : $signature")
    logger.debug(s"Algorithm used : $algorithm")

    if(signingValues.size != headers.size)
      FastFuture.successful(false)
    else if(Base64.getEncoder.encodeToString(Signatures.hmac(HMACUtils.Algo(algorithm.toUpperCase), signingString, secret)) == signature)
      FastFuture.successful(true)
    else
      FastFuture.successful(false)
  }

  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] =
    ((context.configFor("HMACAccessValidator") \ "secret").asOpt[String] match {
      case Some(value) if value.nonEmpty => Some(value)
      case _ => context.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.clientSecret)
    }) match {
      case None =>
        logger.debug("No api key found and no secret found in configuration of the plugin")
        FastFuture.successful(false)
      case Some(secret) =>
        (context.request.headers.get("Authorization"), context.request.headers.get("Proxy-Authorization")) match {
          case (Some(authorization), None) => checkHMACSignature(authorization, context, secret)
          case (None, Some(authorization)) => checkHMACSignature(authorization, context, secret)
          case (_, _) =>
            logger.debug("Missing authorization header")
            FastFuture.successful(false)
        }
    }

  override def documentation: Option[String] = Some(
    s"""
     | The HMAC signature needs to be set on the `Authorization` or `Proxy-Authorization` header.
     | The format of this header should be : `hmac algorithm="<ALGORITHM>", headers="<HEADER>", signature="<SIGNATURE>"`
     | As example, a simple nodeJS call with the expected header
     | ```js
     | const crypto = require('crypto');
     | const fetch = require('node-fetch');
     |
     | const date = new Date()
     | const secret = "my-secret" // equal to the api key secret by default
     |
     | const algo = "sha512"
     | const signature = crypto.createHmac(algo, secret)
     |    .update(date.getTime().toString())
     |    .digest('base64');
     |
     | fetch('http://myservice.oto.tools:9999/api/test', {
     |    headers: {
     |        "Otoroshi-Client-Id": "my-id",
     |        "Otoroshi-Client-Secret": "my-secret",
     |        "Date": date.getTime().toString(),
     |        "Authorization": `hmac algorithm="hmac-$${algo}", headers="Date", signature="$${signature}"`,
     |        "Accept": "application/json"
     |    }
     | })
     |    .then(r => r.json())
     |    .then(console.log)
     | ```
     | In this example, we have an Otoroshi service deployed on http://myservice.oto.tools:9999/api/test, protected by api keys.
     | The secret used is the secret of the api key (by default, but you can change it and define a secret on the plugin configuration).
     | We send the base64 encoded date of the day, signed by the secret, in the Authorization header. We specify the headers signed and the type of algorithm used.
     | You can sign more than one header but you have to list them in the headers fields (each one separate by a space, example : headers="Date KeyId").
     | The algorithm used can be HMAC-SHA1, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512.
     |""".stripMargin
  )
}
