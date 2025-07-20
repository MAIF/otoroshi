package otoroshi.api.schema

import scala.reflect.runtime.universe.MethodSymbol

// Annotation mapper trait
trait AnnotationMapper {
    def extractMetadata(symbol: MethodSymbol): FieldMetadata => FieldMetadata
}
