package otoroshi.security

import otoroshi.env.Env

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.util.Try

class IdGenerator(generatorId: Long) {
  def nextId(): Long          = IdGenerator.nextId(generatorId)
  def nextIdSafe(): Try[Long] = Try(nextId())
  def nextIdStr(): String     = IdGenerator.nextIdStr(generatorId)
}

object IdGenerator {

  private val LOWER_CASE_CHARACTERS =
    "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray.map(_.toString)
  private val CHARACTERS            =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray.map(_.toString)
  private val EXTENDED_CHARACTERS   =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*$%)([]!=+-_:/;.><&".toCharArray.map(_.toString)
  private val INIT_STRING           = for (i <- 0 to 15) yield Integer.toHexString(i)

  // apikey secrets and the like are drawn from here, so the generator must not be predictable from
  // previously observed output. one DRBG instance per thread: the default SecureRandom of linux and macos,
  // NativePRNG, reads through a single lock shared by all its instances, and uuid is called on every request.
  // a DRBG instance is seeded from the JDK's shared DRBG seeder, not from /dev/random
  private val secureRandom: ThreadLocal[SecureRandom] =
    ThreadLocal.withInitial(() => Try(SecureRandom.getInstance("DRBG")).getOrElse(new SecureRandom()))

  private val minus         = 1288834974657L
  // timestamp and counter of the last id, packed as `timestamp << 12 | counter` and updated with a CAS instead of
  // a lock taken at least twice per request. the clock is read inside the loop: when another thread moves the
  // state first, the CAS fails and the clock is read again, so only a clock really running backward is seen as such
  private val lastIdState   = new AtomicLong(-1L)
  private val duplicates    = new AtomicLong(-0L)

  def apply(generatorId: Long) = new IdGenerator(generatorId)

  def nextId(generatorId: Long): Long = {
    if (generatorId > 1024L) throw new RuntimeException("Generator id can't be larger than 1024")
    var timestamp = 0L
    var next      = 0L
    var done      = false
    while (!done) {
      val prev = lastIdState.get()
      timestamp = System.currentTimeMillis
      if (timestamp < (prev >> 12)) throw new RuntimeException("Clock is running backward. Sorry :-(")
      next = (timestamp << 12) | (((prev & 4095L) + 1L) & 4095L)
      done = lastIdState.compareAndSet(prev, next)
    }
    ((timestamp - minus) << 22L) | (generatorId << 10L) | (next & 4095L)
  }

  def nextIdStr(generatorId: Long): String = {
    if (generatorId > 1024L) throw new RuntimeException("Generator id can't be larger than 1024")
    var timestamp = 0L
    var prev      = 0L
    var next      = 0L
    var done      = false
    while (!done) {
      prev = lastIdState.get()
      timestamp = System.currentTimeMillis
      next = (timestamp << 12) | (((prev & 4095L) + 1L) & 4095L)
      done = lastIdState.compareAndSet(prev, next)
    }
    val append = if (timestamp < (prev >> 12)) s"-${duplicates.incrementAndGet() + generatorId}" else ""
    (((timestamp - minus) << 22L) | (generatorId << 10L) | (next & 4095L)).toString + append
  }

  // the 32 non fixed characters are one nibble each, so a uuid costs a single draw of 16 bytes
  // instead of one draw per character. the previous `(nextDouble * 15).toInt` also never reached the
  // last hex digit, leaving log2(15) bits per character instead of 4
  def uuid: String = {
    val bytes = new Array[Byte](16)
    secureRandom.get().nextBytes(bytes)
    val builder = new java.lang.StringBuilder(37)
    var nibble  = 0
    var index   = 0
    while (index <= 36) {
      index match {
        case 9 | 14 | 19 | 24 => builder.append('-')
        case 15               => builder.append('4')
        case _                =>
          val byte  = bytes(nibble / 2) & 0xff
          val value = if (nibble % 2 == 0) byte >>> 4 else byte & 0x0f
          nibble += 1
          builder.append(INIT_STRING(if (index == 20) (value & 0x03) | 8 else value))
      }
      index += 1
    }
    builder.toString
  }

  // one draw for the whole token instead of one per character. a byte is used only below the largest multiple of
  // the alphabet size, so every character stays equally likely, as with nextInt(characters.size)
  def token(characters: Array[String], size: Int): String = {
    val random  = secureRandom.get()
    val length  = math.max(size, 0)
    val builder = new java.lang.StringBuilder(length)
    if (characters.isEmpty || characters.length > 256) {
      while (builder.length < length) builder.append(characters(random.nextInt(characters.length)))
    } else {
      val limit = 256 - (256 % characters.length)
      val bytes = new Array[Byte](length + length / 4 + 8)
      var index = bytes.length
      while (builder.length < length) {
        if (index == bytes.length) {
          random.nextBytes(bytes)
          index = 0
        }
        val value = bytes(index) & 0xff
        index += 1
        if (value < limit) builder.append(characters(value % characters.length))
      }
    }
    builder.toString
  }

  def token(size: Int): String                                = token(CHARACTERS, size)
  def token: String                                           = token(64)
  def lowerCaseToken(size: Int): String                       = token(LOWER_CASE_CHARACTERS, size)
  def lowerCaseToken: String                                  = token(LOWER_CASE_CHARACTERS, 64)
  def extendedToken(size: Int): String                        = token(EXTENDED_CHARACTERS, size)
  def extendedToken: String                                   = token(EXTENDED_CHARACTERS, 64)
  def namedToken(prefix: String, size: Int, env: Env): String = namedToken(prefix, size, env.env)
  def namedToken(prefix: String, size: Int, env: String): String = {
    env match {
      case "prod" => s"${prefix}_${token(size)}"
      case _      => s"${prefix}_${env}_${token(size)}"
    }
  }
  def namedId(prefix: String, env: Env): String               = namedId(prefix, env.env)
  def namedId(prefix: String, env: String): String = {
    env match {
      case "prod" => s"${prefix}_${UUID.randomUUID().toString}"
      case _      => s"${prefix}_${env}_${UUID.randomUUID().toString}"
    }
  }
}
