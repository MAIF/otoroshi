package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api.{
  BackendCallResponse,
  NgBackendCall,
  NgPluginCategory,
  NgPluginConfig,
  NgPluginHttpResponse,
  NgPluginVisibility,
  NgStep,
  NgbBackendCallContext
}
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.plugins.jobs.kubernetes.{KubernetesCRDsJob, KubernetesConfig}
import otoroshi.script.JobContext
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.config.ConfigUtils
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class KubernetesNamespaceScanConfig(namespaces: Seq[String] = Seq.empty) extends NgPluginConfig {
  override def json: JsValue = Json.obj("namespaces" -> namespaces)
}

object KubernetesNamespaceScanConfig {
  val fmt = new Format[KubernetesNamespaceScanConfig] {
    override def reads(json: JsValue): JsResult[KubernetesNamespaceScanConfig] = {
      Try {
        KubernetesNamespaceScanConfig(
          namespaces = json.select("namespaces").asOpt[Seq[String]].getOrElse(Seq.empty)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
    override def writes(o: KubernetesNamespaceScanConfig): JsValue = o.json
  }
}

class KubernetesNamespaceScanBackend extends NgBackendCall {
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def useDelegates: Boolean             = false

  override def name: String                = "Kubernetes Namespace Scanner"
  override def description: Option[String] =
    "Triggers Kubernetes CRD controller to scan specified namespaces".some

  override def defaultConfigObject: Option[NgPluginConfig] =
    KubernetesNamespaceScanConfig().some

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val scanConfig = ctx
      .cachedConfig(internalName)(KubernetesNamespaceScanConfig.fmt)
      .getOrElse(KubernetesNamespaceScanConfig())

    if (scanConfig.namespaces.isEmpty) {
      BackendCallResponse(
        NgPluginHttpResponse.fromResult(
          Results.BadRequest(Json.obj("error" -> "no namespaces to scan"))
        ),
        None
      ).rightf
    } else {
      val jobCtx    = createJobContext(env)
      val k8sConfig = KubernetesConfig.theConfig(jobCtx)

      initializeKubernetesResources(k8sConfig, jobCtx)

      syncNamespaces(k8sConfig, jobCtx, scanConfig.namespaces)
    }
  }

  private def createJobContext(env: Env): JobContext = {
    JobContext(
      snowflake = IdGenerator.uuid,
      attrs = TypedMap.empty,
      globalConfig = ConfigUtils.mergeOpt(
        env.datastores.globalConfigDataStore.latestSafe.map(_.scripts.jobConfig).getOrElse(Json.obj()),
        env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
      ),
      actorSystem = env.otoroshiActorSystem,
      scheduler = env.otoroshiActorSystem.scheduler
    )
  }

  private def initializeKubernetesResources(k8sConfig: KubernetesConfig, jobCtx: JobContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Unit = {
    KubernetesCRDsJob.patchCoreDnsConfig(k8sConfig, jobCtx)
    KubernetesCRDsJob.patchKubeDnsConfig(k8sConfig, jobCtx)
    KubernetesCRDsJob.patchOpenshiftDnsOperatorConfig(k8sConfig, jobCtx)
    KubernetesCRDsJob.patchValidatingAdmissionWebhook(k8sConfig, jobCtx)
    KubernetesCRDsJob.patchMutatingAdmissionWebhook(k8sConfig, jobCtx)
    KubernetesCRDsJob.createWebhookCerts(k8sConfig, jobCtx)
    KubernetesCRDsJob.createMeshCerts(k8sConfig, jobCtx)
  }

  private def syncNamespaces(
      k8sConfig: KubernetesConfig,
      jobCtx: JobContext,
      namespaces: Seq[String]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    KubernetesCRDsJob
      .syncCRDs(
        k8sConfig,
        jobCtx.attrs,
        jobRunning = true,
        namespaces = namespaces.some,
        verboseLogging = true
      )
      .map { syncReport =>
        BackendCallResponse(
          NgPluginHttpResponse.fromResult(
            Results
              .Ok(
                syncReport
                  .map(_.toJson)
                  .getOrElse(Json.obj("result" -> "done"))
              )
          ),
          None
        ).right
      }
  }
}
