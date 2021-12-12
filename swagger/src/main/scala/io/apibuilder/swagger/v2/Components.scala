package io.apibuilder.swagger.v2

import cats.data.ValidatedNec
import cats.implicits._
import io.apibuilder.spec.v0.models._
import io.apibuilder.swagger.SchemaType
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.{models => swagger}
import lib.Text

import scala.jdk.CollectionConverters._

case class ReferenceType[T](ref: String, value: T)

object ComponentsValidator extends OpenAPIParseHelpers {
  def validate(api: OpenAPI): ValidatedNec[String, Components] = {
    Option(api.getComponents) match {
      case None => Components(
        models = Nil
      ).validNec
      case Some(c) => {
        validateSchemas(c).map { models =>
          Components(
            models = models,
          )
        }
      }
    }
  }

  private[this] def validateSchemas(components: swagger.Components): ValidatedNec[String, Seq[ReferenceType[Model]]] = {
    Option(components.getSchemas).map(_.asScala).getOrElse(Nil).map { case (ref, schema) =>
      validateSchema(ref, schema)
    }.toList.traverse(identity)
  }

  private[this] def validateSchema[T](name: String, schema: swagger.media.Schema[T]): ValidatedNec[String, ReferenceType[Model]] = {
    (
      validateSchemaDescription(schema),
      validateSchemaFields(schema),
    ).mapN { case (description, fields) =>
      println(s"REFERENCE: #/components/schemas/$name")
      println(s"FIELDS: ${fields}")
      ReferenceType(
        s"#/components/schemas/$name",
        Model(
          name = name,
          plural = Text.pluralize(name),
          description = description,
          fields = fields,
          deprecation = deprecation(schema.getDeprecated),
          attributes = Nil, // not supported
          interfaces = Nil  // not supported
        )
      )
    }
  }

  private[this] def validateSchemaDescription[T](schema: swagger.media.Schema[T]): ValidatedNec[String, Option[String]] = {
    trimmedString(schema.getDescription).validNec
  }

  private[this] def validateSchemaFields[T](schema: swagger.media.Schema[T]): ValidatedNec[String, Seq[Field]] = {
    Option(schema.getDiscriminator) match {
      case Some(disc) => s"API Builder does not yet support schema discriminators ('$disc')'".invalidNec
      case None => {
        trimmedString(schema.getType) match {
          case Some(t) if t == "object" => validateSchemaFieldsObject(schema)
          case Some(t) => s"API Builder does not yet support components/schema of type '$t".invalidNec
          case None => {
            "TODO".invalidNec
          }
        }
      }
    }
  }

  private[this] def validateSchemaFieldsObject[T](schema: swagger.media.Schema[T]): ValidatedNec[String, Seq[Field]] = {
    val required = Option(schema.getRequired).map(_.asScala).getOrElse(Nil)
    val properties = Option(schema.getProperties).map(_.asScala).getOrElse(Map.empty[String, swagger.media.Schema[T]])
    properties.keys.toList.sorted.map { name =>
      val props = properties(name)
      validateField(name, props, required.contains(name))
    }.traverse(identity)
  }

  private[this] def validateField(
    name: String,
    props: swagger.media.Schema[_],
    required: Boolean,
  ): ValidatedNec[String, Field] = {
    (
      validateType(props),
      validateDefault(props.getDefault),
    ).mapN { case (typ, default) =>
      Field(
        name = name,
        `type` = typ,
        required = required,
        description = trimmedString(props.getDescription),
        deprecation = deprecation(props.getDeprecated),
        default = default,
        minimum = optionalLong(props.getMinimum).orElse {
          optionalLong(props.getMinItems).orElse {
            optionalLong(props.getMinLength)
          }
        },
        maximum = optionalLong(props.getMaximum).orElse {
          optionalLong(props.getMaxItems).orElse {
            optionalLong(props.getMaxLength)
          }
        },
        example = None, // TODO: Add support
        attributes = Nil, // Not supported
        annotations = Nil, // Not supported
      )
    }
  }

  private[this] def validateType(props: swagger.media.Schema[_]): ValidatedNec[String, String] = {
    SchemaType.validateFromSwagger(props.getType, Option(props.getFormat))
  }

  private[this] def validateDefault(value: Any): ValidatedNec[String, Option[String]] = {
    Option(value).map(_.toString).validNec
  }

}

case class Components(models: Seq[ReferenceType[Model]]) {
  private[this] val modelsByRef: Map[String, Model] = models.map { m => m.ref -> m.value }.toMap

  def findModel(ref: String): Option[Model] = modelsByRef.get(ref)
}
