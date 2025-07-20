package otoroshi.api.schema

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import org.json4s.JsonAST.JField
import org.json4s.JsonDSL._
import org.json4s._
import org.slf4j.LoggerFactory

import java.time._
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.reflect.runtime.universe._
import scala.util.Try

// Immutable production schema generator
class ProductionSchemaGenerator(
                                   val config: SchemaConfig = SchemaConfig(),
                                   val typeMappers: List[TypeMapper] = List(),
                                   val annotationMappers: List[AnnotationMapper] = List(),
                                   private val registeredADTs: Map[Type, Set[Type]] = Map[Type, Set[Type]]()
                               ) {
    private val logger = LoggerFactory.getLogger(getClass)

    // Bounded cache with stable keys
    private val schemaCache: Cache[String, JValue] = Caffeine.newBuilder()
        .maximumSize(config.cacheSize)
        .expireAfterWrite(config.cacheTtlMinutes, TimeUnit.MINUTES)
        .build()

    // Default value cache
    private val defaultValueCache: Cache[String, Map[String, Any]] = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(config.cacheTtlMinutes, TimeUnit.MINUTES)
        .build()

    // Built-in type mappers
    private val builtInMappers = List(
        ScalaEnumerationMapper,
        JavaEnumMapper
    )

    // All type mappers combined
    private val allTypeMappers = builtInMappers ++ typeMappers

    // Format registry
    private val formatRegistry: List[(Type => Boolean, String)] = List(
        (t => t <:< typeOf[LocalDate], "date"),
        (t => t <:< typeOf[LocalDateTime], "date-time"),
        (t => t <:< typeOf[Instant], "date-time"),
        (t => t <:< typeOf[ZonedDateTime], "date-time"),
        (t => t <:< typeOf[OffsetDateTime], "date-time"),
        (t => t <:< typeOf[LocalTime], "time"),
        (t => t <:< typeOf[Duration], "duration"),
        (t => t <:< typeOf[Period], "duration"),
        (t => t <:< typeOf[UUID], "uuid"),
        (t => t <:< typeOf[java.net.URI], "uri"),
        (t => t <:< typeOf[java.net.URL], "url"),
        (t => t <:< typeOf[java.util.regex.Pattern], "regex")
    )

    // Helper to merge JValues
    private def mergeJson(base: JValue, additional: (String, JValue)*): JValue = {
        base match {
            case JObject(fields) => JObject(fields ++ additional.map { case (k, v) => JField(k, v) })
            case _ => base
        }
    }

    def createSchema[T: TypeTag]: JValue = {
        val tpe = typeOf[T]
        generateFullSchema(tpe)
    }

    def createSchema(clazz: Class[_]): JValue = {
        val mirror = runtimeMirror(clazz.getClassLoader)
        val classSymbol = mirror.classSymbol(clazz)
        val tpe = classSymbol.toType
        generateFullSchema(tpe)
    }

    private def generateFullSchema(tpe: Type): JValue = {
        val cacheKey = s"${ProductionSchemaGenerator.getQualifiedTypeName(tpe)}__${config.cacheKeySuffix}"

        Option(schemaCache.getIfPresent(cacheKey)).getOrElse {
            val definitions = new scala.collection.concurrent.TrieMap[String, JValue]()
            val context = SchemaContext(Set.empty, 0, definitions, config)

            val mainSchema = try {
                generateSchema(tpe, context)
            } catch {
                case e: Exception =>
                    logger.error(s"Schema generation failed for ${sanitizeTypeName(tpe)}", e)
                    ("type" -> "object") ~
                        ("description" -> s"Schema generation failed") ~
                        ("x-error" -> e.getClass.getSimpleName)
            }

            // Build the proper top-level structure
            val result = buildTopLevelSchema(mainSchema, definitions.toMap, tpe)

            schemaCache.put(cacheKey, result)
            result
        }
    }

    private def buildTopLevelSchema(mainSchema: JValue, definitions: Map[String, JValue], rootType: Type): JValue = {
        val rootName = ProductionSchemaGenerator.getQualifiedTypeName(rootType)

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

        // Special handling for root-level sealed traits
        val isRootSealedTrait = rootType.typeSymbol.isClass && rootType.typeSymbol.asClass.isSealed
        val isSimpleEnum = isRootSealedTrait && isSimpleEnumType(rootType)

        mainSchema match {
            // Already a reference
            case JObject(fields) if fields.exists(_._1 == "$ref") =>
                createTopLevel(withDefinitions(List(fields.find(_._1 == "$ref").get)))

            // Root sealed trait with oneOf (not simple enum)
            case obj @ JObject(fields) if isRootSealedTrait && !isSimpleEnum && fields.exists(_._1 == "oneOf") =>
                val allDefs = definitions + (rootName -> obj)
                createTopLevel(List(
                    JField("definitions", JObject(allDefs.map { case (k, v) => JField(k, v) }.toList))
                ) ++ fields)

            // Simple enum sealed trait or basic type - inline it
            case _ if isSimpleEnum || !shouldDefine(rootType) =>
                mainSchema match {
                    case JObject(fields) =>
                        createTopLevel(withDefinitions(fields))
                    case _ =>
                        createTopLevel(withDefinitions(List(JField("type", mainSchema))))
                }

            // Complex type that should be in definitions
            case _ =>
                val updatedDefs = definitions + (rootName -> mainSchema)
                createTopLevel(List(
                    JField("definitions", JObject(updatedDefs.map { case (k, v) => JField(k, v) }.toList)),
                    JField("$ref", JString(s"#/definitions/$rootName"))
                ))
        }
    }

    private def generateSchema(tpe: Type, context: SchemaContext): JValue = {
        val qualifiedName = ProductionSchemaGenerator.getQualifiedTypeName(tpe)

        // Check depth
        if (context.depth > context.config.maxDepth) {
            return ("type" -> "object") ~
                ("description" -> s"Depth limit exceeded") ~
                ("x-truncated" -> true) ~
                ("x-type" -> sanitizeTypeName(tpe))
        }

        // Special handling for value classes - unwrap immediately
        if (isValueClass(tpe)) {
            return generateSchema(getValueClassInnerType(tpe), context)
        }

        // Special handling for simple enum sealed traits - inline immediately
        if (isSimpleEnumType(tpe)) {
            val newContext = context.copy(
                visitedTypes = context.visitedTypes + qualifiedName,
                depth = context.depth + 1
            )
            return handleSealedTrait(tpe, newContext)
        }

        // Check type mappers early to avoid any definition logic
        allTypeMappers.find(_.canMap(tpe)) match {
            case Some(mapper) =>
                val newContext = context.copy(
                    visitedTypes = context.visitedTypes + qualifiedName,
                    depth = context.depth + 1
                )
                val schema = mapper.mapType(tpe, newContext)
                return applyStrictMode(schema, newContext)
            case None => // Continue
        }

        // Check cycles - but only for types that should be defined
        if (context.visitedTypes.contains(qualifiedName)) {
            if (shouldDefine(tpe)) {
                context.definitions.get(qualifiedName) match {
                    case Some(_) => return "$ref" -> s"#/definitions/$qualifiedName"
                    case None =>
                        context.definitions.put(qualifiedName, "type" -> "object")
                        return "$ref" -> s"#/definitions/$qualifiedName"
                }
            }
        }

        val newContext = context.copy(
            visitedTypes = context.visitedTypes + qualifiedName,
            depth = context.depth + 1
        )

        val schema = generateSchemaInternal(tpe, newContext)

        // Register complex types in definitions
        // For root types (depth = 0), we handle this in buildTopLevelSchema
        if (shouldDefine(tpe) && context.depth > 0) {
            context.definitions.put(qualifiedName, applyStrictMode(schema, newContext))
            "$ref" -> s"#/definitions/$qualifiedName"
        } else {
            applyStrictMode(schema, newContext)
        }
    }

    private def isStandardLibraryType(tpe: Type): Boolean = {
        val typeName = tpe.typeSymbol.fullName
        // Only consider actual standard library types
        typeName.startsWith("scala.") && (
            typeName.startsWith("scala.collection.") ||
                typeName.startsWith("scala.util.") ||
                typeName.startsWith("scala.math.") ||
                typeName == "scala.Option" ||
                typeName == "scala.Some" ||
                typeName == "scala.None" ||
                typeName.startsWith("scala.util.Either") ||
                typeName.startsWith("scala.util.Try") ||
                typeName.startsWith("scala.Tuple")
            ) ||
            typeName.startsWith("java.lang.") ||
            typeName.startsWith("java.util.") ||
            typeName.startsWith("java.time.") ||
            typeName.startsWith("java.math.") ||
            isPrimitiveType(tpe)
    }

    private def isPrimitiveType(tpe: Type): Boolean = {
        tpe =:= typeOf[String] ||
            tpe =:= typeOf[Int] || tpe =:= typeOf[java.lang.Integer] ||
            tpe =:= typeOf[Long] || tpe =:= typeOf[java.lang.Long] ||
            tpe =:= typeOf[Double] || tpe =:= typeOf[java.lang.Double] ||
            tpe =:= typeOf[Float] || tpe =:= typeOf[java.lang.Float] ||
            tpe =:= typeOf[Boolean] || tpe =:= typeOf[java.lang.Boolean] ||
            tpe =:= typeOf[Byte] || tpe =:= typeOf[java.lang.Byte] ||
            tpe =:= typeOf[Short] || tpe =:= typeOf[java.lang.Short] ||
            tpe =:= typeOf[Char] ||
            tpe =:= typeOf[Unit] ||
            tpe =:= typeOf[BigDecimal] || tpe =:= typeOf[java.math.BigDecimal] ||
            tpe =:= typeOf[BigInt] || tpe =:= typeOf[java.math.BigInteger] ||
            formatRegistry.exists(_._1(tpe))
    }

    private def generateSchemaInternal(tpe: Type, context: SchemaContext): JValue = tpe match {
        case t if t =:= typeOf[String] => "type" -> "string"
        case t if t =:= typeOf[Char] => ("type" -> "string") ~ ("minLength" -> 1) ~ ("maxLength" -> 1)
        case t if t =:= typeOf[Int] || t =:= typeOf[java.lang.Integer] =>
            ("type" -> "integer") ~ ("format" -> "int32")
        case t if t =:= typeOf[Long] || t =:= typeOf[java.lang.Long] =>
            ("type" -> "integer") ~ ("format" -> "int64")
        case t if t =:= typeOf[Short] || t =:= typeOf[java.lang.Short] =>
            ("type" -> "integer") ~ ("format" -> "int16") ~
                ("minimum" -> Short.MinValue) ~ ("maximum" -> Short.MaxValue)
        case t if t =:= typeOf[Byte] || t =:= typeOf[java.lang.Byte] =>
            ("type" -> "integer") ~ ("format" -> "int8") ~
                ("minimum" -> Byte.MinValue) ~ ("maximum" -> Byte.MaxValue)
        case t if t =:= typeOf[Double] || t =:= typeOf[java.lang.Double] =>
            ("type" -> "number") ~ ("format" -> "double")
        case t if t =:= typeOf[Float] || t =:= typeOf[java.lang.Float] =>
            ("type" -> "number") ~ ("format" -> "float")
        case t if t =:= typeOf[Boolean] || t =:= typeOf[java.lang.Boolean] =>
            "type" -> "boolean"
        case t if t =:= typeOf[BigDecimal] || t =:= typeOf[java.math.BigDecimal] =>
            ("type" -> "string") ~ ("format" -> "decimal")
        case t if t =:= typeOf[BigInt] || t =:= typeOf[java.math.BigInteger] =>
            ("type" -> "string") ~ ("format" -> "bigint")
        case t if t =:= typeOf[Unit] =>
            "type" -> "null"

        case t =>
            formatRegistry.find(_._1(t)) match {
                case Some((_, format)) =>
                    ("type" -> "string") ~ ("format" -> format)
                case None =>
                    handleComplexTypes(t, context)
            }
    }

    private def handleComplexTypes(tpe: Type, context: SchemaContext): JValue = tpe match {
        // Option handling
        case t if t <:< typeOf[Option[_]] =>
            val innerType = t.typeArgs.head
            val innerSchema = generateSchema(innerType, context)

            if (context.config.openApiMode && context.config.draft == JsonSchemaDraft.OpenApi30) {
                mergeJson(innerSchema, "nullable" -> JBool(true))
            } else {
                "anyOf" -> List(innerSchema, JObject(JField("type", JString("null"))))
            }

        case t if t.erasure =:= typeOf[Either[_, _]].erasure =>
            handleEitherType(t, context)

        case t if t <:< typeOf[Try[_]] =>
            val innerType = t.typeArgs.head
            val successSchema = mergeJson(generateSchema(innerType, context), "title" -> JString("Success"))
            val failureSchema = ("type" -> "object") ~
                ("title" -> "Failure") ~
                ("properties" -> JObject(
                    JField("message", JObject(JField("type", JString("string")))),
                    JField("cause", JObject(JField("type", JString("string"))))
                )) ~
                ("required" -> List("message"))

            "oneOf" -> List(successSchema, failureSchema)

        case t if t <:< typeOf[Map[_, _]] =>
            handleMapType(t, context)

        case t if t <:< typeOf[Array[_]] =>
            val innerType = t.typeArgs.headOption.getOrElse(typeOf[Any])
            ("type" -> "array") ~ ("items" -> generateSchema(innerType, context))

        case t if isCollectionType(t) =>
            val innerType = t.typeArgs.headOption.getOrElse(typeOf[Any])
            val arraySchema = ("type" -> "array") ~ ("items" -> generateSchema(innerType, context))
            if (t <:< typeOf[Set[_]]) arraySchema ~ ("uniqueItems" -> true) else arraySchema

        case t if isTupleType(t) =>
            val items = t.typeArgs.map(generateSchema(_, context))
            ("type" -> "array") ~
                ("prefixItems" -> items) ~ // JSON Schema 2020-12 style
                ("items" -> false) ~ // No additional items
                ("minItems" -> items.length) ~
                ("maxItems" -> items.length)

        case t if t.typeSymbol.isClass && t.typeSymbol.asClass.isSealed =>
            handleSealedTrait(t, context)

        case t if t.typeSymbol.isClass =>
            val classSymbol = t.typeSymbol.asClass
            if (classSymbol.isCaseClass) {
                generateObjectSchema(t, context)
            } else {
                ("type" -> "object") ~
                    ("description" -> s"Instance of ${sanitizeTypeName(t)}")
            }

        case t if t.dealias != t =>
            generateSchema(t.dealias, context)

        case t =>
            ("type" -> "object") ~
                ("description" -> s"Unknown type: ${sanitizeTypeName(t)}")
    }

    private def handleEitherType(tpe: Type, context: SchemaContext): JValue = {
        val leftType = tpe.typeArgs.head
        val rightType = tpe.typeArgs(1)

        val leftSchema = mergeJson(
            generateSchema(leftType, context),
            "title" -> JString(s"Left[${sanitizeTypeName(leftType)}]")
        )
        val rightSchema = mergeJson(
            generateSchema(rightType, context),
            "title" -> JString(s"Right[${sanitizeTypeName(rightType)}]")
        )

        val schemas = List(leftSchema, rightSchema)
        val oneOf = "oneOf" -> schemas

        // Add discriminator for OpenAPI
        if (context.config.openApiMode) {
            oneOf ~ ("discriminator" -> JObject(
                JField("propertyName", JString("_type")),
                JField("mapping", JObject(
                    JField("left", JString(s"#/definitions/${ProductionSchemaGenerator.getQualifiedTypeName(leftType)}")),
                    JField("right", JString(s"#/definitions/${ProductionSchemaGenerator.getQualifiedTypeName(rightType)}"))
                ))
            ))
        } else oneOf
    }

    private def handleMapType(tpe: Type, context: SchemaContext): JValue = {
        val keyType = tpe.typeArgs.head
        val valueType = tpe.typeArgs(1)

        if (keyType =:= typeOf[String]) {
            ("type" -> "object") ~
                ("additionalProperties" -> generateSchema(valueType, context))
        } else {
            // Non-string keys need array representation
            ("type" -> "array") ~
                ("items" -> (
                    ("type" -> "object") ~
                        ("properties" -> JObject(
                            JField("key", generateSchema(keyType, context)),
                            JField("value", generateSchema(valueType, context))
                        )) ~
                        ("required" -> List("key", "value"))
                    ))
        }
    }

    private def handleSealedTrait(tpe: Type, context: SchemaContext): JValue = {
        val subtypes = registeredADTs.getOrElse(tpe, {
            try {
                val subclasses = tpe.typeSymbol.asClass.knownDirectSubclasses
                if (subclasses.isEmpty) {
                    logger.warn(s"No subtypes found for sealed trait ${sanitizeTypeName(tpe)}")
                }
                subclasses.map(_.asType.toType)
            } catch {
                case e: Exception =>
                    logger.error(s"Failed to discover subtypes for ${sanitizeTypeName(tpe)}", e)
                    Set.empty[Type]
            }
        })

        if (subtypes.isEmpty) {
            return ("type" -> "object") ~
                ("description" -> s"Sealed trait ${sanitizeTypeName(tpe)}") ~
                ("x-warning" -> "No subtypes discovered")
        }

        if (subtypes.forall(_.typeSymbol.isModuleClass)) {
            // All case objects - enum
            ("type" -> "string") ~
                ("enum" -> subtypes.map(_.typeSymbol.name.toString.stripSuffix("$")).toList.sorted)
        } else {
            // Mixed types - oneOf
            val schemas = subtypes.map { subType =>
                mergeJson(
                    generateSchema(subType, context),
                    "title" -> JString(subType.typeSymbol.name.toString.stripSuffix("$"))
                )
            }.toList

            val oneOf = "oneOf" -> schemas

            // Add discriminator for OpenAPI
            if (context.config.openApiMode) {
                oneOf ~ ("discriminator" -> JObject(JField("propertyName", JString("type"))))
            } else oneOf
        }
    }

    private def generateObjectSchema(tpe: Type, context: SchemaContext): JValue = {
        val fields = extractFields(tpe, context)

        val properties = fields.map { field =>
            val propertyName = field.customName.getOrElse(context.config.namingStrategy(field.name))
            val baseSchema = generateSchema(field.fieldType, context)
            val enrichedSchema = enrichFieldSchema(baseSchema, field, context)
            JField(propertyName, enrichedSchema)
        }

        val requiredFields = fields.filter(_.required).map { f =>
            f.customName.getOrElse(context.config.namingStrategy(f.name))
        }

        ("type" -> "object") ~
            ("properties" -> JObject(properties)) ~
            ("required" -> requiredFields)
    }

    private def extractFields(tpe: Type, context: SchemaContext): List[FieldMetadata] = {
        val caseAccessors = tpe.decls.collect {
            case m: MethodSymbol if m.isCaseAccessor => m
        }.toList

        val companionDefaults: Map[String, Any] = if (context.config.includeDefaults) {
            extractCompanionDefaults(tpe)
        } else Map.empty

        caseAccessors.map { method =>
            val fieldName = method.name.toString
            val fieldType = method.returnType

            // Base metadata
            var metadata = FieldMetadata(
                name = fieldName,
                fieldType = fieldType,
                required = !(fieldType <:< typeOf[Option[_]]) && !companionDefaults.contains(fieldName),
                defaultValue = companionDefaults.get(fieldName)
            )

            // Apply all annotation mappers
            metadata = annotationMappers.foldLeft(metadata) { (md, mapper) =>
                mapper.extractMetadata(method)(md)
            }

            // Built-in annotation extraction as a fallback
            if (metadata.description.isEmpty) {
                metadata = extractBuiltInAnnotations(method, metadata)
            }

            metadata
        }
    }

    private def extractBuiltInAnnotations(method: MethodSymbol, metadata: FieldMetadata): FieldMetadata = {
        try {
            method.annotations.foldLeft(metadata) { (md, ann) =>
                val annType = ann.tree.tpe.typeSymbol.name.toString
                annType match {
                    case "deprecated" => md.copy(deprecated = true)
                    case _ => md
                }
            }
        } catch {
            case _: Exception => metadata
        }
    }

    private def extractCompanionDefaults(tpe: Type): Map[String, Any] = {
        val cacheKey = ProductionSchemaGenerator.getQualifiedTypeName(tpe)

        Option(defaultValueCache.getIfPresent(cacheKey)).getOrElse {
            val defaults = Try {
                val companionSymbol = tpe.typeSymbol.companion
                if (companionSymbol == NoSymbol) Map.empty[String, Any]
                else {
                    val mirror = runtimeMirror(getClass.getClassLoader)
                    val companionInstance = mirror.reflectModule(companionSymbol.asModule).instance

                    val fieldNames = tpe.decls.collect {
                        case m: MethodSymbol if m.isCaseAccessor => m.name.toString
                    }.toList

                    // Try multiple patterns
                    val patterns = List(
                        (i: Int) => s"$$lessinit$$greater$$default$$$i",
                        (i: Int) => s"_$i",
                        (i: Int) => s"$$default$$$i",
                        (i: Int) => s"apply$$default$$$i"
                    )

                    fieldNames.zipWithIndex.flatMap { case (name, index) =>
                        patterns.view.flatMap { pattern =>
                            Try {
                                val methodName = pattern(index + 1)
                                val method = companionInstance.getClass.getMethod(methodName)
                                name -> method.invoke(companionInstance)
                            }.toOption
                        }.headOption
                    }.toMap
                }
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
            case obj@JObject(fields) =>
                val hasType = fields.exists(_._1 == "type")
                val typeValue = fields.find(_._1 == "type").map(_._2)
                val hasAdditionalProps = fields.exists(_._1 == "additionalProperties")

                if (hasType && typeValue.contains(JString("object")) && !hasAdditionalProps) {
                    mergeJson(obj, "additionalProperties" -> JBool(false))
                } else if (fields.exists(_._1 == "oneOf")) {
                    // Apply to oneOf branches
                    val updated = fields.map {
                        case JField("oneOf", JArray(schemas)) =>
                            JField("oneOf", JArray(schemas.map(applyStrict)))
                        case other => other
                    }
                    JObject(updated)
                } else obj

            case other => other
        }

        applyStrict(schema)
    }

    // Utility methods
    private def shouldDefine(tpe: Type): Boolean = {
        // Don't define standard library types
        if (isStandardLibraryType(tpe)) return false

        // Don't define simple enum sealed traits
        if (isSimpleEnumType(tpe)) return false

        // Don't define value classes - they should be unwrapped
        if (isValueClass(tpe)) return false

        // Don't define enumerations - they should be inlined
        if (tpe <:< typeOf[Enumeration#Value]) return false

        // Don't define types that can be mapped by type mappers
        if (allTypeMappers.exists(_.canMap(tpe))) return false

        // Define any case class or sealed trait that's not from the standard library
        tpe.typeSymbol.isClass && (
            tpe.typeSymbol.asClass.isCaseClass ||
                (tpe.typeSymbol.asClass.isSealed && !isSimpleEnumType(tpe))
            )
    }

    private def isSimpleEnumType(tpe: Type): Boolean = {
        if (!tpe.typeSymbol.isClass || !tpe.typeSymbol.asClass.isSealed) return false

        val subtypes = registeredADTs.getOrElse(tpe, {
            try {
                val subclasses = tpe.typeSymbol.asClass.knownDirectSubclasses
                subclasses.map(_.asType.toType)
            } catch {
                case _: Exception => Set.empty[Type]
            }
        })

        subtypes.nonEmpty && subtypes.forall(_.typeSymbol.isModuleClass)
    }

    private def isValueClass(tpe: Type): Boolean = {
        tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.isDerivedValueClass
    }

    private def getValueClassInnerType(tpe: Type): Type = {
        tpe.members.find(m => m.isMethod && m.name.toString == "value")
            .map(_.asMethod.returnType)
            .getOrElse(typeOf[Any])
    }

    private def isCollectionType(tpe: Type): Boolean = {
        tpe <:< typeOf[Iterable[_]] ||
            tpe <:< typeOf[java.lang.Iterable[_]] ||
            tpe <:< typeOf[Iterator[_]]
    }

    private def isTupleType(tpe: Type): Boolean = {
        val name = tpe.typeSymbol.fullName
        name.startsWith("scala.Tuple") && name.drop(11).forall(_.isDigit)
    }

    private def sanitizeTypeName(tpe: Type): String = {
        tpe.typeSymbol.name.toString.stripSuffix("$")
    }

    private def anyToJValue(value: Any): JValue = value match {
        case null => JNull
        case s: String => JString(s)
        case i: Int => JInt(i)
        case l: Long => JLong(l)
        case d: Double => JDouble(d)
        case f: Float => JDouble(f)
        case b: Boolean => JBool(b)
        case bd: BigDecimal => JDecimal(bd)
        case bi: BigInt => JInt(bi)
        case Some(v) => anyToJValue(v)
        case None => JNull
        case map: Map[_, _] =>
            JObject(map.map { case (k, v) => JField(k.toString, anyToJValue(v)) }.toList)
        case seq: Seq[_] => JArray(seq.map(anyToJValue).toList)
        case arr: Array[_] => JArray(arr.map(anyToJValue).toList)
        case _ => JString(value.toString)
    }
}

// Companion object
object ProductionSchemaGenerator {
    def builder(): SchemaGeneratorBuilder = new SchemaGeneratorBuilder()

    // Stable type name generation for use as keys
    private def getQualifiedTypeName(tpe: Type): String = {
        val base = tpe.typeSymbol.fullName
        if (tpe.typeArgs.isEmpty) {
            base
        } else {
            val typeParams = tpe.typeArgs.map(getQualifiedTypeName).mkString("[", ",", "]")
            s"$base$typeParams"
        }
    }
}