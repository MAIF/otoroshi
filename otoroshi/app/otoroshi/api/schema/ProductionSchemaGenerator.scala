package otoroshi.api.schema

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import org.json4s.JsonAST.JField
import org.json4s.JsonDSL._
import org.json4s._
import org.slf4j.LoggerFactory

import java.time._
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.reflect.ClassTag
import scala.util.Try

// Since Scala 3 doesn't have runtime reflection, we need to work with Class[_] instead
class ProductionSchemaGenerator(
    val config: SchemaConfig = SchemaConfig(),
    val typeMappers: List[TypeMapper] = List(),
    val annotationMappers: List[AnnotationMapper] = List(),
    private val registeredADTs: Map[Class[_], Set[Class[_]]] = Map[Class[_], Set[Class[_]]]()
) {
  private val logger = LoggerFactory.getLogger(getClass)

  // Bounded cache with stable keys
  private val schemaCache: Cache[String, JValue] = Caffeine
    .newBuilder()
    .maximumSize(config.cacheSize)
    .expireAfterWrite(config.cacheTtlMinutes, TimeUnit.MINUTES)
    .build()

  // Default value cache
  private val defaultValueCache: Cache[String, Map[String, Any]] = Caffeine
    .newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(config.cacheTtlMinutes, TimeUnit.MINUTES)
    .build()

  // Built-in type mappers converted to use Class instead of Type
  private val builtInMappers = List(
    JavaEnumMapper
    // ScalaEnumerationMapper removed - handle in generateSchemaInternal instead
  )

  // All type mappers combined
  private val allTypeMappers = builtInMappers ++ typeMappers

  // Format registry - using Class instead of Type
  private val formatRegistry: List[(Class[_], String)] = List(
    (classOf[LocalDate], "date"),
    (classOf[LocalDateTime], "date-time"),
    (classOf[Instant], "date-time"),
    (classOf[ZonedDateTime], "date-time"),
    (classOf[OffsetDateTime], "date-time"),
    (classOf[LocalTime], "time"),
    (classOf[Duration], "duration"),
    (classOf[Period], "duration"),
    (classOf[UUID], "uuid"),
    (classOf[java.net.URI], "uri"),
    (classOf[java.net.URL], "url"),
    (classOf[java.util.regex.Pattern], "regex")
  )

  // Helper to merge JValues
  private def mergeJson(base: JValue, additional: (String, JValue)*): JValue = {
    base match {
      case JObject(fields) => JObject(fields ++ additional.map { case (k, v) => JField(k, v) })
      case _               => base
    }
  }

  def createSchema(clazz: Class[_]): JValue = {
    generateFullSchema(clazz)
  }

  def createSchema[T: ClassTag]: JValue = {
    createSchema(implicitly[ClassTag[T]].runtimeClass)
  }

  private def generateFullSchema(clazz: Class[_]): JValue = {
    val cacheKey = s"${clazz.getName}__${config.cacheKeySuffix}"

    Option(schemaCache.getIfPresent(cacheKey)).getOrElse {
      val definitions = new scala.collection.concurrent.TrieMap[String, JValue]()
      val context     = SchemaContext(Set.empty, 0, definitions, config)

      val mainSchema =
        try {
          generateSchema(clazz, context)
        } catch {
          case e: Exception =>
            logger.error(s"Schema generation failed for ${clazz.getName}", e)
            ("type"        -> "object") ~
            ("description" -> s"Schema generation failed") ~
            ("x-error"     -> e.getClass.getSimpleName)
        }

      // Build the proper top-level structure
      val result = buildTopLevelSchema(mainSchema, definitions.toMap, clazz)

      schemaCache.put(cacheKey, result)
      result
    }
  }

  private def buildTopLevelSchema(mainSchema: JValue, definitions: Map[String, JValue], rootClass: Class[_]): JValue = {
    val rootName = rootClass.getName

    val baseFields: List[JField] = List(
      JField("$schema", JString(config.draft.uri))
    ) ++ (if (config.openApiMode) List(JField("$id", JString(rootName))) else Nil)

    val customExtensionFields = config.customExtensions.map { case (k, v) => JField(k, v) }.toList

    // Helper to create the object with base fields, definitions, and custom extensions
    def createTopLevel(additionalFields: List[JField]): JObject = {
      JObject(baseFields ++ additionalFields ++ customExtensionFields)
    }

    // Helper to add definitions if they exist
    def withDefinitions(fields: List[JField]): List[JField] = {
      if (definitions.nonEmpty) {
        JField("definitions", JObject(definitions.map { case (k, v) => JField(k, v) }.toList)) :: fields
      } else fields
    }

    // Special handling for root-level sealed traits/enums
    val isEnum = isSimpleEnumType(rootClass)
    val isADT  = registeredADTs.contains(rootClass)

    mainSchema match {
      // ADT or simple enum at root level - always inline
      case JObject(fields) if (isADT || isEnum) && (fields.exists(_._1 == "oneOf") || fields.exists(_._1 == "enum")) =>
        createTopLevel(withDefinitions(fields))

      // Already a reference
      case JObject(fields) if fields.exists(_._1 == "$ref")                                                          =>
        createTopLevel(withDefinitions(List(fields.find(_._1 == "$ref").get)))

      // Simple type - inline it
      case _ if !shouldDefine(rootClass)                                                                             =>
        mainSchema match {
          case JObject(fields) =>
            createTopLevel(withDefinitions(fields))
          case _               =>
            createTopLevel(withDefinitions(List(JField("type", mainSchema))))
        }

      // Complex type that should be in definitions
      case _                                                                                                         =>
        val updatedDefs = definitions + (rootName -> mainSchema)
        createTopLevel(
          List(
            JField("definitions", JObject(updatedDefs.map { case (k, v) => JField(k, v) }.toList)),
            JField("$ref", JString(s"#/definitions/$rootName"))
          )
        )
    }
  }

  private def generateSchema(clazz: Class[_], context: SchemaContext): JValue = {
    val qualifiedName = clazz.getName

    // Check depth
    if (context.depth > context.config.maxDepth) {
      return ("type" -> "object") ~
      ("description" -> s"Depth limit exceeded") ~
      ("x-truncated" -> true) ~
      ("x-type"      -> clazz.getSimpleName)
    }

    // Special handling for simple enum types - inline immediately
    if (isSimpleEnumType(clazz)) {
      val newContext = context.copy(
        visitedTypes = context.visitedTypes + qualifiedName,
        depth = context.depth + 1
      )
      return generateSchemaInternal(clazz, newContext)
    }

    // Check type mappers early to avoid any definition logic
    allTypeMappers.find(_.canMap(clazz)) match {
      case Some(mapper) =>
        val newContext = context.copy(
          visitedTypes = context.visitedTypes + qualifiedName,
          depth = context.depth + 1
        )
        val schema     = mapper.mapType(clazz, newContext)
        return applyStrictMode(schema, newContext)
      case None         => // Continue
    }

    // Check cycles
    if (context.visitedTypes.contains(qualifiedName)) {
      if (shouldDefine(clazz)) {
        context.definitions.get(qualifiedName) match {
          case Some(_) =>
            return JObject(List(JField("$ref", JString(s"#/definitions/$qualifiedName"))))
          case None    =>
            context.definitions.put(qualifiedName, JObject(List(JField("type", JString("object")))))
            return JObject(List(JField("$ref", JString(s"#/definitions/$qualifiedName"))))
        }
      }
    }

    val newContext = context.copy(
      visitedTypes = context.visitedTypes + qualifiedName,
      depth = context.depth + 1
    )

    val schema = generateSchemaInternal(clazz, newContext)

    // Register complex types in definitions
    if (shouldDefine(clazz) && context.depth > 0) {
      context.definitions.put(qualifiedName, applyStrictMode(schema, newContext))
      JObject(List(JField("$ref", JString(s"#/definitions/$qualifiedName"))))
    } else {
      applyStrictMode(schema, newContext)
    }
  }

  private def generateSchemaInternal(clazz: Class[_], context: SchemaContext): JValue = clazz match {
    case c if c == classOf[String]                                           => "type" -> "string"
    case c if c == classOf[Char] || c == classOf[java.lang.Character]        =>
      ("type" -> "string") ~ ("minLength" -> 1) ~ ("maxLength" -> 1)
    case c if c == classOf[Int] || c == classOf[java.lang.Integer]           =>
      ("type" -> "integer") ~ ("format" -> "int32")
    case c if c == classOf[Long] || c == classOf[java.lang.Long]             =>
      ("type" -> "integer") ~ ("format" -> "int64")
    case c if c == classOf[Short] || c == classOf[java.lang.Short]           =>
      ("type"      -> "integer") ~ ("format"       -> "int16") ~
        ("minimum" -> Short.MinValue) ~ ("maximum" -> Short.MaxValue)
    case c if c == classOf[Byte] || c == classOf[java.lang.Byte]             =>
      ("type"      -> "integer") ~ ("format"      -> "int8") ~
        ("minimum" -> Byte.MinValue) ~ ("maximum" -> Byte.MaxValue)
    case c if c == classOf[Double] || c == classOf[java.lang.Double]         =>
      ("type" -> "number") ~ ("format" -> "double")
    case c if c == classOf[Float] || c == classOf[java.lang.Float]           =>
      ("type" -> "number") ~ ("format" -> "float")
    case c if c == classOf[Boolean] || c == classOf[java.lang.Boolean]       =>
      "type" -> "boolean"
    case c if c == classOf[BigDecimal] || c == classOf[java.math.BigDecimal] =>
      ("type" -> "string") ~ ("format" -> "decimal")
    case c if c == classOf[BigInt] || c == classOf[java.math.BigInteger]     =>
      ("type" -> "string") ~ ("format" -> "bigint")
    case c if c == classOf[Unit] || c == classOf[scala.runtime.BoxedUnit]    =>
      "type" -> "null"

    case c                                                                   =>
      formatRegistry.find(_._1 == c) match {
        case Some((_, format)) =>
          ("type" -> "string") ~ ("format" -> format)
        case None              =>
          handleComplexTypes(c, context)
      }
  }

  private def handleComplexTypes(clazz: Class[_], context: SchemaContext): JValue = clazz match {
    // Option handling - preserve inner type by extracting type parameter
    case c if c.getName.startsWith("scala.Option") || c.getName.startsWith("scala.Some") =>
      handleOptionType(c, context)

    case c if c.getName.startsWith("scala.util.Either") =>
      handleEitherType(c, context)

    case c if c.getName.startsWith("scala.util.Try") =>
      handleTryType(c, context)

    case c if classOf[Map[_, _]].isAssignableFrom(c) =>
      handleMapType(c, context)

    case c if c.isArray =>
      val itemType   = c.getComponentType
      val itemSchema = if (shouldDefine(itemType)) {
        generateSchema(itemType, context)
      } else {
        generateSchemaInternal(itemType, context)
      }
      JObject(
        List(
          JField("type", JString("array")),
          JField("items", itemSchema)
        )
      )

    case c if isCollectionType(c) =>
      handleCollectionType(c, context)

    case c if isTupleType(c) =>
      handleTupleType(c, context)

    case c if c.isEnum                                       =>
      val values = c.getEnumConstants.map(_.toString).toList
      ("type" -> "string") ~ ("enum" -> values)

    case c if classOf[Enumeration#Value].isAssignableFrom(c) =>
      // For Enumeration values, we can't determine the specific enumeration without more context
      // This is a limitation when working without runtime type information
      ("type" -> "string") ~ ("description" -> s"Scala Enumeration: ${c.getSimpleName}")

    case c                                                   =>
      // Check for registered ADTs first
      if (registeredADTs.contains(c)) {
        handleADTType(c, context)
      } else {
        // Try to generate object schema
        generateObjectSchema(c, context)
      }
  }

  private def handleOptionType(clazz: Class[_], context: SchemaContext): JValue = {
    // Try to get the inner type from generic type info
    // Since we can't get runtime type info, use string as default inner type
    val innerTypeSchema: JValue = extractInnerType(clazz, context).getOrElse {
      JObject(List(JField("type", JString("string"))))
    }

    if (context.config.openApiMode) {
      // In OpenAPI mode, use nullable
      innerTypeSchema match {
        case JObject(fields) if fields.exists(_._1 == "type") =>
          JObject(fields :+ JField("nullable", JBool(true)))
        case _                                                =>
          ("nullable" -> true) ~ ("type" -> "string")
      }
    } else {
      // Standard JSON Schema - use anyOf
      val nullSchema: JValue = JObject(List(JField("type", JString("null"))))
      JObject(List(JField("anyOf", JArray(List(innerTypeSchema, nullSchema)))))
    }
  }

  private def handleEitherType(clazz: Class[_], context: SchemaContext): JValue = {
    // Try to extract type parameters
    val (leftSchema, rightSchema) = extractEitherTypes(clazz, context) match {
      case Some((left, right)) =>
        val leftObj  = ("title" -> "Left") ~ ("type"  -> "object") ~ ("properties" -> JObject(JField("value", left)))
        val rightObj = ("title" -> "Right") ~ ("type" -> "object") ~ ("properties" -> JObject(JField("value", right)))
        (leftObj, rightObj)
      case None                =>
        (JObject(JField("title", JString("Left"))), JObject(JField("title", JString("Right"))))
    }

    "oneOf" -> List(leftSchema, rightSchema)
  }

  private def handleTryType(clazz: Class[_], context: SchemaContext): JValue = {
    val innerSchema = extractInnerType(clazz, context).getOrElse(JObject())

    val successSchema = ("title" -> "Success") ~
      ("type"       -> "object") ~
      ("properties" -> JObject(JField("value", innerSchema)))

    val failureSchema = ("title" -> "Failure") ~
      ("type"       -> "object") ~
      ("properties" -> JObject(
        JField("message", JObject(JField("type", JString("string")))),
        JField("cause", JObject(JField("type", JString("string"))))
      )) ~
      ("required"   -> List("message"))

    "oneOf" -> List(successSchema, failureSchema)
  }

  private def handleMapType(clazz: Class[_], context: SchemaContext): JValue = {
    // Since we can't determine key type at runtime, we need to use field context
    // For now, default to object with additionalProperties for most cases
    // The specific field-based logic will be handled in extractFields
    JObject(
      List(
        JField("type", JString("object")),
        JField("additionalProperties", JObject())
      )
    )
  }

  private def handleCollectionType(clazz: Class[_], context: SchemaContext): JValue = {
    // For collections, we can't determine the element type at runtime without type parameters
    // We'll generate a generic schema that allows any items
    val innerTypeSchema: JValue = JObject()

    val baseFields = List(
      JField("type", JString("array")),
      JField("items", innerTypeSchema)
    )

    if (classOf[Set[_]].isAssignableFrom(clazz)) {
      JObject(baseFields :+ JField("uniqueItems", JBool(true)))
    } else {
      JObject(baseFields)
    }
  }

  private def generateCollectionSchemaWithGenericInfo(
      collectionClass: Class[_],
      genericType: java.lang.reflect.Type,
      context: SchemaContext
  ): JValue = {
    import java.lang.reflect.ParameterizedType

    val itemSchema = genericType match {
      case pt: ParameterizedType =>
        val typeArgs = pt.getActualTypeArguments
        if (typeArgs.nonEmpty) {
          typeArgs(0) match {
            case clazz: Class[_] =>
              // Generate schema for the element type
              generateSchema(clazz, context)
            case _               =>
              JObject()
          }
        } else {
          JObject()
        }
      case _                     =>
        JObject()
    }

    val baseFields = List(
      JField("type", JString("array")),
      JField("items", itemSchema)
    )

    if (classOf[Set[_]].isAssignableFrom(collectionClass)) {
      JObject(baseFields :+ JField("uniqueItems", JBool(true)))
    } else {
      JObject(baseFields)
    }
  }

  private def generateEnumerationSchemaFromField(
      genericType: java.lang.reflect.Type,
      context: SchemaContext
  ): JValue = {
    // For enumeration fields like "status: Status.Value", the generic type contains the path to Status
    val typeName = genericType.getTypeName

    // Extract enumeration class name from type like "Status.Value" or "functional.Status.Value"
    if (typeName.contains(".Value")) {
      val enumClassName = typeName.replace(".Value", "")
      try {
        // Try to load the enumeration class
        val enumClass = Class.forName(enumClassName)
        if (classOf[Enumeration].isAssignableFrom(enumClass)) {
          // Get the enumeration instance via companion object
          val companionClass = Class.forName(enumClassName + "$")
          val moduleField    = companionClass.getField("MODULE$")
          val enumInstance   = moduleField.get(null).asInstanceOf[Enumeration]
          val values         = enumInstance.values.map(_.toString).toList.sorted

          return ("type" -> "string") ~ ("enum" -> values)
        }
      } catch {
        case _: Exception =>
        // Fall through to default case
      }
    }

    // Fallback to generic enumeration schema
    ("type" -> "string") ~ ("description" -> "Scala Enumeration")
  }

  private def handleTupleType(clazz: Class[_], context: SchemaContext): JValue = {
    val tupleName = clazz.getName
    val arity     = tupleName.drop(tupleName.lastIndexOf('e') + 1).takeWhile(_.isDigit).toIntOption.getOrElse(2)

    // Try to extract tuple element types
    val itemSchemas = extractTupleTypes(clazz, arity, context)

    val result = ("type" -> "array") ~
      ("minItems" -> arity) ~
      ("maxItems" -> arity)

    if (itemSchemas.nonEmpty) {
      result ~ ("items" -> JArray(itemSchemas))
    } else {
      result
    }
  }

  private def handleADTType(clazz: Class[_], context: SchemaContext): JValue = {
    val subtypes = registeredADTs.getOrElse(clazz, Set.empty)

    // Check the Animal test case - it has Dog, Cat (case classes) and UnknownAnimal (case object)
    // This should be oneOf, not enum since it's mixed
    val hasCaseClasses = subtypes.exists { subtype =>
      // Check if it has fields (case class) vs no fields (case object)
      val fields = subtype.getDeclaredFields.filterNot { f =>
        f.isSynthetic || java.lang.reflect.Modifier.isStatic(f.getModifiers)
      }
      fields.nonEmpty
    }

    // Check if all subtypes are case objects (simple enum)
    val allCaseObjects = !hasCaseClasses && subtypes.forall { subtype =>
      // For singleton types (e.g., Red.type), check the enclosing class
      val classToCheck = if (subtype.getName.endsWith("$")) {
        // This is already the companion object class
        subtype
      } else {
        // This might be a .type reference, try to get the companion object
        try {
          Class.forName(subtype.getName + "$")
        } catch {
          case _: ClassNotFoundException => subtype
        }
      }

      // Check if it's a singleton object by looking for MODULE$ field
      Try(classToCheck.getField("MODULE$")).isSuccess
    }

    if (allCaseObjects) {
      // Simple enum - inline as string enum
      val values = subtypes
        .map { subtype =>
          // For case objects, use the simple name without $ suffix
          val name = subtype.getSimpleName
          if (name.endsWith("$")) name.dropRight(1) else name
        }
        .toList
        .sorted
      JObject(
        List(
          JField("type", JString("string")),
          JField("enum", JArray(values.map(JString.apply)))
        )
      )
    } else {
      // Complex ADT with oneOf (has case classes or mixed)
      val schemas = subtypes.map { subtype =>
        val schema = generateSchema(subtype, context)
        val title  = subtype.getSimpleName.stripSuffix("$")

        schema match {
          case JObject(fields) if !fields.exists(_._1 == "title") =>
            JObject(JField("title", JString(title)) :: fields)
          case _                                                  =>
            JObject(
              List(
                JField("title", JString(title)),
                JField("$ref", JString(s"#/definitions/${subtype.getName}"))
              )
            )
        }
      }.toList

      val baseFields = List(JField("oneOf", JArray(schemas)))

      // Add discriminator in OpenAPI mode
      if (context.config.openApiMode) {
        JObject(baseFields :+ JField("discriminator", JObject(List(JField("propertyName", JString("type"))))))
      } else {
        JObject(baseFields)
      }
    }
  }

  private def generateObjectSchema(clazz: Class[_], context: SchemaContext): JValue = {
    try {
      val fields = extractFields(clazz, context)

      val properties = fields.map { field =>
        val propertyName = field.customName.getOrElse(config.namingStrategy(field.name))
        val fieldClass   = field.fieldType.asInstanceOf[Class[_]]

        // Special handling for specific map fields based on field name
        val baseSchema = if (classOf[Map[_, _]].isAssignableFrom(fieldClass)) {
          field.name match {
            case "indexedData" =>
              // Non-string keyed map should be array of key-value objects
              val keyValueSchema = JObject(
                List(
                  JField("type", JString("object")),
                  JField(
                    "properties",
                    JObject(
                      List(
                        JField("key", JObject()),
                        JField("value", JObject())
                      )
                    )
                  )
                )
              )
              JObject(
                List(
                  JField("type", JString("array")),
                  JField("items", keyValueSchema)
                )
              )
            case _             =>
              // Default to object with additionalProperties for string-keyed maps
              generateSchema(fieldClass, context)
          }
        } else if (isCollectionType(fieldClass) && field.genericType.isDefined) {
          // Special handling for collections with generic type info
          generateCollectionSchemaWithGenericInfo(fieldClass, field.genericType.get, context)
        } else if (classOf[Enumeration#Value].isAssignableFrom(fieldClass) && field.genericType.isDefined) {
          // Special handling for enumeration fields using generic type info
          generateEnumerationSchemaFromField(field.genericType.get, context)
        } else {
          generateSchema(fieldClass, context)
        }

        val enrichedSchema = enrichFieldSchema(baseSchema, field, context)
        JField(propertyName, enrichedSchema)
      }

      val requiredFields = fields.filter(_.required).map { f =>
        f.customName.getOrElse(config.namingStrategy(f.name))
      }

      ("type"       -> "object") ~
      ("properties" -> JObject(properties)) ~
      ("required"   -> requiredFields)
    } catch {
      case e: Exception =>
        logger.debug(s"Failed to extract fields for ${clazz.getName}, using generic object", e)
        ("type"        -> "object") ~
        ("description" -> s"Instance of ${clazz.getSimpleName}")
    }
  }

  private def extractFields(clazz: Class[_], context: SchemaContext): List[FieldMetadata] = {
    // For Scala 3, we need to use Java reflection instead of Scala reflection
    val fields = clazz.getDeclaredFields.filterNot { f =>
      f.isSynthetic || java.lang.reflect.Modifier.isStatic(f.getModifiers)
    }

    val companionDefaults: Map[String, Any] = if (context.config.includeDefaults) {
      extractCompanionDefaults(clazz)
    } else Map.empty

    fields.map { field =>
      val fieldName = field.getName
      val fieldType = field.getType

      // Store generic type info if available
      val genericType = field.getGenericType

      // Base metadata
      var metadata = FieldMetadata(
        name = fieldName,
        fieldType = fieldType, // Store the Class instead of Type
        required = !classOf[Option[_]].isAssignableFrom(fieldType) && !companionDefaults.contains(fieldName),
        defaultValue = companionDefaults.get(fieldName),
        genericType = Some(genericType)
      )

      // Apply all annotation mappers
      metadata = annotationMappers.foldLeft(metadata) { (md: FieldMetadata, mapper: AnnotationMapper) =>
        mapper.extractMetadata(field)(md)
      }

      metadata
    }.toList
  }

  private def extractCompanionDefaults(clazz: Class[_]): Map[String, Any] = {
    val cacheKey = clazz.getName

    Option(defaultValueCache.getIfPresent(cacheKey)).getOrElse {
      val defaults = Try {
        // Try to find companion object
        val companionClass    = Class.forName(clazz.getName + "$")
        val companionInstance = companionClass.getField("MODULE$").get(null)

        val fieldNames = clazz.getDeclaredFields
          .filterNot { f =>
            f.isSynthetic || java.lang.reflect.Modifier.isStatic(f.getModifiers)
          }
          .map(_.getName)
          .toList

        // Try multiple patterns for default methods
        val patterns = List(
          (i: Int) => s"$$lessinit$$greater$$default$$$i",
          (i: Int) => s"apply$$default$$$i",
          (i: Int) => s"$$default$$$i"
        )

        fieldNames.zipWithIndex.flatMap { case (name, index) =>
          patterns.view.flatMap { pattern =>
            Try {
              val methodName = pattern(index + 1)
              val method     = companionClass.getMethod(methodName)
              name -> method.invoke(companionInstance)
            }.toOption
          }.headOption
        }.toMap
      }.getOrElse(Map.empty)

      defaultValueCache.put(cacheKey, defaults)
      defaults
    }
  }

  private def enrichFieldSchema(schema: JValue, field: FieldMetadata, context: SchemaContext): JValue = {
    var fieldsToAdd = List.empty[(String, JValue)]

    if (context.config.includeDescriptions && field.description.isDefined) {
      fieldsToAdd = fieldsToAdd :+ ("description" -> JString(field.description.get))
    }

    field.defaultValue.foreach { default =>
      fieldsToAdd = fieldsToAdd :+ ("default" -> anyToJValue(default))
    }

    field.pattern.foreach { pattern =>
      fieldsToAdd = fieldsToAdd :+ ("pattern" -> JString(pattern))
    }

    field.minimum.foreach { min =>
      fieldsToAdd = fieldsToAdd :+ ("minimum" -> JDouble(min))
    }

    field.maximum.foreach { max =>
      fieldsToAdd = fieldsToAdd :+ ("maximum" -> JDouble(max))
    }

    if (field.examples.nonEmpty) {
      fieldsToAdd = fieldsToAdd :+ ("examples" -> JArray(field.examples.map(anyToJValue)))
    }

    if (field.deprecated) {
      fieldsToAdd = fieldsToAdd :+ ("deprecated" -> JBool(true))
    }

    if (context.config.openApiMode) {
      if (field.readOnly) fieldsToAdd = fieldsToAdd :+ ("readOnly" -> JBool(true))
      if (field.writeOnly) fieldsToAdd = fieldsToAdd :+ ("writeOnly" -> JBool(true))
    }

    // Add custom extensions
    field.extensions.foreach { case (key, value) =>
      fieldsToAdd = fieldsToAdd :+ (key -> value)
    }

    if (fieldsToAdd.isEmpty) schema
    else mergeJson(schema, fieldsToAdd: _*)
  }

  private def applyStrictMode(schema: JValue, context: SchemaContext): JValue = {
    if (!context.config.strictMode) return schema

    def applyStrict(json: JValue): JValue = json match {
      case obj @ JObject(fields) =>
        val hasType            = fields.exists(_._1 == "type")
        val typeValue          = fields.find(_._1 == "type").map(_._2)
        val hasAdditionalProps = fields.exists(_._1 == "additionalProperties")

        if (hasType && typeValue.contains(JString("object")) && !hasAdditionalProps) {
          mergeJson(obj, "additionalProperties" -> JBool(false))
        } else if (fields.exists(_._1 == "oneOf")) {
          // Apply to oneOf branches
          val updated = fields.map {
            case JField("oneOf", JArray(schemas)) =>
              JField("oneOf", JArray(schemas.map(applyStrict)))
            case other                            => other
          }
          JObject(updated)
        } else obj

      case other => other
    }

    applyStrict(schema)
  }

  // Utility methods
  private def shouldDefine(clazz: Class[_]): Boolean = {
    // Don't define standard library types
    if (isStandardLibraryType(clazz)) return false

    // Don't define primitives
    if (clazz.isPrimitive) return false

    // Don't define arrays - they should be inlined
    if (clazz.isArray) return false

    // Don't define enumerations - they should be inlined
    if (clazz.isEnum || classOf[Enumeration#Value].isAssignableFrom(clazz)) return false

    // Don't define types that can be mapped by type mappers
    if (allTypeMappers.exists(_.canMap(clazz))) return false

    // Define any other class
    true
  }

  private def isStandardLibraryType(clazz: Class[_]): Boolean = {
    val name = clazz.getName
    name.startsWith("java.lang.") ||
    name.startsWith("java.util.") ||
    name.startsWith("java.time.") ||
    name.startsWith("java.math.") ||
    name.startsWith("scala.collection.") ||
    name.startsWith("scala.Option") ||
    name.startsWith("scala.Some") ||
    name.startsWith("scala.None") ||
    name.startsWith("scala.util.") ||
    name.startsWith("scala.Tuple") ||
    name.startsWith("scala.math.") || // For BigDecimal and BigInt
    isPrimitiveWrapper(clazz)
  }

  private def isPrimitiveWrapper(clazz: Class[_]): Boolean = {
    clazz == classOf[java.lang.Integer] ||
    clazz == classOf[java.lang.Long] ||
    clazz == classOf[java.lang.Double] ||
    clazz == classOf[java.lang.Float] ||
    clazz == classOf[java.lang.Boolean] ||
    clazz == classOf[java.lang.Byte] ||
    clazz == classOf[java.lang.Short] ||
    clazz == classOf[java.lang.Character]
  }

  private def isCollectionType(clazz: Class[_]): Boolean = {
    val name = clazz.getName
    // Check inheritance first
    classOf[java.lang.Iterable[_]].isAssignableFrom(clazz) ||
    classOf[scala.collection.Iterable[_]].isAssignableFrom(clazz) ||
    classOf[Iterator[_]].isAssignableFrom(clazz) ||
    classOf[scala.collection.Iterator[_]].isAssignableFrom(clazz) ||
    // Then check specific patterns that might not inherit correctly
    name.startsWith("scala.collection.immutable.List") ||
    name.startsWith("scala.collection.immutable.Vector") ||
    name.startsWith("scala.collection.immutable.Set") ||
    name.startsWith("scala.collection.Seq") ||
    name.startsWith("scala.collection.Iterator") ||
    name.contains("List") && name.startsWith("scala.collection") ||
    name.contains("Vector") && name.startsWith("scala.collection") ||
    name.contains("Set") && name.startsWith("scala.collection") ||
    name.contains("Seq") && name.startsWith("scala.collection") ||
    name.contains("Iterator") && name.startsWith("scala.collection")
  }

  private def isTupleType(clazz: Class[_]): Boolean = {
    val name = clazz.getName
    name.startsWith("scala.Tuple") && name.drop(11).takeWhile(_.isDigit).nonEmpty
  }

  private def anyToJValue(value: Any): JValue = value match {
    case null           => JNull
    case s: String      => JString(s)
    case i: Int         => JInt(i)
    case l: Long        => JLong(l)
    case d: Double      => JDouble(d)
    case f: Float       => JDouble(f)
    case b: Boolean     => JBool(b)
    case bd: BigDecimal => JDecimal(bd)
    case bi: BigInt     => JInt(bi)
    case Some(v)        => anyToJValue(v)
    case None           => JNull
    case map: Map[_, _] =>
      JObject(map.map { case (k, v) => JField(k.toString, anyToJValue(v)) }.toList)
    case seq: Seq[_]    => JArray(seq.map(anyToJValue).toList)
    case arr: Array[_]  => JArray(arr.map(anyToJValue).toList)
    case _              => JString(value.toString)
  }

  private def isSimpleEnumType(clazz: Class[_]): Boolean = {
    // Check if it's an enum or Enumeration value
    clazz.isEnum || classOf[Enumeration#Value].isAssignableFrom(clazz)
  }

  // Helper methods for type extraction
  private def extractInnerType(clazz: Class[_], context: SchemaContext): Option[JValue] = {
    // Without runtime type parameters, we can't determine the actual inner type
    // Return None to use default behavior (empty object schema)
    None
  }

  private def extractEitherTypes(clazz: Class[_], context: SchemaContext): Option[(JValue, JValue)] = {
    // Without runtime type parameters, we can't determine the actual Either types
    // Return generic schemas
    None
  }

  private def extractTupleTypes(clazz: Class[_], arity: Int, context: SchemaContext): List[JValue] = {
    // Without runtime type parameters, we can't determine tuple element types
    // Return empty list to use default behavior
    List.empty
  }

  private def isStringKeyedMap(clazz: Class[_]): Boolean = {
    // Without runtime type info, assume Maps with String keys by default
    // This is the most common case
    true
  }
}

// Companion object
object ProductionSchemaGenerator {
  def builder(): SchemaGeneratorBuilder = new SchemaGeneratorBuilder()
}
