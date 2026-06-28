package com.chipprbots.ethereum.utils

import java.util.Map.Entry

import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.scaladsl.model.headers.HttpOrigin

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigValue

object ConfigUtils:

  def parseCorsAllowedOrigins(config: TypesafeConfig, key: String): HttpOriginMatcher =
    Try(parseMultipleOrigins(config.getStringList(key).asScala.toSeq)).recoverWith { case _ =>
      Try(parseSingleOrigin(config.getString(key)))
    }.get

  def parseMultipleOrigins(origins: Seq[String]): HttpOriginMatcher = HttpOriginMatcher(origins.map(HttpOrigin(_))*)

  def parseSingleOrigin(origin: String): HttpOriginMatcher = origin match
    case "*" => HttpOriginMatcher.*
    case s   => HttpOriginMatcher.Default(HttpOrigin(s) :: Nil)

  def getOptionalValue[V](config: TypesafeConfig, getter: TypesafeConfig => String => V, path: String): Option[V] =
    if config.hasPath(path) then Some(getter(config)(path))
    else None

  def keys(config: TypesafeConfig): Set[String] = config
    .entrySet()
    .asScala
    .toSet
    .flatMap((entry: Entry[String, ConfigValue]) => entry.getKey.split('.').headOption)
