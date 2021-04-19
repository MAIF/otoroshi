package auth.saml

import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.ls.DOMImplementationLS

import java.io.Writer

object XMLHelper {

    def writeNode(node: Node, output: Writer): Unit = {
        val domImplLS = (node match {
            case n: Document => n.getImplementation
            case n => n.getOwnerDocument.getImplementation
        })
          .getFeature("LS", "3.0")
          .asInstanceOf[DOMImplementationLS]

        val serializer = domImplLS.createLSSerializer()

        val serializerOut = domImplLS.createLSOutput()
        serializerOut.setCharacterStream(output)

        serializer.write(node, serializerOut)
    }
}
