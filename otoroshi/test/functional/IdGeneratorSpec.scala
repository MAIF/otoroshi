package functional

import org.scalatest.OptionValues
import otoroshi.security.IdGenerator

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

// pure logic. randomness quality itself cannot be asserted in a test, so what is pinned here is the
// shape and alphabet contract of the generators, plus one measurable entropy defect
class IdGeneratorSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with OptionValues {

  val hexDigits = "0123456789abcdef".toSet

  val extendedAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*$%)([]!=+-_:/;.><&"

  "IdGenerator.token" should {

    "have the requested length" in {
      IdGenerator.token(64).length mustBe 64
      IdGenerator.token(16).length mustBe 16
    }

    "only use alphanumeric characters" in {
      val alphabet = ('a' to 'z').toSet ++ ('A' to 'Z').toSet ++ ('0' to '9').toSet
      IdGenerator.token(4096).toSet.diff(alphabet) mustBe empty
    }

    "keep lowerCaseToken lower case, sized or not" in {
      val alphabet = ('a' to 'z').toSet ++ ('0' to '9').toSet
      IdGenerator.lowerCaseToken(4096).toSet.diff(alphabet) mustBe empty
      IdGenerator.lowerCaseToken.toSet.diff(alphabet) mustBe empty
    }

    "not repeat itself" in {
      (0 until 200).map(_ => IdGenerator.token(32)).toSet.size mustBe 200
    }

    "have the requested length around the size of its draw" in {
      Seq(0, 1, 7, 8, 9, 63, 128, 1000).foreach(size => IdGenerator.token(size).length mustBe size)
      IdGenerator.token(-1) mustBe ""
    }

    "keep extendedToken in its alphabet" in {
      IdGenerator.extendedToken(4096).toSet.diff(extendedAlphabet.toSet) mustBe empty
    }

    // tokens come from a single draw of bytes, and a byte is only used below the largest multiple of the alphabet
    // size: taking every byte modulo the size would make the first 256 % size characters 14 to 33% more likely
    "keep every character equally likely" in {
      Seq[(String, Int => String, Int)](
        ("alphanumeric", size => IdGenerator.token(size), 62),
        ("lower case", size => IdGenerator.lowerCaseToken(size), 36),
        ("extended", size => IdGenerator.extendedToken(size), extendedAlphabet.length)
      ).foreach { case (name, draw, alphabetSize) =>
        val chars    = (0 until 300).map(_ => draw(1000)).mkString
        val counts   = chars.groupBy(identity).view.mapValues(_.length).toMap
        val expected = chars.length.toDouble / alphabetSize
        withClue(s"$name: ") {
          counts.size mustBe alphabetSize
          counts.values.foreach(count => math.abs(count - expected) must be < expected * 0.1)
        }
      }
    }
  }

  // an id is `(timestamp - 1288834974657) << 22 | generatorId << 10 | counter`, the counter taking the 10 bits below
  // the generator id. with generator 0 the 22 low bits are the counter alone
  "IdGenerator.nextId" should {

    "give distinct ids in a row" in {
      val generator = IdGenerator(0L)
      (0 until 1000).map(_ => generator.nextId()).toSet.size mustBe 1000
    }

    // the generator id tells the ids of two instances apart: the counter must never reach its bits, or two instances
    // could produce the same id in the same millisecond
    "keep the generator id out of reach of the counter" in {
      Seq(1L, 2L, 3L, 5L, 1023L).foreach { generatorId =>
        val generator = IdGenerator(generatorId)
        val ids       = (0 until 5000).map(i => if (i % 2 == 0) generator.nextId() else generator.nextIdStr().toLong)
        ids.filterNot(id => ((id >> 10) & 0xfffL) == generatorId) mustBe empty
        ids.map(_ & 0x3ffL).distinct.size mustBe 1024
      }
    }

    // the state is updated with a CAS: a thread whose CAS fails reads the clock again, otherwise its older reading
    // compared to the newer state would look like a clock running backward, i.e. an exception from nextId and a
    // suffix from nextIdStr
    "not see the clock running backward when many threads draw ids at once" in {
      val generator = IdGenerator(0L)
      val start     = System.currentTimeMillis()
      val draws     = (0 until 8).map { _ =>
        Future {
          (0 until 25000).map(i => if (i % 2 == 0) generator.nextIdStr() else generator.nextId().toString)
        }
      }
      val ids       = Await.result(Future.sequence(draws), 60.seconds).flatten
      val end       = System.currentTimeMillis()
      ids.size mustBe 200000
      ids.filter(_.contains("-")) mustBe empty
      ids.map(_.toLong).foreach { id =>
        (id & 0x3fffffL) must be < 1024L
        (id >> 22) + 1288834974657L must be >= start
        (id >> 22) + 1288834974657L must be <= end
      }
    }
  }

  "IdGenerator.uuid" should {

    "keep its shape" in {
      val uuid = IdGenerator.uuid
      uuid.length mustBe 37
      Seq(9, 14, 19, 24).foreach(i => uuid.charAt(i) mustBe '-')
      uuid.charAt(15) mustBe '4'
      Set('8', '9', 'a', 'b') must contain(uuid.charAt(20))
    }

    "only use hex digits outside of the separators" in {
      val chars = (0 until 200).map(_ => IdGenerator.uuid).mkString.toSet - '-'
      chars.diff(hexDigits) mustBe empty
    }

    // (random.nextDouble() * 15.0).toInt only ever yields 0 to 14, so the last entry of the hex
    // alphabet was unreachable: every position carried log2(15) bits instead of 4
    "reach every hex digit" in {
      val chars = (0 until 200).map(_ => IdGenerator.uuid).mkString.toSet - '-'
      hexDigits.diff(chars) mustBe empty
    }

    "not repeat itself" in {
      (0 until 200).map(_ => IdGenerator.uuid).toSet.size mustBe 200
    }
  }
}
