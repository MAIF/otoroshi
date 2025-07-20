package otoroshi.utils

import play.api.libs.json._
import sangria.marshalling._

import scala.util.Try

object JsonMarshaller extends PlayJsonSupportLowPrioImplicits {
  implicit object PlayJsonResultMarshaller extends ResultMarshaller {
    type Node       = JsValue
    type MapBuilder = ArrayMapBuilder[Node]

    override def emptyMapNode(keys: Seq[String]): MapBuilder                                                  = new ArrayMapBuilder[Node](keys)
    override def addMapNodeElem(builder: MapBuilder, key: String, value: Node, optional: Boolean): MapBuilder =
      builder.add(key, value)

    override def mapNode(builder: MapBuilder): Node               = JsObject(builder.toSeq)
    override def mapNode(keyValues: Seq[(String, JsValue)]): Node = JsObject(keyValues)

    override def arrayNode(values: Vector[JsValue]) = JsArray(values)

    override def optionalArrayNodeValue(value: Option[JsValue]): Node = value match {
      case Some(v) => v
      case None    => nullNode
    }

    override def scalarNode(value: Any, typeName: String, info: Set[ScalarValueInfo]): Node =
      value match {
        case v: String     => JsString(v)
        case true          => JsTrue
        case false         => JsFalse
        case v: Int        => JsNumber(BigDecimal(v))
        case v: Long       => JsNumber(BigDecimal(v))
        case v: Float      => JsNumber(BigDecimal(v.toDouble))
        case v: Double     => JsNumber(BigDecimal(v))
        case v: BigInt     => JsNumber(BigDecimal(v))
        case v: BigDecimal => JsNumber(v)
        case v: JsValue    => v
        case node          => throw new IllegalStateException(s"$node is not a scalar value")
      }

    override def enumNode(value: String, typeName: String): Node = JsString(value)

    override def nullNode: Node = JsNull

    override def renderCompact(node: JsValue): String = Json.stringify(node)
    override def renderPretty(node: JsValue): String  = Json.prettyPrint(node)
  }

  implicit object PlayJsonMarshallerForType extends ResultMarshallerForType[JsValue] {
    val marshaller: PlayJsonResultMarshaller.type = PlayJsonResultMarshaller
  }

  implicit object PlayJsonInputUnmarshaller extends InputUnmarshaller[JsValue] {
    override def getRootMapValue(node: JsValue, key: String): Option[JsValue] =
      node.asInstanceOf[JsObject].value.get(key)

    override def isListNode(node: JsValue): Boolean        = node.isInstanceOf[JsArray]
    override def getListValue(node: JsValue): Seq[JsValue] = node.asInstanceOf[JsArray].value.toSeq

    override def isMapNode(node: JsValue): Boolean                        = node.isInstanceOf[JsObject]
    override def getMapValue(node: JsValue, key: String): Option[JsValue] =
      node.asInstanceOf[JsObject].value.get(key)
    override def getMapKeys(node: JsValue): Iterable[String]              = node.asInstanceOf[JsObject].keys

    override def isDefined(node: JsValue): Boolean  = node != JsNull
    override def getScalarValue(node: JsValue): Any = node match {
      case JsBoolean(b) => b
      case JsNumber(d)  => d.toBigIntExact.getOrElse(d)
      case JsString(s)  => s
      case n            => n
    }

    override def getScalaScalarValue(node: JsValue): Any = getScalarValue(node)

    override def isEnumNode(node: JsValue): Boolean = node.isInstanceOf[JsString]

    override def isScalarNode(node: JsValue): Boolean = node match {
      case _: JsValue => true
      case _          => false
    }

    override def isVariableNode(node: JsValue): Boolean = false
    override def getVariableName(node: JsValue): String = throw new IllegalArgumentException(
      "variables are not supported"
    )

    override def render(node: JsValue): String = Json.stringify(node)
  }

  private object PlayJsonToInput extends ToInput[JsValue, JsValue] {
    def toInput(value: JsValue): (JsValue, PlayJsonInputUnmarshaller.type) =
      (value, PlayJsonInputUnmarshaller)
  }

  implicit def playJsonToInput[T <: JsValue]: ToInput[T, JsValue] =
    PlayJsonToInput.asInstanceOf[ToInput[T, JsValue]]

  implicit def playJsonWriterToInput[T: Writes]: ToInput[T, JsValue] =
    (value: T) => implicitly[Writes[T]].writes(value) -> PlayJsonInputUnmarshaller

  private object PlayJsonFromInput extends FromInput[JsValue] {
    val marshaller: PlayJsonResultMarshaller.type  = PlayJsonResultMarshaller
    def fromResult(node: marshaller.Node): JsValue = node
  }

  implicit def playJsonFromInput[T <: JsValue]: FromInput[T] =
    PlayJsonFromInput.asInstanceOf[FromInput[T]]

  implicit def playJsonReaderFromInput[T: Reads]: FromInput[T] =
    new FromInput[T] {
      val marshaller: PlayJsonResultMarshaller.type = PlayJsonResultMarshaller
      def fromResult(node: marshaller.Node): T      = implicitly[Reads[T]].reads(node) match {
        case JsSuccess(v, _) => v
        case JsError(errors) =>
          val formattedErrors = errors.toVector.flatMap { case (JsPath(nodes), es) =>
            es.map(e => s"At path '${nodes.mkString(".")}': ${e.message}")
          }

          throw InputParsingError(formattedErrors)
      }
    }

  implicit object PlayJsonInputParser extends InputParser[JsValue] {
    def parse(str: String): Try[JsValue] = Try(Json.parse(str))
  }
}

trait PlayJsonSupportLowPrioImplicits {
  implicit val PlayJsonInputUnmarshallerJObject: InputUnmarshaller[JsObject] =
    JsonMarshaller.PlayJsonInputUnmarshaller.asInstanceOf[InputUnmarshaller[JsObject]]
}
