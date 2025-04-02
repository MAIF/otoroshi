package utils

import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue}
import play.api.mvc.RequestHeader

object EntityFiltering {

  private def filterPrefix: Option[String] = "filter.".some

  private def filterEntity(_entity: JsValue, request: RequestHeader): Option[JsValue] = {
    _entity match {
      case arr @ JsArray(_) => {
        val prefix     = filterPrefix
        val filters    = request.queryString
          .mapValues(_.last)
          .collect {
            case v if prefix.isEmpty                                  => v
            case v if prefix.isDefined && v._1.startsWith(prefix.get) => (v._1.replace(prefix.get, ""), v._2)
          }
          .filterNot(a => a._1 == "page" || a._1 == "pageSize" || a._1 == "fields")
        val filtered   = request
          .getQueryString("filtered")
          .map(
            _.split(",")
              .map(r => {
                val field = r.split(":")
                (field.head, field.last)
              })
              .toSeq
          )
          .getOrElse(Seq.empty[(String, String)])
        val hasFilters = filters.nonEmpty

        val reducedItems = if (hasFilters) {
          val items: Seq[JsValue] = arr.value.filter { elem =>
            filters.forall {
              case (key, value) if key.startsWith("$") && key.contains(".") => {
                elem.atPath(key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
              case (key, value) if key.contains(".")                        => {
                elem.at(key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
              case (key, value) if key.contains("/")                        => {
                elem.atPointer(key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
              case (key, value)                                             => {
                (elem \ key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _               => false
                }
              }
            }
          }
          items
        } else {
          arr.value
        }

        val filteredItems = if (filtered.nonEmpty) {
          val items: Seq[JsValue] = reducedItems.filter { elem =>
            filtered.forall { case (key, value) =>
              JsonOperationsHelper.getValueAtPath(key.toLowerCase(), elem)._2.asOpt[JsValue] match {
                case Some(v) =>
                  v match {
                    case JsString(v)              => v.toLowerCase().indexOf(value) != -1
                    case JsBoolean(v)             => v == value.toBoolean
                    case JsNumber(v)              => v.toDouble == value.replaceAll("[^0-9]", "").toDouble
                    case JsArray(values)          => values.contains(JsString(value))
                    case JsObject(v) if v.isEmpty =>
                      JsonOperationsHelper.getValueAtPath(key, elem)._2.asOpt[JsValue] match {
                        case Some(v) =>
                          v match {
                            case JsString(v)     => v.toLowerCase().indexOf(value) != -1
                            case JsBoolean(v)    => v == value.toBoolean
                            case JsNumber(v)     => v.toDouble == value.replaceAll("[^0-9]", "").toDouble
                            case JsArray(values) => values.contains(JsString(value))
                            case _               => false
                          }
                        case _       => false
                      }
                    case _                        => false
                  }
                case _       =>
                  false
              }
            }
          }
          items
        } else {
          reducedItems
        }
        JsArray(filteredItems).some
      }
      case _                => _entity.some
    }
  }

  private def sortEntity(_entity: JsValue, request: RequestHeader): Option[JsValue] = {
    _entity match {
      case arr @ JsArray(_) => {
        val sorted    = request
          .getQueryString("sorted")
          .map(
            _.split(",")
              .map(r => {
                val field = r.split(":")
                (field.head, field.last.toBoolean)
              })
              .toSeq
          )
          .getOrElse(Seq.empty[(String, Boolean)])
        val hasSorted = sorted.nonEmpty
        if (hasSorted) {
          JsArray(sorted.foldLeft(arr.value) {
            case (sortedArray, sort) => {

              val out = if (sortedArray.isEmpty) {
                sortedArray
              } else {
                val isANumber = JsonOperationsHelper.getValueAtPath(sort._1, sortedArray.head)._2 match {
                  case JsNumber(_) => true
                  case _ => false
                }

                if (isANumber) {
                  sortedArray
                    .sortBy(r => {
                      JsonOperationsHelper.getValueAtPath(sort._1, r)._2 match {
                        case JsNumber(value) => value.toInt
                        case value => value.asOpt[Int].getOrElse(0)
                      }
                    })(
                      Ordering[Int].reverse
                    )
                } else {
                  sortedArray
                    .sortBy(r => String.valueOf(JsonOperationsHelper.getValueAtPath(sort._1, r)._2))(
                      Ordering[String].reverse
                    )
                }
              }

              if (sort._2) {
                out.reverse
              } else {
                out
              }
            }
          }).some
        } else {
          arr.some
        }
      }
      case _                => _entity.some
    }
  }

  case class PaginatedContent(pages: Int = -1, content: JsValue)

  private def paginateEntity(_entity: JsValue, request: RequestHeader): Option[PaginatedContent] = {
    _entity match {
      case arr @ JsArray(_) => {
        val paginationPage: Int     =
          request.queryString
            .get("page")
            .flatMap(_.headOption)
            .map(_.toInt)
            .getOrElse(1)
        val paginationPageSize: Int =
          request.queryString
            .get("pageSize")
            .flatMap(_.headOption)
            .map(_.toInt)
            .getOrElse(Int.MaxValue)
        val paginationPosition      = (paginationPage - 1) * paginationPageSize

        val content = arr.value.slice(paginationPosition, paginationPosition + paginationPageSize)
        PaginatedContent(
          pages = Math.ceil(arr.value.size.toFloat / paginationPageSize).toInt,
          content = JsArray(content)
        ).some
      }
      case _                =>
        PaginatedContent(
          content = _entity
        ).some
    }
  }

  private def projectedEntity(_entity: PaginatedContent, request: RequestHeader): Option[PaginatedContent] = {
    val fields    = request.getQueryString("fields").map(_.split(",").toSeq).getOrElse(Seq.empty[String])
    val hasFields = fields.nonEmpty
    if (hasFields) {
      val content = _entity.content match {
        case arr @ JsArray(_)  =>
          JsArray(arr.value.map { item =>
            JsonOperationsHelper.filterJson(item.asObject, fields)
          })
        case obj @ JsObject(_) => JsonOperationsHelper.filterJson(obj, fields)
        case _                 => return _entity.some
      }
      _entity.copy(content = content).some
    } else {
      _entity.some
    }
  }

  def process(entity: JsValue, request: RequestHeader): PaginatedContent = {
    (for {
      filtered  <- filterEntity(entity, request)
      sorted    <- sortEntity(filtered, request)
      paginated <- paginateEntity(sorted, request)
      projected <- projectedEntity(paginated, request)
    } yield projected).get
  }
}
