package otoroshi.events.pulsar

import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.client.impl.schema.SchemaInfoImpl
import org.apache.pulsar.common.schema.{SchemaInfo, SchemaType}
import play.api.libs.json.{Json, Reads, Writes}

import java.nio.charset.StandardCharsets
import scala.annotation.implicitNotFound

/**
 * Local implementation of Pulsar's Play JSON schema support.
 *
 * This code is maintained locally rather than imported from pulsar4s-play-json library because:
 * 1. The library pulls in com.typesafe.play:play-json:2.10.x as a transitive dependency,
 *    which conflicts with the Play 3.0 migration (using org.playframework:play-json:3.x)
 * 2. The implementation is trivial (~20 lines) and hasn't changed in years
 * 3. Avoiding the dependency reduces the attack surface and eliminates version conflicts
 * 4. All com.typesafe dependencies are being removed as part of the Akka -> Pekko migration
 *
 * Original implementation based on: com.clever-cloud.pulsar4s:pulsar4s-play-json
 *
 * If pulsar4s eventually releases a version compatible with Play 3.0/org.playframework,
 * the library dependency could be reconsidered.
 */
object PulsarPlayJsonSchema {

  @implicitNotFound(
    "No Writes or Reads for type ${T} found. Bring an implicit Writes[T] and Reads[T] instance in scope"
  )
  implicit def playSchema[T](using w: Writes[T], r: Reads[T]): Schema[T] = new Schema[T] {
    override def clone(): Schema[T]            = this
    override def encode(t: T): Array[Byte]     = Json.stringify(Json.toJson(t)(using w)).getBytes(StandardCharsets.UTF_8)
    override def decode(bytes: Array[Byte]): T = Json.parse(bytes).as[T]
    override def getSchemaInfo: SchemaInfo = {
      SchemaInfoImpl
        .builder()
        .name("play-json-schema") // Generic name since we don't need reflection
        .`type`(SchemaType.BYTES)
        .schema(Array.empty[Byte])
        .build()
    }
  }
}
