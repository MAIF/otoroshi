package otoroshi.plugins.jobs.kubernetes

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, TimeUnit}

import io.kubernetes.client.extended.controller.builder.ControllerBuilder
import io.kubernetes.client.extended.controller.reconciler.{Reconciler, Request}
import io.kubernetes.client.extended.controller.{Controller, LeaderElectingController, reconciler}
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock
import io.kubernetes.client.extended.leaderelection.{LeaderElectionConfig, LeaderElector}
import io.kubernetes.client.extended.workqueue.WorkQueue
import io.kubernetes.client.informer.SharedInformerFactory
import io.kubernetes.client.openapi.apis.{CoreV1Api, NetworkingV1beta1Api}
import io.kubernetes.client.openapi.models._
import io.kubernetes.client.util.credentials.AccessTokenAuthentication
import io.kubernetes.client.util.{CallGeneratorParams, ClientBuilder}
import okhttp3.Call

import scala.reflect.ClassTag

object KubernetesSupport {

  class OfficialSDKKubernetesController(config: KubernetesConfig) {

    private val ref = new AtomicReference[LeaderElectingController]()
    private val tp = Executors.newFixedThreadPool(2)

    def buildController[T, L](name: String, informerFactory: SharedInformerFactory)(f: CallGeneratorParams => Call)(implicit c1: ClassTag[T], c2: ClassTag[L]): Controller = {
      val informer = informerFactory.sharedIndexInformerFor(
        (params: CallGeneratorParams) => f(params),
        c1.runtimeClass,
        c2.runtimeClass
      )
      val controller = ControllerBuilder.defaultBuilder(informerFactory)
        .watch[V1Service]((workQueue: WorkQueue[Request]) => {
        ControllerBuilder
          .controllerWatchBuilder(classOf[V1Service], workQueue)
          .withWorkQueueKeyFunc(node => new Request(node.getMetadata.getNamespace, node.getMetadata.getName))
          .build()
      })
        .withReconciler(new Reconciler {
          override def reconcile(request: Request): reconciler.Result = {
            val service = informer.getIndexer.getByKey(s"${request.getNamespace}/${request.getName}")
            // println(s"$name - ${request.getName} / ${request.getNamespace} / ${service == null}")
            new io.kubernetes.client.extended.controller.reconciler.Result(false)
          }
        }) // required, set the actual reconciler
        .withName(name)
        .withWorkerCount(4) // optional, set worker thread count
        .withReadyFunc(() => true)//nodeInformer.hasSynced())
        .build()
      controller
    }

    def start(): OfficialSDKKubernetesController = {

      val apiClient = new ClientBuilder()
        .setVerifyingSsl(!config.trust)
        .setAuthentication(new AccessTokenAuthentication(config.token.get))
        .setBasePath(config.endpoint)
        .setCertificateAuthority(config.caCert.map(c => c.getBytes).orNull)
        .build()
      val httpClient = apiClient.getHttpClient.newBuilder
        .readTimeout(0, TimeUnit.SECONDS)
        .build
      apiClient.setHttpClient(httpClient)
      val coreV1Api = new CoreV1Api(apiClient)
      val netApi = new NetworkingV1beta1Api(apiClient)
      val informerFactory = new SharedInformerFactory

      val controllerManager = ControllerBuilder.controllerManagerBuilder(informerFactory)
        .addController(buildController[V1Service, V1ServiceList]("otoroshi-controller-services", informerFactory) { params =>
          coreV1Api.listServiceAccountForAllNamespacesCall(null,null,null,null,null,null, params.resourceVersion, params.timeoutSeconds, params.watch,null)
        })
        .addController(buildController[V1Endpoints, V1EndpointsList]("otoroshi-controller-endpoints", informerFactory) { params =>
          coreV1Api.listEndpointsForAllNamespacesCall(null,null,null,null,null,null, params.resourceVersion, params.timeoutSeconds, params.watch,null)
        })
        .addController(buildController[NetworkingV1beta1Ingress, NetworkingV1beta1IngressList]("otoroshi-controller-ingresses", informerFactory) { params =>
          netApi.listIngressForAllNamespacesCall(null,null,null,null,null,null, params.resourceVersion, params.timeoutSeconds, params.watch,null)
        })
        .build()

      informerFactory.startAllRegisteredInformers()

      val leaderElectingController =
        new LeaderElectingController(
          new LeaderElector(
            new LeaderElectionConfig(
              new EndpointsLock("kube-system", "leader-election", "otoroshi-controllers", apiClient),
              java.time.Duration.ofMillis(10000),
              java.time.Duration.ofMillis(8000),
              java.time.Duration.ofMillis(5000))),
          controllerManager)

      ref.set(leaderElectingController)
      tp.submit(new Runnable {
        override def run(): Unit = leaderElectingController.run()
      })
      this
    }

    def stop(): OfficialSDKKubernetesController  = {
      Option(ref.get()).foreach(_.shutdown())
      this
    }
  }
}