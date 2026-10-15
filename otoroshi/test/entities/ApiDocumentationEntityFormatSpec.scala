package entities

import otoroshi.next.models.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class ApiDocumentationEntityFormatSpec extends EntityFormatSpec {

  private val icon = Json.obj("css_icon_class" -> "fas fa-book")

  private val full = ApiDocumentation(
    enabled = false,
    source = Some(ApiDocumentationSource(Json.obj("url" -> "https://docs.oto.tools/users", "timeout" -> 5000))),
    home = ApiDocumentationResource(Json.obj("text_content" -> "# Users", "content_type" -> "text/markdown")),
    logo = ApiDocumentationResource(Json.obj("url" -> "https://docs.oto.tools/logo.png", "content_type" -> "image/png")),
    references = Seq(ApiDocumentationResourceRef(Json.obj("title" -> "OpenAPI", "link" -> "/openapi.json", "icon" -> icon))),
    resources = Seq(ApiDocumentationResource(Json.obj("path" -> Json.arr("guides", "start"), "title" -> "Getting started"))),
    navigation = Seq(
      ApiDocumentationSidebar(
        Json.obj(
          "label" -> "Guides",
          "items" -> Json.arr(Json.obj("kind" -> "link", "label" -> "Start", "link" -> "/guides/start"))
        )
      )
    ),
    redirections = Seq(ApiDocumentationRedirection(Json.obj("from" -> "/old", "to" -> "/new"))),
    footer = Some(ApiDocumentationResource(Json.obj("text_content" -> "(c) oto"))),
    search = ApiDocumentationSearch(Json.obj("enabled" -> false)),
    banner = Some(ApiDocumentationResource(Json.obj("text_content" -> "beta"))),
    metadata = Map("owner" -> "docs-team"),
    tags = Seq("public")
  )

  "ApiDocumentation" should {

    val format = ApiDocumentation._fmt

    "write the default documentation" in {
      format.writes(ApiDocumentation()) mustBe Json.obj(
        "enabled"      -> true,
        "source"       -> JsNull,
        "home"         -> Json.obj(),
        "logo"         -> Json.obj(),
        "references"   -> Json.arr(),
        "resources"    -> Json.arr(),
        "navigation"   -> Json.arr(),
        "redirections" -> Json.arr(),
        "footer"       -> JsNull,
        "search"       -> Json.obj("enabled" -> true),
        "banner"       -> JsNull,
        "metadata"     -> Json.obj(),
        "tags"         -> Json.arr()
      )
    }

    "write each part of a documentation as its raw json" in {
      val json = written(format, full)
      (json \ "source").as[JsObject] mustBe full.source.get.raw
      (json \ "home").as[JsObject] mustBe full.home.raw
      (json \ "logo").as[JsObject] mustBe full.logo.raw
      (json \ "references").as[JsArray] mustBe JsArray(full.references.map(_.raw))
      (json \ "resources").as[JsArray] mustBe JsArray(full.resources.map(_.raw))
      (json \ "navigation").as[JsArray] mustBe JsArray(full.navigation.map(_.raw))
      (json \ "redirections").as[JsArray] mustBe Json.arr(Json.obj("from" -> "/old", "to" -> "/new"))
      (json \ "footer").as[JsObject] mustBe Json.obj("text_content" -> "(c) oto")
      (json \ "search").as[JsObject] mustBe Json.obj("enabled" -> false)
      (json \ "banner").as[JsObject] mustBe Json.obj("text_content" -> "beta")
    }

    "round trip a documentation where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the default documentation" in {
      roundTrip(format, ApiDocumentation())
    }

    "round trip a documentation without source, footer nor banner" in {
      val back = roundTrip(format, full.copy(source = None, footer = None, banner = None))
      back.source mustBe None
      back.footer mustBe None
      back.banner mustBe None
    }

    "keep the keys of its parts that the model does not know" in {
      val home = Json.obj("text_content" -> "# Users", "custom" -> Json.obj("a" -> 1))
      roundTrip(format, full.copy(home = ApiDocumentationResource(home))).home.raw mustBe home
    }

    "read an empty object as the default documentation" in {
      readOk(format, Json.obj()) mustBe ApiDocumentation()
    }

    "ignore an enabled flag that is not a boolean" in {
      readOk(format, Json.obj("enabled" -> "false")).enabled mustBe true
    }

    "read a source that is not an object as no source" in {
      readOk(format, Json.obj("source" -> "https://docs.oto.tools")).source mustBe None
    }

    "read a home that is not an object as an empty resource" in {
      readOk(format, Json.obj("home" -> "# Users")).home mustBe ApiDocumentationResource(Json.obj())
    }

    "drop every resource when one of them is not an object" in {
      readOk(format, Json.obj("resources" -> Json.arr(Json.obj("title" -> "a"), "b"))).resources mustBe empty
    }

    "read a search that is not an object as the default search" in {
      readOk(format, Json.obj("search" -> false)).search mustBe ApiDocumentationSearch.default
    }

    "ignore tags given as a single string" in {
      readOk(format, Json.obj("tags" -> "public")).tags mustBe empty
    }

    "expose through json what the format writes" in {
      full.json mustBe format.writes(full)
    }
  }

  "ApiDocumentationResource" should {

    "read a path given as an array" in {
      ApiDocumentationResource(Json.obj("path" -> Json.arr("guides", "start"))).path mustBe Seq("guides", "start")
    }

    "read a path given as a single string" in {
      ApiDocumentationResource(Json.obj("path" -> "guides")).path mustBe Seq("guides")
    }

    "have no path by default" in {
      ApiDocumentationResource(Json.obj()).path mustBe empty
    }

    "default to markdown, no site page and the http defaults" in {
      val resource = ApiDocumentationResource(Json.obj())
      resource.contentType mustBe "text/markdown"
      resource.site_page mustBe false
      resource.httpHeaders mustBe empty
      resource.httpTimeout mustBe 30000.millis
      resource.httpFollowRedirects mustBe true
      resource.title mustBe None
      resource.description mustBe None
      resource.url mustBe None
      resource.transform mustBe None
      resource.transform_wrapper mustBe None
      resource.css_icon_class mustBe None
    }

    "read its http settings" in {
      val resource = ApiDocumentationResource(
        Json.obj(
          "http_headers"          -> Json.obj("authorization" -> "Bearer x"),
          "http_timeout"          -> 1000,
          "http_follow_redirects" -> false
        )
      )
      resource.httpHeaders mustBe Map("authorization" -> "Bearer x")
      resource.httpTimeout mustBe 1000.millis
      resource.httpFollowRedirects mustBe false
    }

    "read its contents" in {
      val resource = ApiDocumentationResource(
        Json.obj(
          "title"          -> "Start",
          "description"    -> "how to start",
          "content_type"   -> "application/json",
          "text_content"   -> "hello",
          "json_content"   -> Json.obj("a" -> 1),
          "base64_content" -> "aGVsbG8=",
          "site_page"      -> true,
          "css_icon_class" -> "fas fa-book"
        )
      )
      resource.title mustBe Some("Start")
      resource.description mustBe Some("how to start")
      resource.contentType mustBe "application/json"
      resource.text_content mustBe Some("hello")
      resource.json_content mustBe Some(Json.obj("a" -> 1))
      resource.base64_content.map(_.utf8String) mustBe Some("hello")
      resource.site_page mustBe true
      resource.css_icon_class mustBe Some("fas fa-book")
    }

    "read a null json content as no json content" in {
      ApiDocumentationResource(Json.obj("json_content" -> JsNull)).json_content mustBe None
    }

    "resolve a relative url against the url of the source" in {
      val doc = ApiDocumentation(source = Some(ApiDocumentationSource(Json.obj("url" -> "https://docs.oto.tools/users"))))
      ApiDocumentationResource(Json.obj("url" -> "./guides/start.md")).resolveUrl(doc) mustBe
        Some("https://docs.oto.tools/users/guides/start.md")
      ApiDocumentationResource(Json.obj("url" -> "../shared/logo.png")).resolveUrl(doc) mustBe
        Some("https://docs.oto.tools/shared/logo.png")
    }

    "keep a relative url as is without source" in {
      ApiDocumentationResource(Json.obj("url" -> "./start.md")).resolveUrl(ApiDocumentation()) mustBe Some("./start.md")
    }

    "keep an absolute url as is" in {
      val doc = ApiDocumentation(source = Some(ApiDocumentationSource(Json.obj("url" -> "https://docs.oto.tools"))))
      ApiDocumentationResource(Json.obj("url" -> "https://cdn.oto.tools/a.md")).resolveUrl(doc) mustBe Some("https://cdn.oto.tools/a.md")
    }

    "resolve no url without url" in {
      ApiDocumentationResource(Json.obj()).resolveUrl(full) mustBe None
    }
  }

  "ApiDocumentationSource" should {

    "have no url and the http defaults by default" in {
      val source = ApiDocumentationSource(Json.obj())
      source.url mustBe None
      source.httpHeaders mustBe empty
      source.httpTimeout mustBe 30000.millis
      source.httpFollowRedirects mustBe true
    }

    "read its http settings from short keys" in {
      val source = ApiDocumentationSource(
        Json.obj(
          "url"              -> "https://docs.oto.tools",
          "headers"          -> Json.obj("a" -> "b"),
          "timeout"          -> 2000,
          "follow_redirects" -> false
        )
      )
      source.url mustBe Some("https://docs.oto.tools")
      source.httpHeaders mustBe Map("a" -> "b")
      source.httpTimeout mustBe 2000.millis
      source.httpFollowRedirects mustBe false
    }

    "not read the http keys of a resource" in {
      ApiDocumentationSource(Json.obj("http_timeout" -> 2000, "http_headers" -> Json.obj("a" -> "b"))).httpTimeout mustBe 30000.millis
    }
  }

  "ApiDocumentationSidebar" should {

    "read its label, icon and path" in {
      val sidebar = ApiDocumentationSidebar(Json.obj("label" -> "Guides", "icon" -> icon, "path" -> "guides"))
      sidebar.label mustBe "Guides"
      sidebar.icon mustBe Some(ApiDocumentationResource(icon))
      sidebar.path mustBe Seq("guides")
    }

    "have an empty label, no icon, no path and no item by default" in {
      val sidebar = ApiDocumentationSidebar(Json.obj())
      sidebar.label mustBe ""
      sidebar.icon mustBe None
      sidebar.path mustBe empty
      sidebar.items mustBe empty
    }

    "read its items as links or categories" in {
      val link     = Json.obj("kind" -> "link", "label" -> "Start", "link" -> "/start")
      val category = Json.obj("kind" -> "category", "label" -> "More", "links" -> Json.arr(link))
      ApiDocumentationSidebar(Json.obj("items" -> Json.arr(link, category))).items mustBe Seq(
        ApiDocumentationSidebarLink(link),
        ApiDocumentationSidebarCategory(category)
      )
    }

    "read an item without kind or with an unknown kind as a link" in {
      val items = Json.arr(Json.obj("label" -> "a"), Json.obj("kind" -> "section", "label" -> "b"))
      ApiDocumentationSidebar(Json.obj("items" -> items)).items mustBe Seq(
        ApiDocumentationSidebarLink(Json.obj("label" -> "a")),
        ApiDocumentationSidebarLink(Json.obj("kind" -> "section", "label" -> "b"))
      )
    }

    "label a link without label and send it nowhere by default" in {
      val link = ApiDocumentationSidebarLink(Json.obj())
      link.label mustBe "No label"
      link.link mustBe "#"
      link.icon mustBe None
    }

    "read the links of a category" in {
      val category = ApiDocumentationSidebarCategory(Json.obj("links" -> Json.arr(Json.obj("link" -> "/a"))))
      category.label mustBe "No label"
      category.links.map(_.link) mustBe Seq("/a")
    }
  }

  "ApiDocumentation other parts" should {

    "be searchable by default" in {
      ApiDocumentationSearch.default.enabled mustBe true
      ApiDocumentationSearch(Json.obj()).enabled mustBe true
      ApiDocumentationSearch(Json.obj("enabled" -> false)).enabled mustBe false
    }

    "read a redirection" in {
      val redirection = ApiDocumentationRedirection(Json.obj("from" -> "/old", "to" -> "/new"))
      redirection.from mustBe "/old"
      redirection.to mustBe "/new"
    }

    "fail on access to a redirection without target" in {
      a[JsResultException] must be thrownBy ApiDocumentationRedirection(Json.obj("from" -> "/old")).to
    }

    "read a reference" in {
      val ref = ApiDocumentationResourceRef(Json.obj("title" -> "OpenAPI", "link" -> "/openapi.json", "icon" -> icon))
      ref.title mustBe "OpenAPI"
      ref.link mustBe "/openapi.json"
      ref.description mustBe None
      ref.icon mustBe Some(ApiDocumentationResource(icon))
    }

    "fail on access to a reference without title" in {
      a[JsResultException] must be thrownBy ApiDocumentationResourceRef(Json.obj("link" -> "/a")).title
    }
  }

  "ApiSpecification" should {

    val format = ApiSpecification._fmt

    val openapi = ApiSpecification.OpenApiSpecification(Json.obj("openapi" -> "3.0.0"), "OpenApiSpecification")

    "write a specification" in {
      format.writes(openapi) mustBe Json.obj("name" -> "OpenApiSpecification", "content" -> Json.obj("openapi" -> "3.0.0"))
    }

    "round trip an openapi specification" in {
      roundTrip(format, openapi)
    }

    "round trip an asyncapi specification" in {
      roundTrip(format, ApiSpecification.AsyncApiSpecification(Json.obj("asyncapi" -> "2.6.0"), "AsyncApiSpecification"))
    }

    "read any other name as an asyncapi specification" in {
      readOk(format, Json.obj("name" -> "graphql", "content" -> Json.obj())) mustBe
        ApiSpecification.AsyncApiSpecification(Json.obj(), "AsyncApiSpecification")
    }

    "not read a specification without content" in {
      readError(format, Json.obj("name" -> "OpenApiSpecification"))
    }

    "not read a specification without name" in {
      readError(format, Json.obj("content" -> Json.obj()))
    }
  }
}
