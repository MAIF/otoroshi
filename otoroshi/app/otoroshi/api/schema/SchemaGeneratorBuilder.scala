package otoroshi.api.schema

// Builder for immutable configuration - migrated to use Class instead of Type
class SchemaGeneratorBuilder {
  private var config            = SchemaConfig()
  private val typeMappers       = scala.collection.mutable.ListBuffer[TypeMapper]()
  private val annotationMappers = scala.collection.mutable.ListBuffer[AnnotationMapper]()
  private val registeredADTs    = scala.collection.mutable.Map[Class[_], Set[Class[_]]]()

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

  def registerADT(rootClass: Class[_], subtypes: Set[Class[_]]): SchemaGeneratorBuilder = {
    registeredADTs(rootClass) = subtypes
    this
  }

  def registerADT(rootClass: Class[_], subtypes: Class[_]*): SchemaGeneratorBuilder = {
    registerADT(rootClass, subtypes.toSet)
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
