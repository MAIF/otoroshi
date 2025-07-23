package otoroshi.api.schema

import java.lang.reflect.Field

// Annotation mapper trait - migrated to use Java reflection
trait AnnotationMapper {
    def extractMetadata(field: Field): FieldMetadata => FieldMetadata
}