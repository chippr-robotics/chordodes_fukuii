package com.chipprbots.ethereum.metrics

object MetricsUtils:

  def mkNameWithPrefix(prefix: String)(name: String): String =
    val metricsPrefix = prefix + "."
    if name.startsWith(metricsPrefix) then name else metricsPrefix + name
