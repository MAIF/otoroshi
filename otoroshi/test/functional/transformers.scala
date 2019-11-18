// package otoroshi.plugins.companion

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import env.Env
import models.{ApiKey, InputMode, ServiceDescriptor, ServiceGroup}
import org.apache.commons.codec.binary.Base64
import org.apache.commons.lang.StringUtils
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import play.api.libs.json._
import play.api.mvc.{Result, Results}
import ssl.FakeKeyStore.KeystoreSettings
import ssl.{Cert, FakeKeyStore}
import utils.RegexPool
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object OtoroshiCompanion {
  val counter = new AtomicLong(0L)
  def urlPathSegmentSanitized(input: String): String = {
    StringUtils.replaceChars(
      Normalizer.normalize(input, Normalizer.Form.NFD)
        .replaceAll("[\\p{InCombiningDiacriticalMarks}]", ""),
      " -._~!$'()*,;&=@:",
      "-"
    ).replaceAll("--", "-")
      .replaceAll("---", "-")
      .replaceAll("----", "-")
      .toLowerCase.trim
  }
}

class OtoroshiCompanion extends RequestTransformer {

  import scala.concurrent.duration._

  private def sign(algorithm: Algorithm, headerJson: JsObject, payloadJson: JsObject): String = {
    val header: String              = Base64.encodeBase64URLSafeString(Json.toBytes(headerJson))
    val payload: String             = Base64.encodeBase64URLSafeString(Json.toBytes(payloadJson))
    val signatureBytes: Array[Byte] = algorithm.sign((header + "." + payload).getBytes(StandardCharsets.UTF_8))
    val signature: String           = Base64.encodeBase64URLSafeString(signatureBytes)
    String.format("%s.%s.%s", header, payload, signature)
  }

  private def passWithSignedToken(ctx: TransformerRequestContext)(f: => Future[Either[Result, HttpRequest]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    ctx.request.getQueryString("token") match {
      case None => Left(Results.Unauthorized(Json.obj("error" -> "not authorized"))).future
      case Some(token) => {
        val tokenHeader = Try(Json.parse(org.apache.commons.codec.binary.Base64.decodeBase64(token.split("\\.")(0)))).getOrElse(Json.obj())
        val kid         = (tokenHeader \ "kid").asOpt[String]
        val alg         = (tokenHeader \ "alg").asOpt[String].getOrElse("RS256")
        ctx.descriptor.secComSettings.asAlgorithmF(InputMode(alg, kid)).flatMap {
          case None => Left(Results.Unauthorized(Json.obj("error" -> "not authorized"))).future
          case Some(algo) => {
            Try(JWT
              .require(algo)
              .withAudience("OtoroshiCompanion")
              .withClaim("method", "GET")
              .withClaim("host", ctx.descriptor.toHost)
              .acceptLeeway(10).build().verify(token)) match {
              case Success(tok) => {
                val exp =
                  Option(tok.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                val iat =
                  Option(tok.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                if (exp.isEmpty || iat.isEmpty) {
                  Left(Results.Unauthorized(Json.obj("error" -> "not authorized"))).future
                } else {
                  if ((exp.get - iat.get) <= 20) { // seconds
                    f
                  } else {
                    Left(Results.Unauthorized(Json.obj("error" -> "not authorized"))).future
                  }
                }
              }
              case Failure(e) =>
                e.printStackTrace()
                Left(Results.Unauthorized(Json.obj("error" -> "not authorized"))).future
            }
          }
        }
      }
    }
  }

  private def getOrCreateCA(certs: Seq[Cert])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Cert] = {
    val cas = certs.filter(_.ca)
    val caNoOto = cas.find(_.id != Cert.OtoroshiCA)
    val caOto = cas.find(_.id == Cert.OtoroshiCA)
    val caHead = cas.headOption
    caHead.orElse(caNoOto).orElse(caOto) match {
      case Some(ca) => FastFuture.successful(ca)
      case None => {
        val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
        keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
        val keyPair1 = keyPairGenerator.generateKeyPair()
        // TODO: customize CN if needed
        // TODO: handle shorter expiration
        val ca = FakeKeyStore.createCA(s"CN=Otoroshi Companion CA", FiniteDuration(365, TimeUnit.DAYS), keyPair1)
        val caCert = Cert(ca, keyPair1, None, client = false).copy(ca = true, autoRenew = true).enrich()
        caCert.copy(id = Cert.OtoroshiCA).save().map(_ => caCert)
      }
    }
  }

  private def getOrCreateBackendCert(_domain: String, certs: Seq[Cert])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Cert] = {
    val domain = _domain.split(":").apply(0)
    certs.filterNot(_.client).find(cert => RegexPool(cert.domain).matches(domain)) match {
      case Some(cert) => FastFuture.successful(cert)
      case None => {
        getOrCreateCA(certs).flatMap { ca =>
          val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
          keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
          val keyPair = keyPairGenerator.generateKeyPair()
          val x509Cert = FakeKeyStore.createCertificateFromCA(
            domain,
            FiniteDuration(365, TimeUnit.DAYS),  // TODO: handle shorter expiration
            keyPair,
            ca.certificate.get,
            ca.keyPair
          )
          val cert = Cert(x509Cert, keyPair, ca, client = false).copy(ca = false, autoRenew = true).enrich()
          cert.save().map(_ => cert)
        }
      }
    }
  }

  private def getOrCreateClientCert(_domain: String, certs: Seq[Cert])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Cert] = {
    val domain = "client-for-" + _domain.split(":").apply(0)
    certs.filter(_.client).find(cert => RegexPool(cert.domain).matches(domain)) match {
      case Some(cert) => FastFuture.successful(cert)
      case None => {
        getOrCreateCA(certs).flatMap { ca =>
          val keyPairGenerator = KeyPairGenerator.getInstance(KeystoreSettings.KeyPairAlgorithmName)
          keyPairGenerator.initialize(KeystoreSettings.KeyPairKeyLength)
          val keyPair = keyPairGenerator.generateKeyPair()
          val x509Cert = FakeKeyStore.createCertificateFromCA(
            domain, //s"client-for-$domain",
            FiniteDuration(365, TimeUnit.DAYS), // TODO: handle shorter expiration
            keyPair,
            ca.certificate.get,
            ca.keyPair
          )
          val cert = Cert(x509Cert, keyPair, ca, client = true).copy(ca = false, autoRenew = true).enrich()
          cert.save().map(_ => cert)
        }
      }
    }
  }

  private def getOrCreateApikey(group: ServiceGroup, desc: ServiceDescriptor, apikeys: Seq[ApiKey])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[ApiKey] = {
    apikeys.find(a => a.authorizedGroup == group.id && a.tags.contains("used-by-service-" + desc.id)) match {
      case Some(cert) => FastFuture.successful(cert)
      case None => {
        val apikey = ApiKey(
          clientName = "apikey-from-service-" + OtoroshiCompanion.urlPathSegmentSanitized(desc.name) + "-on-group-" + OtoroshiCompanion.urlPathSegmentSanitized(group.name),
          authorizedGroup = group.id,
          tags = Seq("used-by-service-" + desc.id) // TODO: handle shorter expiration
        )
        apikey.save().map(_ => apikey)
      }
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {

    val config = (ctx.config \ "OtoroshiCompanion").asOpt[JsValue]
      .orElse((ctx.globalConfig \ "OtoroshiCompanion").asOpt[JsValue])
      .getOrElse(ctx.config)

    val transformsIn: JsValue = (config \ "transformsIn").asOpt[JsArray].getOrElse(Json.arr())
    val transformsOut: JsValue = (config \ "transformsOut").asOpt[JsArray].getOrElse(Json.arr())
    val dynamicTargets = (config \ "dynamicTargets").asOpt[Boolean].getOrElse(false)
    val dynamicTargetsTtl = (config \ "dynamicTargetsTtl").asOpt[Long].getOrElse(30.seconds.toMillis)
    val initialContact = (config \ "initialContact").asOpt[Boolean].getOrElse(false)

    // val apikeyRotation = (config \ "apikeyRotation").asOpt[Boolean].getOrElse(false) // TODO: use it when ready ;)

    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET",  "/.well-known/otoroshi/companion/details") => passWithSignedToken(ctx) {
        for {
          descriptor   <- if (initialContact) {
            env.datastores.serviceDescriptorDataStore.findById(ctx.request.getQueryString("descriptor").get).map(_.get)
          } else {
            FastFuture.successful(ctx.descriptor)
          }
          services     <- env.datastores.serviceDescriptorDataStore.findAll()
          certs        <- env.datastores.certificatesDataStore.findAll()
          allApiKeys   <- env.datastores.apiKeyDataStore.findAll()
          group        <- env.datastores.serviceGroupDataStore.findById(descriptor.groupId).map(_.get)
          groupIds     <- (config \ "groups").asOpt[String]
            .map {
              case "current" => FastFuture.successful(Seq(descriptor.groupId))
              case "all" => env.datastores.serviceGroupDataStore.findAll().map(_.map(_.id).filterNot(_ == env.backOfficeGroupId))
              case _ => FastFuture.successful(Seq(descriptor.groupId))
            }
            .orElse((config \ "groups").asOpt[Seq[String]].map(FastFuture.successful))
            .getOrElse(FastFuture.successful(Seq(descriptor.groupId)))
          hostAndGroup <- services.filter(s => groupIds.contains(s.groupId)).flatMap { service =>
            val target = service.targets.head.theHost
            Seq((service.toHost, service.groupId), (target, service.groupId))
          }.distinct.future
          hostAndNames <- services.filter(s => groupIds.contains(s.groupId)).flatMap { service =>
            Seq((service.toHost, service.name))
          }.distinct.future
          hosts        =  hostAndNames.map(_._1)
          apiKey       <- getOrCreateApikey(group, descriptor, allApiKeys)
          // clientCert   <- getOrCreateClientCert(descriptor.toHost, certs)
          appCert      <- getOrCreateBackendCert(descriptor.targets.head.host, certs)
          clientCerts  <- Source(hosts.toList).mapAsync(1) { domain =>
            println(domain)
            getOrCreateBackendCert(domain, certs).flatMap { _ =>
              getOrCreateClientCert(domain, certs).map(c => (c, domain))
            }
          }.runWith(Sink.seq)
          clientCert    = clientCerts.find(c => c._1.domain == s"client-for-${descriptor.toHost}").get
          apiKeys      <- Source(groupIds.toList).mapAsync(1) { groupId =>
            getOrCreateApikey(group, descriptor, allApiKeys).map(apk => (apk, groupId))
          }.runWith(Sink.seq)
        } yield {
          val apikeysJson = hostAndGroup.map {
            case (host, groupId) =>
              apiKeys.find(_._2 == groupId) match {
                case None => Json.obj()
                case Some((apikey, grid)) => {
                  Json.obj(
                    "domain" -> host,
                    "group" -> grid,
                    "apikey" -> Json.obj(
                      "clientId" -> apikey.clientId,
                      "clientSecret" -> apikey.clientSecret
                    )
                  )
                }
              }
          }
          val cert = appCert.certificatesRaw.head
          val ca = appCert.certificatesRaw.tail.head

          val ccert = clientCert._1.certificatesRaw.head
          val cca = appCert.certificatesRaw.tail.head
          Left(Results.Ok(Json.obj(
            // "domains" -> JsArray(hosts.map(JsString.apply)),
            "domains" -> JsArray(hostAndNames.map {
              case (host, name) => Json.obj(
                "domain" -> host,
                "name" -> name
              )
            }),
            // "domainsWithGroup" -> JsArray(hostAndGroup.map {
            //   case (host, group) => Json.obj(
            //     "domain" -> host,
            //     "group" -> group
            //   )
            // }),
            // "apikey" -> Json.obj(
            //   "clientId" -> apiKey.clientId,
            //   "clientSecret" -> apiKey.clientSecret
            // ),
            "apikeys" -> apikeysJson,
            // "clientCerts" -> JsArray(clientCerts.map {
            //   case (cert, domain) =>
            //     val cert1 = cert.certificatesRaw.head
            //     val ca1 = cert.certificatesRaw.tail.head
            //     Json.obj(
            //       "domain" -> domain,
            //       "cert" -> cert1,
            //       "key" -> cert.privateKey,
            //       "ca" -> ca1
            //     )
            // }),
            "clientCert" -> Json.obj(
              "cert" -> ccert,
              "key" -> clientCert.privateKey,
              "ca" -> cca
            ),
            "appCert" -> Json.obj(
              "cert" -> cert,
              "key" -> appCert.privateKey,
              "ca" -> ca
            ),
            "dynamicTargets" -> dynamicTargets,
            "dynamicTargetsTtl" -> dynamicTargetsTtl,
            "transformsOut" -> transformsOut,
            "transformsIn" -> transformsIn
          )))
        }
      }

      case ("GET", "/.well-known/otoroshi/companion/targets") if dynamicTargets => passWithSignedToken(ctx) {
        val remoteAddress = ctx.request.headers.get("X-Forwarded-For").getOrElse(ctx.request.remoteAddress)
        val port = ctx.request.getQueryString("port").map(_.toInt).getOrElse(15000)
        env.datastores.rawDataStore.set(
          s"${env.storageRoot}:companion:targets:${ctx.descriptor.id}:$remoteAddress-$port",
          ByteString(s"$remoteAddress:$port"),
          Some(dynamicTargetsTtl)
        ).map(_ => Left(Results.Ok(Json.obj("done" -> true))))
      }

      case (_, _) if !dynamicTargets       => Right(ctx.otoroshiRequest).future

      case (_, _) if dynamicTargets        => {
        env.datastores.rawDataStore.keys(s"${env.storageRoot}:companion:targets:${ctx.descriptor.id}:*").flatMap { keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else env.datastores.rawDataStore.mget(keys)
        }.map { seq =>
          val ips = seq.filter(_.isDefined).map(_.get).map(v => v.utf8String)
          if (ips.isEmpty) {
            Left(Results.InternalServerError(Json.obj("error" -> "no target registered")))
          } else {
            val index = OtoroshiCompanion.counter.incrementAndGet() % (if (ips.nonEmpty) ips.size else 1)
            val ipAndPort = ips.apply(index.toInt).split(":")
            val ip = ipAndPort.apply(0)
            val port = ipAndPort.apply(1)
            val targetHost = ctx.otoroshiRequest.target.get.host.split(":").apply(0)
            val request = ctx.otoroshiRequest.copy(
              target = ctx.otoroshiRequest.target.map(t => t.copy(
                scheme = "https",
                host = s"$targetHost:$port",
                ipAddress = Some(ip),
                loose = false
              ))
            )
            Right(request)
          }
        }
      }
    }
  }
}

new OtoroshiCompanion