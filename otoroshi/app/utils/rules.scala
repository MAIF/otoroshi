package utils

object rules {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import shapeless.labelled._
  import shapeless.ops.record.{LacksKey, Selector}
  import shapeless.syntax.singleton._
  import shapeless.{::, HList, HNil, LabelledGeneric, Lazy, Witness}

  import scala.annotation.implicitNotFound

  @implicitNotFound("Implicit not found: Rules type or fields are not valid")
  trait RuleValidation[Repr <: HList, Rules <: HList]

  object RuleValidation {

    implicit def validateHNil[Repr <: HList]: RuleValidation[Repr, HNil] =
      new RuleValidation[Repr, HNil] {}

    implicit def validateSingleton[Repr <: HList, K <: Symbol, V](
        implicit sel: Selector.Aux[Repr, K, V]
    ): RuleValidation[Repr, FieldType[K, Rule[V]] :: HNil] =
      new RuleValidation[Repr, FieldType[K, Rule[V]] :: HNil] {}

    implicit def validateHCons[Repr <: HList, H, R <: HList, K <: Symbol, V](
        implicit sel: Selector.Aux[Repr, K, V],
        validation: RuleValidation[Repr, R]
    ): RuleValidation[Repr, FieldType[K, Rule[V]] :: R] =
      new RuleValidation[Repr, FieldType[K, Rule[V]] :: R] {}
  }

  sealed trait Rule[T]
  case class ReadRule[T](reads: Reads[T])   extends Rule[T]
  case class OrElseRule[T](reads: Reads[T]) extends Rule[T]

  trait ReadsWithRules[T, R <: HList] {
    def withRules(rules: R): Reads[T]
  }

  trait ReadsWithRulesLowerPriority {
    implicit def readsNoRule[T](implicit reads: Reads[T]): ReadsWithRules[T, HNil] = new ReadsWithRules[T, HNil] {
      override def withRules(rules: HNil): Reads[T] = reads
    }

    implicit def readsGeneric[Repr, A, R <: HList](implicit gen: LabelledGeneric.Aux[A, Repr],
                                                   readsRepr: Lazy[ReadsWithRules[Repr, R]]): ReadsWithRules[A, R] =
      new ReadsWithRules[A, R] {
        override def withRules(rules: R): Reads[A] =
          readsRepr.value.withRules(rules).map(r => gen.from(r))
      }

  }

  object ReadsWithRules extends ReadsWithRulesLowerPriority {

    implicit def readHNil[R <: HList]: ReadsWithRules[HNil, R] = new ReadsWithRules[HNil, R] {
      override def withRules(rules: R): Reads[HNil] = Reads[HNil] { json =>
        JsSuccess(HNil)
      }
    }

    implicit def readNoRuleForHead[K <: Symbol, H, T <: HList, R <: HList](
        implicit witness: Witness.Aux[K],
        noRule: LacksKey[R, K],
        readsH: Reads[H],
        readsT: ReadsWithRules[T, R]
    ): ReadsWithRules[FieldType[K, H] :: T, R] =
      new ReadsWithRules[FieldType[K, H] :: T, R] {
        override def withRules(rules: R): Reads[FieldType[K, H] :: T] = {
          val name = witness.value.name
          val rH   = (__ \ name).read(readsH)
          (rH and readsT.withRules(rules))((a, b) => (name ->> a :: b).asInstanceOf[FieldType[K, H] :: T])
        }
      }

    implicit def readRuleForHead[K <: Symbol, H, T <: HList, R <: HList](
        implicit witness: Witness.Aux[K],
        readsH: Reads[H],
        at: Selector.Aux[R, K, Rule[H]],
        readsT: ReadsWithRules[T, R]
    ): ReadsWithRules[FieldType[K, H] :: T, R] =
      new ReadsWithRules[FieldType[K, H] :: T, R] {
        override def withRules(rules: R): Reads[FieldType[K, H] :: T] = {
          val name                    = witness.value.name
          val additionalRule: Rule[H] = at(rules)
          val rH = additionalRule match {
            case ReadRule(reads)   => (__ \ name).read[H](reads)
            case OrElseRule(reads) => (__ \ name).read[H](readsH).orElse(reads)
          }
          (rH and readsT.withRules(rules))((a, b) => (name ->> a :: b).asInstanceOf[FieldType[K, H] :: T])
        }
      }

  }

  trait JsonRead[T]

  def jsonRead[T]: JsonRead[T] = new JsonRead[T] {}

  implicit class WithRules[A](gen: JsonRead[A]) {
    def readsWithRules[R <: HList, ARepr <: HList](rules: R)(
        implicit readWithRule: ReadsWithRules[A, R],
        genA: LabelledGeneric.Aux[A, ARepr],
        validation: RuleValidation[ARepr, R]
    ): Reads[A] =
      readWithRule.withRules(rules)
  }

  def read[T](implicit reads: Reads[T]): Rule[T] = ReadRule(reads)

  def orElse[T](or: Reads[T])(implicit reads: Reads[T]): Rule[T] = OrElseRule(or)

}

//object TestApp extends App {
//  import play.api.libs.json._
//  import play.api.libs.json.Reads._
//  import play.api.libs.functional.syntax._
//  import shapeless._
//  import syntax.singleton._
//  import playjson.rules._
//
//  case class Other(name: String)
//
//  case class MonPojo(toto: String, tata: Int, other: Other)
//
//  implicit val readsOther = Json.reads[Other]
//
//
//  private val value: Reads[MonPojo] = jsonRead[MonPojo].readsWithRules(
//   ('tata ->> (min(0) keepAnd max(150))) ::
//     HNil
//  )
//  println(s"!!! ${
//   value.reads(Json.obj(
//     "toto" -> "test",
//     "other" -> Json.obj("name" -> "test"),
//     "tata" -> "test"
//   ))
//  }")
//
//  //JsSuccess(MonPojo(42),)
//}
