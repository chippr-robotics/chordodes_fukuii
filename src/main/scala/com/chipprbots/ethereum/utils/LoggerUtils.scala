package com.chipprbots.ethereum.utils

object LoggingUtils:

  def getClassName(cls: Class[?]): String = cls.getName.split("\\.").last

  def getClassName(o: Object): String = getClassName(o.getClass)
