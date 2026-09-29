package functional

import java.io.DataInputStream

// the match of the expression language is split in parts so that the JIT compiles them: HotSpot never compiles a
// method of more than 8000 bytes of bytecode (-XX:+DontCompileHugeMethods, the default), it interprets it forever. A
// case added to a part that gets over the limit fails here, instead of slowing down every expression without a sign
class ExpressionLanguageMatchSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private val hugeMethodLimit = 8000

  // the bytecode length of every method of a class, read from its class file
  private def codeLengths(className: String): Map[String, Int] = {
    val in = new DataInputStream(getClass.getResourceAsStream("/" + className.replace('.', '/') + ".class"))
    def skip(n: Int): Unit = in.readFully(new Array[Byte](n))
    try {
      skip(8) // magic, minor and major versions
      val constants = in.readUnsignedShort()
      val utf8      = scala.collection.mutable.Map[Int, String]()
      var index     = 1
      while (index < constants) {
        in.readUnsignedByte() match {
          case 1                         => utf8(index) = in.readUTF()
          case 5 | 6                     => skip(8); index += 1 // a long or a double takes two entries
          case 3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => skip(4)
          case 15                        => skip(3)
          case 7 | 8 | 16 | 19 | 20      => skip(2)
          case tag                       => fail(s"unknown constant pool tag $tag in $className")
        }
        index += 1
      }
      skip(6)                              // access flags, this class, super class
      skip(2 * in.readUnsignedShort())     // interfaces
      def attributes(): Unit = (0 until in.readUnsignedShort()).foreach { _ => skip(2); skip(in.readInt()) }
      (0 until in.readUnsignedShort()).foreach { _ => skip(6); attributes() } // fields
      (0 until in.readUnsignedShort()).flatMap { _ =>
        skip(2)
        val name = utf8(in.readUnsignedShort())
        skip(2)
        (0 until in.readUnsignedShort()).flatMap { _ =>
          val attribute = utf8(in.readUnsignedShort())
          val length    = in.readInt()
          if (attribute == "Code") {
            skip(4) // max stack, max locals
            val code = in.readInt()
            skip(length - 8)
            Some(name -> code)
          } else {
            skip(length)
            None
          }
        }
      }.toMap
    } finally in.close()
  }

  "The match of the expression language" should {

    "be split in parts" in {
      codeLengths("otoroshi.el.GlobalExpressionLanguage$").keys.filter(_.startsWith("part")) must not be empty
    }

    "have no method the JIT does not compile" in {
      codeLengths("otoroshi.el.GlobalExpressionLanguage$").filter(_._2 > hugeMethodLimit) mustBe empty
    }
  }
}
