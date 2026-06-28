package com.chipprbots.ethereum.jsonrpc.serialization

import org.json4s.JArray
import org.json4s.JBool
import org.json4s.JInt
import org.json4s.JLong
import org.json4s.JNull
import org.json4s.JString
import org.json4s.JValue

import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits

@FunctionalInterface
trait JsonEncoder[T]:
  def encodeJson(t: T): JValue
object JsonEncoder:
  def apply[T](implicit encoder: JsonEncoder[T]): JsonEncoder[T] = encoder

  def encode[T](value: T)(implicit encoder: JsonEncoder[T]): JValue = encoder.encodeJson(value)

  object Ops:
    extension [T](item: T) def jsonEncoded(implicit encoder: JsonEncoder[T]): JValue = encoder.encodeJson(item)

  given stringEncoder: JsonEncoder[String] = JString(_)
  given intEncoder: JsonEncoder[Int] = JInt(_)
  given longEncoder: JsonEncoder[Long] = JLong(_)
  given booleanEncoder: JsonEncoder[Boolean] = JBool(_)
  given jvalueEncoder: JsonEncoder[JValue] = identity
  given bigIntEncoder: JsonEncoder[BigInt] = JsonMethodsImplicits.encodeAsHex(_)

  implicit def listEncoder[T](implicit itemEncoder: JsonEncoder[T]): JsonEncoder[List[T]] = list =>
    JArray(list.map(itemEncoder.encodeJson))

  trait OptionToNull:
    implicit def optionToNullEncoder[T](implicit valueEncoder: JsonEncoder[T]): JsonEncoder[Option[T]] = {
      case Some(value) => valueEncoder.encodeJson(value)
      case None        => JNull
    }
  object OptionToNull extends OptionToNull
