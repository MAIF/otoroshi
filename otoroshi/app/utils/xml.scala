package otoroshi.utils.xml

import play.api.libs.json._

import scala.util._
import scala.xml._

// vendoring of project https://github.com/3tty0n/play-json-xml for scala 2.13 compat
// licence Apache V2
object Xml {
  def toJson(xml: NodeSeq): JsValue = {

    def isEmpty(node: Node): Boolean = node.child.isEmpty
    def isLeaf(node: Node) = {
      def descendant(n: Node): List[Node] = n match {
        case g: Group => g.nodes.toList.flatMap(x => x :: descendant(x))
        case _        => n.child.toList.flatMap { x => x :: descendant(x) }
      }
      !descendant(node).exists(_.isInstanceOf[Elem])
    }

    def isArray(nodeNames: Seq[String])  = nodeNames.size != 1 && nodeNames.toList.distinct.size == 1
    def directChildren(n: Node): NodeSeq = n.child.filter(c => c.isInstanceOf[Elem])
    def nameOf(n: Node)                  = (if (n.prefix ne null) n.prefix + ":" else "") + n.label
    def buildAttrs(n: Node)              = n.attributes.map((a: MetaData) => (a.key, XValue(a.value.text))).toList

    sealed abstract class XElem                                             extends Product with Serializable
    case class XValue(value: String)                                        extends XElem
    case class XLeaf(value: (String, XElem), attrs: List[(String, XValue)]) extends XElem
    case class XNode(fields: List[(String, XElem)])                         extends XElem
    case class XArray(elems: List[XElem])                                   extends XElem

    def toJsValue(x: XElem, flatten: Boolean = false): JsValue = x match {
      case x @ XValue(_)               => xValueToJsValue(x)
      case XLeaf((name, value), attrs) =>
        (value, attrs) match {
          case (_, Nil)            => toJsValue(value)
          case (XValue(""), xs)    => JsObject(mkFields(xs))
          case (XValue(_), _ :: _) =>
            val values = JsObject(mkFields(("value" → value) +: attrs))
            if (flatten) {
              values
            } else {
              JsObject(Seq(name → values))
            }
          case (_, _)              => JsObject(Seq(name -> toJsValue(value)))
        }
      case XNode(xs)                   => JsObject(mkFields(xs))
      case XArray(elems)               =>
        elems match {
          case (_: XValue) :: _ => JsArray(elems.map(y => toJsValue(y)))
          case _                => JsArray(elems.map(y => toJsValue(y, flatten = true)))
        }
    }

    def xValueToJsValue(xValue: XValue): JsValue = {
      (Try(xValue.value.toInt), Try(xValue.value.toBoolean)) match {
        case (Success(v), Failure(_)) => JsNumber(v)
        case (Failure(_), Success(v)) => JsBoolean(v)
        case _                        => JsString(xValue.value)
      }
    }

    def mkFields(xs: List[(String, XElem)]): List[(String, JsValue)] =
      xs.flatMap { case (name, value) =>
        (value, toJsValue(value)) match {
          case (XLeaf(_, _ :: _), o: JsObject) =>
            if (o.fields.map(_._1).contains(name)) o.fields
            else Seq(name -> o)
          case (_, json)                       => Seq(name -> json)
        }
      }

    def buildNodes(xml: NodeSeq): List[XElem] = xml match {
      case n: Node        =>
        if (isEmpty(n)) XLeaf((nameOf(n), XValue("")), buildAttrs(n)) :: Nil
        else if (isLeaf(n)) XLeaf((nameOf(n), XValue(n.text)), buildAttrs(n)) :: Nil
        else {
          val children = directChildren(n)
            .map(cn => (nameOf(cn), buildNodes(cn).head))
            .groupBy(_._1)
            .map({ case (k, v) => (k, if (v.length > 1) XArray(v.toList.map(_._2)) else v.head._2) })
            .toList
          XNode(buildAttrs(n) ::: children) :: Nil
        }
      case nodes: NodeSeq =>
        val allLabels = nodes.map(_.label)
        if (isArray(allLabels)) {
          val arr = XArray(nodes.toList.flatMap { n =>
            if (isLeaf(n) && n.attributes.length == 0) XValue(n.text) :: Nil
            else buildNodes(n)
          })
          XLeaf((allLabels.head, arr), Nil) :: Nil
        } else nodes.toList.flatMap(buildNodes)
    }

    buildNodes(xml) match {
      case List(x @ XLeaf(_, _ :: _)) => toJsValue(x)
      case List(x)                    => play.api.libs.json.Json.obj(nameOf(xml.head) -> toJsValue(x))
      case x                          => JsArray(x.map(y => toJsValue(y, flatten = true)))
    }

  }

  def toXml(json: JsValue): NodeSeq = {
    def getAttributes(attrs: List[(String, JsValue)]): MetaData = {
      if (attrs.isEmpty)
        xml.Null
      else {
        val lastAttribute =
          new UnprefixedAttribute(attrs.last._1.replaceAll("@", ""), attrs.last._2.toString(), xml.Null)
        attrs
          .slice(0, attrs.length - 1)
          .reverse
          .foldLeft(lastAttribute) { case (attr, attribute) =>
            new UnprefixedAttribute(attribute._1.replaceAll("@", ""), attribute._2.toString(), attr)
          }
      }
    }

    def nestedToXml(name: String, json: JsValue): NodeSeq = json match {
      case JsObject(fields) =>
        val children   =
          fields.toList.filter(p => !p._1.startsWith("@") && p._1 != "$").flatMap { case (n, v) => nestedToXml(n, v) }
        val attributes = fields.toList.filter(p => p._1.startsWith("@"))
        val value      = fields.toList.find(p => p._1 == "$")

        if (value.isEmpty)
          XmlNode(name, children, getAttributes(attributes))
        else
          XmlElemWithAttributes(name, value.get._2.toString(), getAttributes(attributes))
      case JsArray(xs)      =>
        XmlNode(name, xs.flatMap { v => toXml(v) }, xml.Null)
      case JsNumber(v)      =>
        XmlElem(name, v.toString())
      case JsBoolean(v)     =>
        XmlElem(name, v.toString)
      case JsString(v)      =>
        XmlElem(name, v)
      case JsNull           =>
        XmlElem(name, "null")
    }

    json match {
      case JsObject(fields) =>
        fields.toList.flatMap { case (n, v) => nestedToXml(n, v) }
      case x                =>
        nestedToXml("root", x)
    }
  }

  private[this] case class XmlNode(name: String, children: Seq[Node], atrributes: MetaData)
      extends Elem(null, name, attributes1 = atrributes, TopScope, true, children: _*)

  private[this] case class XmlElem(name: String, value: String)
      extends Elem(null, name, xml.Null, TopScope, true, Text(value))

  private[this] case class XmlElemWithAttributes(name: String, value: String, atrributes: MetaData)
      extends Elem(null, name, attributes1 = atrributes, TopScope, true, Text(value))
}
