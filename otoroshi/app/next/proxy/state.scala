package otoroshi.next.proxy

import otoroshi.env.Env
import otoroshi.models.{ApiKeyRouteMatcher, ClientConfig, EntityLocation, HealthCheck, RoundRobin}
import otoroshi.next.models._
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class ProxyState(env: Env) {

  private val routes = TrieMap.newBuilder[String, Route]
    .+=(Route.fake.id -> Route.fake)
    .result()

  def allRoutes(): Seq[Route] = routes.values.toSeq
  def route(id: String): Option[Route] = routes.get(id)

  def updateRoutes(new_routes: Seq[Route]): Unit = {
    val routeIds = new_routes.map(_.id)
    new_routes.foreach(route => routes.put(route.id, route))
    routes.keySet.filterNot(key => routeIds.contains(key)).foreach(key => routes.remove(key))
  }
}

class ProxyStateLoaderJob extends Job {

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.ProxyStateLoaderJob")

  override def name: String = "proxy state loader job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.second.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  def generateRoutesByDomain(): Future[Seq[Route]] = {
    (0 until 10000).map { idx =>
      Route(
        location = EntityLocation.default,
        id = s"route_generated-domain-${idx}",
        name = s"generated_fake_route_domain_${idx}",
        description = s"generated_fake_route_domain_${idx}",
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        debugFlow = true,
        frontend = Frontend(
          domains = Seq(DomainAndPath(s"${idx}-generated-next-gen.oto.tools")),
          headers = Map.empty,
          stripPath = true,
          apikey = ApiKeyRouteMatcher(),
        ),
        backends = Backends(
          targets = Seq(Backend(
            id = "mirror-1",
            hostname = "mirror.otoroshi.io",
            port = 443,
            tls = true
          )),
          root = s"/gen-${idx}",
          loadBalancing = RoundRobin
        ),
        client = ClientConfig(),
        healthCheck = HealthCheck(false, "/"),
        plugins = Plugins(Seq(
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.OverrideHost",
            enabled = true,
            include = Seq.empty,
            exclude = Seq.empty,
            config = PluginInstanceConfig(Json.obj())
          ),
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
            enabled = true,
            include = Seq.empty,
            exclude = Seq.empty,
            config = PluginInstanceConfig(Json.obj(
              "headers" -> Json.obj(
                "bar" -> "foo"
              )
            ))
          )
        ))
      )
    }.future
  }

  def generateRoutesByName(): Future[Seq[Route]] = {
    (0 until 10000).map { idx =>
      Route(
        location = EntityLocation.default,
        id = s"route_generated-path-${idx}",
        name = s"generated_fake_route_path_${idx}",
        description = s"generated_fake_route_path_${idx}",
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        debugFlow = true,
        frontend = Frontend(
          domains = Seq(DomainAndPath(s"path-generated-next-gen.oto.tools/api/${idx}")),
          headers = Map.empty,
          stripPath = true,
          apikey = ApiKeyRouteMatcher(),
        ),
        backends = Backends(
          targets = Seq(Backend(
            id = "mirror-1",
            hostname = "mirror.otoroshi.io",
            port = 443,
            tls = true
          )),
          root = s"/path-${idx}",
          loadBalancing = RoundRobin
        ),
        client = ClientConfig(),
        healthCheck = HealthCheck(false, "/"),
        plugins = Plugins(Seq(
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.OverrideHost",
            config = PluginInstanceConfig(Json.obj())
          ),
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
            config = PluginInstanceConfig(Json.obj(
              "headers" -> Json.obj(
                "bar" -> "foo"
              )
            ))
          )
        ))
      )
    }.future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    for {
      routes <- env.datastores.routeDataStore.findAll().map(routes => routes ++ Seq(Route.fake))
      genRoutesDomain <- generateRoutesByDomain()
      genRoutesPath <- generateRoutesByName()
      descriptors <- env.datastores.serviceDescriptorDataStore.findAll()
    } yield {
      val newRoutes = genRoutesDomain ++ genRoutesPath ++ descriptors.map(Route.fromServiceDescriptor) ++ routes
      env.proxyState.updateRoutes(newRoutes)
    }
  }
}

