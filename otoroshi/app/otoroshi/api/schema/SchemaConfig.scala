package otoroshi.api.schema

import org.json4s.JValue

import java.security.MessageDigest

// Configuration
case class SchemaConfig(
                           draft: JsonSchemaDraft = JsonSchemaDraft.Draft07,
                           strictMode: Boolean = false,
                           includeDefaults: Boolean = true,
                           includeDescriptions: Boolean = true,
                           maxDepth: Int = 100,
                           cacheSize: Long = 10000,
                           cacheTtlMinutes: Long = 60,
                           namingStrategy: NamingStrategy = NamingStrategy.Identity,
                           openApiMode: Boolean = false,
                           customExtensions: Map[String, JValue] = Map.empty
                       ) {
    // Stable cache key representation
    def cacheKeySuffix: String = {
        val parts = List(
            draft.uri,
            strictMode.toString,
            includeDefaults.toString,
            includeDescriptions.toString,
            maxDepth.toString,
            namingStrategy.getClass.getName,
            openApiMode.toString
        )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.digest(parts.mkString("|").getBytes("UTF-8"))
            .map("%02x".format(_)).mkString.take(16)
    }
}
