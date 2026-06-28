package com.chipprbots.ethereum.cli

import scala.collection.immutable.ArraySeq

import org.slf4j.LoggerFactory

//scalastyle:off
object CliLauncher:

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val arguments: Seq[String] = ArraySeq.unsafeWrapArray(args)
    CliCommands.api.map(s => logger.info(s)).parse(arguments, sys.env) match
      case Left(help) => logger.warn(help.toString)
      case Right(_)   => ()
