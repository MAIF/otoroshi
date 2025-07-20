package otoroshi.api.schema

import scala.reflect.runtime.universe._

// Builder for immutable configuration
class SchemaGeneratorBuilder {
    private var config = SchemaConfig()
    private val typeMappers = scala.collection.mutable.ListBuffer[TypeMapper]()
    private val annotationMappers = scala.collection.mutable.ListBuffer[AnnotationMapper]()
    private val registeredADTs = scala.collection.mutable.Map[Type, Set[Type]]()

    def withConfig(c: SchemaConfig): SchemaGeneratorBuilder = {
        config = c
        this
    }

    def withTypeMapper(mapper: TypeMapper): SchemaGeneratorBuilder = {
        typeMappers += mapper
        this
    }

    def withAnnotationMapper(mapper: AnnotationMapper): SchemaGeneratorBuilder = {
        annotationMappers += mapper
        this
    }

    def registerADT(rootType: Type, subtypes: Set[Type]): SchemaGeneratorBuilder = {
        registeredADTs(rootType) = subtypes
        this
    }

    def registerADT(rootType: Type, subtypes: Type*): SchemaGeneratorBuilder = {
        registerADT(rootType, subtypes.toSet)
    }

    def build(): ProductionSchemaGenerator = {
        new ProductionSchemaGenerator(
            config,
            typeMappers.toList,
            annotationMappers.toList,
            registeredADTs.toMap
        )
    }
}