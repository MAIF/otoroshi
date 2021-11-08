package otoroshi.plugins.oauth1

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.google.common.base.Charsets
import otoroshi.auth.Oauth1AuthModule.encodeURI
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import play.utils.UriEncoding

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class OAuth1CallerPlugin extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-oauth1-caller-plugin")

  override def name: String = "OAuth1 caller"

  object Keys {
    val consumerKey     = "oauth_consumer_key"
    val consumerSecret  = "oauth_consumer_secret"
    val signatureMethod = "oauth_signature_method"
    val signature       = "oauth_signature"
    val timestamp       = "oauth_timestamp"
    val nonce           = "oauth_nonce"
    val token           = "oauth_token"
    val tokenSecret     = "oauth_token_secret"
  }

  private val Algo = Map(
    "HMAC-SHA1"   -> "HmacSHA1",
    "HMAC-SHA256" -> "HmacSHA256",
    "HMAC-SHA384" -> "HmacSHA384",
    "HMAC-SHA512" -> "HmacSHA512",
    "TEXTPLAIN"   -> "TEXTPLAIN"
  )

  override def configFlow: Seq[String] =
    Seq(
      "algo",
      Keys.consumerKey,
      Keys.consumerSecret,
      Keys.token,
      Keys.tokenSecret
    )

  override def configSchema =
    Some(
      Json.obj(
        Keys.consumerKey    -> Json.obj(
          "type"  -> "string",
          "props" -> Json.obj(
            "label" -> "Consumer key",
            "help"  -> "A value used by the Consumer to identify itself to the Service Provider."
          )
        ),
        Keys.consumerSecret -> Json.obj(
          "type"  -> "string",
          "props" -> Json.obj(
            "label" -> "Consumer secret",
            "help"  -> "A secret used by the Consumer to establish ownership of the Consumer Key."
          )
        ),
        Keys.token          -> Json.obj(
          "type"  -> "string",
          "props" -> Json.obj(
            "label" -> "OAuth token",
            "help"  -> "A value used by the Consumer to gain access to the Protected Resources on behalf of the User, instead of using the User's Service Provider credentials."
          )
        ),
        Keys.tokenSecret    -> Json.obj(
          "type"  -> "string",
          "props" -> Json.obj(
            "label" -> "OAuth token",
            "help"  -> "A secret used by the Consumer to establish ownership of a given Token."
          )
        ),
        "algo"              -> Json.obj(
          "type"  -> "select",
          "props" -> Json.obj(
            "label"          -> "Algo to sign requests",
            "defaultValue"   -> "HmacSHA512",
            "possibleValues" -> Seq(
              Json.obj("value" -> "HMAC-SHA1", "label"   -> "HMAC-SHA1"),
              Json.obj("value" -> "HMAC-SHA256", "label" -> "HMAC-SHA256"),
              Json.obj("value" -> "HMAC-SHA384", "label" -> "HMAC-SHA384"),
              Json.obj("value" -> "HMAC-SHA512", "label" -> "HMAC-SHA512"),
              Json.obj("value" -> "TEXTPLAIN", "label"   -> "TEXTPLAIN (not recommended)")
            )
          )
        )
      )
    )

  override def defaultConfig: Option[JsObject] = Some(
    Json.obj(
      "OAuth1Caller" -> Json.obj(
        "algo" -> "HmacSHA512"
      )
    )
  )

  override def description: Option[String] = {
    Some(
      s"""This plugin can be used to call api that are authenticated using OAuth1.
         | Consumer key, secret, and OAuth token et OAuth token secret can be pass through the metadata of an api key
         | or via the configuration of this plugin.
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def configRoot: Option[String] = "OAuth1Caller".some

  private def signRequest(
      verb: String,
      path: String,
      params: Seq[(String, String)],
      consumerSecret: String,
      oauthTokenSecret: String,
      algo: String
  ): String = {
    val sortedEncodedParams = encodeURI(
      params.sortBy(_._1).map(t => (t._1, encodeURI(t._2)).productIterator.mkString("=")).mkString("&")
    )
    val encodedURL          = encodeURI(path)

    val base      = s"$verb&$encodedURL&$sortedEncodedParams"
    val key       = s"${encodeURI(consumerSecret)}&${Some(oauthTokenSecret).map(encodeURI).getOrElse("")}"
    val signature = Base64.getEncoder.encodeToString(Signatures.hmac(Algo(algo), base, key))

    encodeURI(signature)
  }

  private def encode(param: String): String = UriEncoding.encodePathSegment(param, Charsets.UTF_8)

  def prepareParameters(params: Seq[(String, String)]): String = params
    .map { case (k, v) => (encode(k), encode(v)) }
    .sortBy(identity)
    .map { case (k, v) => s"$k=$v" }
    .mkString("&")

  def getOauthParams(
      consumerKey: String,
      consumerSecret: String,
      tokenSecret: Option[String] = None,
      signatureMethod: String
  ): Map[String, String] =
    Map(
      Keys.consumerKey     -> consumerKey,
      Keys.signatureMethod -> signatureMethod,
      Keys.signature       -> s"${encodeURI(consumerSecret + "&" + tokenSecret.getOrElse(""))}",
      Keys.timestamp       -> s"${Math.floor(System.currentTimeMillis() / 1000).toInt}",
      Keys.nonce           -> s"${Random.nextInt(1000000000)}"
    )

  private def authorization(
      httpMethod: String,
      url: String,
      oauthParams: Map[String, String],
      queryParams: Map[String, String],
      consumerKey: String,
      consumerSecret: String,
      oauthToken: String,
      oauthTokenSecret: String
  ): Seq[(String, String)] = {
    val params: Seq[(String, String)] =
      (queryParams ++ oauthParams + (Keys.token -> oauthToken))
        .map { case (k, v) => (k, v) }
        .toSeq
        .filter { case (k, _) => k != "oauth_signature" }

    val signature =
      if (oauthParams(Keys.signatureMethod) != "TEXTPLAIN")
        signRequest(httpMethod, url, params, consumerSecret, oauthTokenSecret, oauthParams(Keys.signatureMethod))
      else
        oauthParams(Keys.signature)

    Seq(
      Keys.consumerKey     -> consumerKey,
      Keys.token           -> oauthToken,
      Keys.signatureMethod -> oauthParams(Keys.signatureMethod),
      Keys.timestamp       -> oauthParams(Keys.timestamp),
      Keys.nonce           -> oauthParams(Keys.nonce),
      Keys.signature       -> signature
    )
  }

  private def getOrElse(config: JsValue, metadata: Map[String, String], key: String): Either[Result, String] = {
    metadata.get(key) match {
      case Some(value) => Right(value)
      case None        =>
        (config \ key).asOpt[String] match {
          case None        => Left(BadRequest(Json.obj("error" -> "Bad parameters")))
          case Some(value) => Right(value)
        }
    }
  }

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = ctx.configFor("OAuth1Caller")

    val metadata         = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.metadata).getOrElse(Map.empty)
    val consumerKey      = getOrElse(config, metadata, Keys.consumerKey)
    val consumerSecret   = getOrElse(config, metadata, Keys.consumerSecret)
    val oauthToken       = getOrElse(config, metadata, Keys.token)
    val oauthTokenSecret = getOrElse(config, metadata, Keys.tokenSecret)

    (consumerKey, consumerSecret, oauthToken, oauthTokenSecret) match {
      case (Right(key), Right(secret), Right(token), Right(tokenSecret)) =>
        val signature_method = (config \ "algo").asOpt[String].getOrElse("HmacSHA512")

        logger.debug(s"Algo used : $signature_method")

        val inParams = getOauthParams(key, secret, Some(tokenSecret), signature_method)

        val params: String = authorization(
          ctx.otoroshiRequest.method,
          ctx.otoroshiRequest.url,
          inParams,
          ctx.otoroshiRequest.queryString.map(_.asInstanceOf[Map[String, String]]).getOrElse(Map.empty[String, String]),
          key,
          secret,
          token,
          tokenSecret
        )
          .map { case (k, v) => s"""$k="$v"""" }
          .mkString(",")

        Right(
          ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers + ("Authorization" -> s"OAuth $params"))
        ).future

      case _ =>
        logger.debug(s"Consumer key : ${consumerKey.getOrElse("missing")}")
        logger.debug(s"Consumer secret : ${consumerSecret.getOrElse("missing")}")
        logger.debug(s"OAuth token : ${oauthToken.getOrElse("missing")}")
        logger.debug(s"OAuth token secret : ${oauthTokenSecret.getOrElse("missing")}")
        FastFuture.successful(Left(BadRequest(Json.obj("error" -> "Bad parameters"))))
    }
  }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    ctx.otoroshiResponse.right.future
  }
}
