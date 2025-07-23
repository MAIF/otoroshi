package otoroshi.api.schema

// Naming strategies - using function type instead of trait
//type NamingStrategy = String => String
//
//object NamingStrategy {
//    val Identity: NamingStrategy = identity
//
//    val SnakeCase: NamingStrategy = name =>
//        name.replaceAll("([A-Z])", "_$1").toLowerCase.stripPrefix("_")
//
//    val KebabCase: NamingStrategy = name =>
//        name.replaceAll("([A-Z])", "-$1").toLowerCase.stripPrefix("-")
//
//    val LowerCamelCase: NamingStrategy = name =>
//        s"${name.head.toLower}${name.tail}"
//}


// Naming strategies
sealed trait NamingStrategy {
    def apply(name: String): String
}

object NamingStrategy {
    case object Identity extends NamingStrategy {
        def apply(name: String): String = name
    }

    case object SnakeCase extends NamingStrategy {
        def apply(name: String): String = name.replaceAll("([A-Z])", "_$1").toLowerCase.stripPrefix("_")
    }

    case object KebabCase extends NamingStrategy {
        def apply(name: String): String = name.replaceAll("([A-Z])", "-$1").toLowerCase.stripPrefix("-")
    }

    case object LowerCamelCase extends NamingStrategy {
        def apply(name: String): String = s"${name.head.toLower}${name.tail}"
    }
}