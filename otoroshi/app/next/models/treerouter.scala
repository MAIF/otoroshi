package otoroshi.next.models

import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.models.{ClientConfig, EntityLocation}
import otoroshi.netty.NettyRequestKeys
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.typedmap
import play.api.mvc.request.{RemoteConnection, RequestTarget}
import play.api.mvc.{Headers, RequestHeader}

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

sealed trait RoutingStrategy {
  def json: JsValue
}
object RoutingStrategy       {
  case object Tree       extends RoutingStrategy { def json: JsValue = JsString("tree")       }
  case object StartsWith extends RoutingStrategy { def json: JsValue = JsString("startswith") }
  def parse(value: String): RoutingStrategy = value.toLowerCase() match {
    case "startswith" => StartsWith
    case _            => Tree
  }
}

case class NgMatchedRoutes(
    routes: Seq[NgRoute],
    path: String = "",
    pathParams: scala.collection.mutable.HashMap[String, String] = scala.collection.mutable.HashMap.empty,
    noMoreSegments: Boolean
) {
  def find(
      f: (NgRoute, String, scala.collection.mutable.HashMap[String, String], Boolean) => Boolean
  ): Option[NgMatchedRoute] = {
    routes.find(r => f(r, path, pathParams, noMoreSegments)).map { r =>
      // TODO: find a solution to cleanup the map from non desirable path params names
      // for request on /api/contracts/123/foo/foo on router like
      //   - /api/contracts/:id/foo/bar
      //   - /api/contracts/:cid/foo/foo
      // we want only Map(cid -> 123) and not Map(cid -> 123, id -> 123)
      NgMatchedRoute(r, path, pathParams, noMoreSegments)
    }
  }
}
case class NgMatchedRoute(
    route: NgRoute,
    path: String = "",
    pathParams: scala.collection.mutable.HashMap[String, String] = scala.collection.mutable.HashMap.empty,
    noMoreSegments: Boolean = false
) {
  def json: JsValue = Json.obj(
    "route_id"         -> route.id,
    "path"             -> path,
    "path_params"      -> pathParams,
    "no_more_segments" -> noMoreSegments
  )
}

object NgTreeRouter {
  def empty = NgTreeRouter(new UnboundedTrieMap[String, NgTreeNodePath](), scala.collection.mutable.MutableList.empty)
  def build(routes: Seq[NgRoute]): NgTreeRouter = {
    val root = NgTreeRouter.empty
    routes.foreach { route =>
      route.frontend.domains.foreach { dpath =>
        if (dpath.domain.contains("*")) {
          root.wildcards.+=(NgRouteDomainAndPathWrapper(route, dpath.domain, dpath.path))
        }
        val ptree = root.tree.getOrElseUpdate(dpath.domain, NgTreeNodePath.empty)
        ptree.addSubRoutes(dpath.path.split("/").toSeq.filterNot(_.trim.isEmpty), route)
      }
    }
    root.wildcards.sortWith((r1, r2) => r1.domain.length.compareTo(r2.domain.length) > 0)
    root
  }
}

case class NgRouteDomainAndPathWrapper(route: NgRoute, domain: String, path: String)

case class NgTreeRouter(
    tree: TrieMap[String, NgTreeNodePath],
    wildcards: scala.collection.mutable.MutableList[NgRouteDomainAndPathWrapper]
) {

  def json: JsValue = Json.obj(
    "tree"      -> JsObject(tree.toMap.mapValues(_.json)),
    "wildcards" -> JsArray(wildcards.map(r => JsString(r.route.name)))
  )

  def findRoute(request: RequestHeader, attrs: TypedMap)(implicit env: Env): Option[NgMatchedRoute] = {
    find(request.theDomain, request.thePath)
      .flatMap { routes =>
        val forCurrentListenerOnly = request.attrs.get(NettyRequestKeys.ListenerExclusiveKey)
          .orElse(attrs.get(otoroshi.plugins.Keys.ForCurrentListenerOnlyKey))
          .getOrElse(false)
        val finalRoutes = request.attrs.get(NettyRequestKeys.ListenerIdKey) match {
          case None =>
            // println("should display on standard listener")
            routes.copy(routes = routes.routes.filter(r => r.notBoundToListener || r.boundToListener("standard")))
          case Some(listener) if forCurrentListenerOnly =>
            // println("should display on exclusive")
            routes.copy(routes = routes.routes.filter(r => r.boundToListener(listener)))
          case Some(listener) =>
            // println("should display on non exclusive")
            routes.copy(routes = routes.routes.filter(r => r.notBoundToListener || r.boundToListener(listener)))
        }
        val routeIds = finalRoutes.routes.map(_.cacheableId)
        attrs.put(otoroshi.next.plugins.Keys.MatchedRoutesKey -> routeIds)
        finalRoutes.find((r, matchedPath, pathParams, noMoreSegments) =>
          r.matches(
            request,
            attrs,
            matchedPath,
            pathParams,
            noMoreSegments = noMoreSegments,
            skipDomainVerif = true,
            skipPathVerif = true
          )
        )
      }
  }

  def find(domain: String, path: String): Option[NgMatchedRoutes] = {
    tree.get(domain) match {
      case Some(ptree) =>
        ptree.find(
          path.split("/").filterNot(_.trim.isEmpty),
          path.endsWith("/"),
          "",
          scala.collection.mutable.HashMap.empty
        )
      case None        => findWildcard(domain)
    }
  }

  def findWildcard(domain: String): Option[NgMatchedRoutes] = {
    wildcards
      .find { route =>
        RegexPool(route.domain).matches(domain)
      }
      .flatMap(route => find(route.domain, route.path))
  }
}

object NgTreeNodePath {

  val logger = Logger("otoroshi-next-tree-node-path")

  def addSubRoutes(current: NgTreeNodePath, segments: Seq[String], route: NgRoute): Unit = {
    if (segments.isEmpty) {
      current.addRoute(route)
    } else {
      val sub = current.tree.getOrElseUpdate(segments.head, NgTreeNodePath.empty)
      if (segments.size == 1) {
        sub.addRoute(route)
      } else {
        addSubRoutes(sub, segments.tail, route)
      }
    }
  }

  def empty: NgTreeNodePath =
    NgTreeNodePath(scala.collection.mutable.MutableList.empty, new UnboundedTrieMap[String, NgTreeNodePath])
}

case class NgTreeNodePath(
    routes: scala.collection.mutable.MutableList[NgRoute],
    tree: TrieMap[String, NgTreeNodePath]
) {
  lazy val wildcardCache                         =
    Scaffeine().maximumSize(100).expireAfterWrite(10.seconds).build[String, Option[NgTreeNodePath]]()
  lazy val segmentStartsWithCache                =
    Scaffeine().maximumSize(100).expireAfterWrite(10.seconds).build[String, Option[NgMatchedRoutes]]()
  lazy val isLeaf: Boolean                       = tree.isEmpty
  lazy val wildcardEntry: Option[NgTreeNodePath] =
    tree.get("*") // lazy should be good as once built the mutable map is never mutated again
  lazy val hasWildcardKeys: Boolean                                    = wildcardKeys.nonEmpty
  lazy val wildcardKeys: scala.collection.Set[String]                  = tree.keySet.filter(_.contains("*"))
  lazy val hasNamedKeys: Boolean                                       = namedKeys.nonEmpty
  lazy val namedKeys: scala.collection.Set[String]                     = tree.keySet.filter(_.startsWith(":"))
  lazy val hasRegexKeys: Boolean                                       = regexKeys.nonEmpty
  lazy val regexKeys: scala.collection.Set[String]                     = tree.keySet.filter(v => v.startsWith("$") && v.endsWith(">"))
  lazy val isEmpty                                                     = routes.isEmpty && isLeaf
  def wildcardEntriesMatching(segment: String): Option[NgTreeNodePath] = wildcardCache.get(
    segment,
    _ => wildcardKeys.find(str => RegexPool(str).matches(segment)).flatMap(key => tree.get(key))
  )
  def addRoute(route: NgRoute): NgTreeNodePath = {
    routes.+=(route)
    this
  }
  def addSubRoutes(segments: Seq[String], route: NgRoute): Unit = {
    NgTreeNodePath.addSubRoutes(this, segments, route)
  }
  def json: JsValue                                                    = Json.obj(
    "routes" -> routes.map(r => JsString(r.name)),
    "leaf"   -> isLeaf,
    "tree"   -> JsObject(tree.toMap.mapValues(_.json))
  )
  def find(
      segments: Seq[String],
      endsWithSlash: Boolean,
      path: String,
      pathParams: scala.collection.mutable.HashMap[String, String]
  ): Option[NgMatchedRoutes] = {
    segments.headOption match {
      case None if routes.isEmpty => None
      case None                   => NgMatchedRoutes(routes, path, pathParams, noMoreSegments = true).some
      case Some(head)             => {
        val mptree = tree
          .get(head)
          .applyOnWithPredicate(opt => if (opt.isEmpty) (hasNamedKeys || hasRegexKeys) else false) { _ =>
            // here is how to solve the case where the user do something like
            // - /api/contracts/:contract-id/items
            // - /api/contracts/:contract/fifou
            namedKeys.map(nk => pathParams.+=(nk.replaceFirst(":", "") -> head))
            val nroute = namedKeys
              .map(k => tree.get(k))
              .collect { case Some(ptree) => ptree }
              .fold(NgTreeNodePath.empty)((a, b) => a.copy(routes = a.routes ++ b.routes, tree = a.tree ++ b.tree))
            // handling regex path params like /api/contracts/$id<[0-9]+>/items
            val matchingRegexKeys = {
              regexKeys
                .map(k => (k, k.split("<").tail.mkString("<").init))
                .filter(reg => RegexPool.regex(reg._2).matches(head))
            }
            matchingRegexKeys.map(nk => pathParams.+=(nk._1.substring(1).split("<").head -> head))
            val rroute = matchingRegexKeys
              .map(k => tree.get(k._1))
              .collect { case Some(ptree) => ptree }
              .fold(NgTreeNodePath.empty)((a, b) => a.copy(routes = a.routes ++ b.routes, tree = a.tree ++ b.tree))
            // merge the tree
            NgTreeNodePath.empty.copy(routes = nroute.routes ++ rroute.routes, tree = nroute.tree ++ rroute.tree).some
          }
          .applyOnWithPredicate(opt => if (opt.isEmpty) hasWildcardKeys else false) { opt =>
            opt.orElse(wildcardEntriesMatching(head)).orElse(wildcardEntry)
          }
        mptree match {
          case None if endsWithSlash && routes.isEmpty              => None
          case None if endsWithSlash && routes.nonEmpty             =>
            // NgMatchedRoutes(routes, s"$path/$head", pathParams, noMoreSegments = segments.isEmpty).some
            NgMatchedRoutes(routes, path, pathParams, noMoreSegments = segments.tail.isEmpty).some
          case None if !endsWithSlash                               => {
            // here is one of the worst case scenario where the user wants to use '/api/999' to match calls on '/api/999-foo'
            segmentStartsWithCache.get(
              head,
              _ => {
                var sw       = false
                val mSubTree = tree.keySet.toSeq
                  .sortWith((r1, r2) => r1.length.compareTo(r2.length) > 0)
                  .find {
                    case key if key.contains("*") => RegexPool(key).matches(head)
                    case key                      =>
                      sw = true
                      head.startsWith(key)
                  }
                  .flatMap(key => tree.get(key))
                mSubTree match {
                  case None if routes.isEmpty => None
                  case None                   =>
                    // NgMatchedRoutes(routes, s"$path/$head", pathParams, noMoreSegments = false).some
                    NgMatchedRoutes(routes, path, pathParams, noMoreSegments = false).some
                  case Some(ptree)            =>
                    ptree
                      .applyOnIf(sw) { pt =>
                        // here returned matched routes can't have more possible segments or you will match anything
                        pt.copy(tree = TrieMap.empty)
                      }
                      .find(segments.tail, endsWithSlash, s"$path/$head", pathParams) match {
                      case None if routes.isEmpty => None
                      case None                   => NgMatchedRoutes(routes, s"$path/$head", pathParams, noMoreSegments = false).some
                      case s                      => s
                    }
                }
              }
            )
          }
          case Some(ptree) if ptree.isEmpty && routes.isEmpty       => None
          case Some(ptree) if ptree.isEmpty && routes.nonEmpty      =>
            NgMatchedRoutes(routes, s"$path/$head", pathParams, noMoreSegments = segments.tail.isEmpty).some
          case Some(ptree) if ptree.isLeaf && ptree.routes.isEmpty  => None
          case Some(ptree) if ptree.isLeaf && ptree.routes.nonEmpty =>
            NgMatchedRoutes(ptree.routes, s"$path/$head", pathParams, noMoreSegments = segments.tail.isEmpty).some
          case Some(ptree)                                          =>
            ptree.find(segments.tail, endsWithSlash, s"$path/$head", pathParams) match {
              case None if routes.isEmpty => None
              case None                   =>
                NgMatchedRoutes(routes, s"$path/$head", pathParams, noMoreSegments = segments.tail.isEmpty).some
              case s                      => s
            }
        }
      }
    }
  }
}

object NgTreeRouter_Test {

  case object NgFakeRemoteConnection extends RemoteConnection {
    override def remoteAddress: InetAddress                           = InetAddress.getLocalHost
    override def secure: Boolean                                      = false
    override def clientCertificateChain: Option[Seq[X509Certificate]] = None
  }

  case class NgFakeRequestTarget(path: String) extends RequestTarget {
    private val _uri                                = new URI(path)
    private val _query                              = Map.empty[String, Seq[String]]
    override def uri: URI                           = _uri
    override def uriString: String                  = path
    override def queryMap: Map[String, Seq[String]] = _query
  }

  class NgFakeRequestHeader(domain: String, path: String, _method: String = "GET") extends RequestHeader {

    private val _attrs      = typedmap.TypedMap.empty
    private val _target     = NgFakeRequestTarget(path)
    private val _connection = NgFakeRemoteConnection
    private val _headers    = Headers("Host" -> domain)

    override def method: String  = _method
    override def version: String = "HTTP/1.1"

    override def connection: RemoteConnection = _connection
    override def target: RequestTarget        = _target
    override def attrs: typedmap.TypedMap     = _attrs
    override def headers: Headers             = _headers
  }

  object NgFakeRoute {
    def route(id: String): NgRoute = {
      NgRoute(
        location = EntityLocation.default,
        id = id,
        name = id,
        description = id,
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        capture = false,
        debugFlow = false,
        exportReporting = false,
        groups = Seq("default"),
        frontend =
          NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"test-tree-router-next-gen.oto.tools/api/$id"))),
        backend = NgBackend.empty
          .copy(root = s"/id/${id}", targets = Seq(NgTarget("localhost", "127.0.0.1", 8081, tls = false))),
        backendRef = None,
        plugins = NgPlugins(Seq.empty)
      )
    }
    def routeFromPath(rpath: String): NgRoute = {
      val id     = rpath
      val parts  = rpath.split("/").toSeq.filter(_.trim.nonEmpty)
      val domain = parts.head
      val path   = parts.tail.mkString("/", "/", "")
      NgRoute(
        location = EntityLocation.default,
        id = id,
        name = id,
        description = id,
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        capture = false,
        debugFlow = false,
        exportReporting = false,
        groups = Seq("default"),
        frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(rpath)), stripPath = false),
        backend =
          NgBackend.empty.copy(root = s"/", targets = Seq(NgTarget("localhost", "127.0.0.1", 8081, tls = false))),
        backendRef = None,
        plugins = NgPlugins(Seq.empty)
      )
    }
  }

  def testFindRoute(env: Env): Unit = {

    val routes      = (0 to 1000000).map(idx => NgFakeRoute.route(s"$idx"))
    val router      = NgTreeRouter.build(routes)
    val test_domain = "test-tree-router-next-gen.oto.tools"
    val counter     = new AtomicLong(0L)
    val sum         = new AtomicLong(0L)

    val attrs = TypedMap.empty

    def clear(): Unit = {
      counter.set(0L)
      sum.set(0L)
    }

    def printStats(iteration: String): Unit = {
      val duration = sum.get().nanos.toMillis
      val avg      = sum.get() / counter.get()
      println(s"$iteration - duration: ${duration} ms, for ${counter.get()} iterations, avg iteration is: ${avg} nanos")
    }

    def findRoute(print: Boolean): Unit = {
      val idx                    = Math.round(Math.random() * 1000000).toString
      val path                   = s"/api/${idx}/foo"
      val request: RequestHeader = new NgFakeRequestHeader(test_domain, path)
      val start_ns               = System.nanoTime()
      val f_route                = router.findRoute(request, attrs)(env)
      val duration_ns            = System.nanoTime() - start_ns
      counter.incrementAndGet()
      sum.addAndGet(duration_ns)
      if (print) {
        val found = f_route.isDefined && f_route.map(_.route.name).contains(idx)
        println(path, found, duration_ns + " nanos", duration_ns.nanos.toMillis + " ms")
      }
    }

    for (idx <- 1 to 100) {
      for (_ <- 1 to 100000) {
        findRoute(false)
      }
      printStats(s"warmup-${idx}")
      clear()
    }

    clear()
    for (_ <- 1 to 1000000) {
      findRoute(false)
    }
    printStats("run-no-print")
    clear()

    for (_ <- 1 to 100) {
      findRoute(true)
    }
    printStats("run-print")
    clear()
  }

  def testFindRoutes(): Unit = {

    val routes      = (0 to 1000000).map(idx => NgFakeRoute.route(s"$idx"))
    val router      = NgTreeRouter.build(routes)
    val test_domain = "test-tree-router-next-gen.oto.tools"
    val counter     = new AtomicLong(0L)
    val sum         = new AtomicLong(0L)

    def clear(): Unit = {
      counter.set(0L)
      sum.set(0L)
    }

    def printStats(iteration: String): Unit = {
      val duration = sum.get().nanos.toMillis
      val avg      = sum.get() / counter.get()
      println(s"$iteration - duration: ${duration} ms, for ${counter.get()} iterations, avg iteration is: ${avg} nanos")
    }

    def findRoutes(print: Boolean): Unit = {
      val idx         = Math.round(Math.random() * 1000000).toString
      val path        = s"/api/${idx}/foo"
      val start_ns    = System.nanoTime()
      val f_routes    = router.find(test_domain, path)
      val duration_ns = System.nanoTime() - start_ns
      counter.incrementAndGet()
      sum.addAndGet(duration_ns)
      if (print) {
        val found =
          f_routes.isDefined && f_routes.exists(_.routes.size == 1) && f_routes.map(_.routes.head.name).contains(idx)
        println(path, found, duration_ns + " nanos", duration_ns.nanos.toMillis + " ms")
      }
    }

    for (idx <- 1 to 100) {
      for (_ <- 1 to 100000) {
        findRoutes(false)
      }
      printStats(s"warmup-${idx}")
      clear()
    }

    clear()
    for (_ <- 1 to 1000000) {
      findRoutes(false)
    }
    printStats("run-no-print")
    clear()

    for (_ <- 1 to 100) {
      findRoutes(true)
    }
    printStats("run-print")
    clear()
  }

  def testPathParams(): Unit = {
    val routes = Seq(
      NgFakeRoute.route("contracts/:id/items"),
      NgFakeRoute.route("contracts/:contract/items"),
      NgFakeRoute.route("contracts/:contractid/items/bar")
    )
    val router = NgTreeRouter.build(routes)
    router.json.prettify.debugPrintln
    router.find("test-tree-router-next-gen.oto.tools", "/api/contracts/1234/items").map { mroute =>
      println(mroute.path)
      println(mroute.pathParams)
    }
    println("-------")
    router.find("test-tree-router-next-gen.oto.tools", "/api/contracts/1234/items/foo/bar").map { mroute =>
      println(mroute.path)
      println(mroute.pathParams)
    }
  }

  def testRealLifeRouter(): Unit = {
    val routesStr = Json
      .parse(java.nio.file.Files.readString(new java.io.File("../test-resources/paths.json").toPath()))
      .as[Seq[String]]
    val routes    = routesStr.map(path => NgFakeRoute.routeFromPath(path))
    val router    = NgTreeRouter.build(routes)

    val counter = new AtomicLong(0L)
    val sum     = new AtomicLong(0L)

    def clear(): Unit = {
      counter.set(0L)
      sum.set(0L)
    }

    def printStats(iteration: String): Unit = {
      val duration = sum.get().nanos.toMillis
      val avg      = sum.get() / counter.get()
      println(s"$iteration - duration: ${duration} ms, for ${counter.get()} iterations, avg iteration is: ${avg} nanos")
    }

    def run(print: Boolean): Unit = {
      routesStr.foreach { rpath =>
        val parts       = rpath.split("/").toSeq.filter(_.trim.nonEmpty)
        val domain      = parts.head
        val path        = parts.tail.mkString("/", "/", "")
        val start_ns    = System.nanoTime()
        val f_routes    = router.find(domain, path)
        val duration_ns = System.nanoTime() - start_ns
        counter.incrementAndGet()
        sum.addAndGet(duration_ns)
        if (print) {
          val found = f_routes.isDefined && f_routes
            .exists(_.routes.size == 1) && f_routes.map(_.routes.head.name).contains(rpath)
          println(path, found, duration_ns + " nanos", duration_ns.nanos.toMillis + " ms")
        }
      }
    }
    for (idx <- 1 to 100) {
      run(false)
      printStats(s"warmup-${idx}")
      clear()
    }

    run(true)
    printStats(s"run")
    clear()
  }

  def testWildcardDomainsRouter(): Unit = {
    val routes = Seq(NgFakeRoute.routeFromPath("*-wildcard-next-gen.oto.tools/api"))
    val router = NgTreeRouter.build(routes)

    println(router.json.prettify)

    router.find("foo-ildcard-next-gen.oto.tools", "/api").map(_.routes.map(_.name)).debugPrintln
    router.find("foo-wildcard-next-gen.oto.tools", "/api").map(_.routes.map(_.name)).debugPrintln
    router.find("foo1-wildcard-next-gen.oto.tools", "/api").map(_.routes.map(_.name)).debugPrintln
    router.find("foo2-wildcard-next-gen.oto.tools", "/api").map(_.routes.map(_.name)).debugPrintln
    router.find("foo-bar-wildcard-next-gen.oto.tools", "/api").map(_.routes.map(_.name)).debugPrintln
    router.find("foo-bar-quix-wildcard-next-gen.oto.tools", "/api").map(_.routes.map(_.name)).debugPrintln
  }
}
