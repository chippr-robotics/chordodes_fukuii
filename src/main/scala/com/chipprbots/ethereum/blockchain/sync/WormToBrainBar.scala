package com.chipprbots.ethereum.blockchain.sync

object WormToBrainBar:

  enum WormState:
    case Queued, Active, Complete

  private val worm = "🪱"
  private val brain = "🧠"

  /** Known total. At 100% renders COMPLETE regardless of bar position. */
  def renderKnown(progress: Double, slots: Int = 20): String =
    val p = progress.max(0.0).min(1.0)
    if p >= 1.0 then s"$worm[COMPLETE ✓]$brain"
    else
      val wormAt = (p * (slots - 1)).toInt
      val sb = new StringBuilder("[")
      var i = 0
      while i < slots do
        if i < wormAt then sb.append('=')
        else if i == wormAt then sb.append(worm)
        else sb.append('.')
        i += 1
      sb.append(s"]$brain")
      s"${sb.toString} ${(p * 100).toInt}%"

  /** Unknown total: renders a labelled state instead of a bar. */
  def renderUnknown(state: WormState): String =
    import WormState.*
    state match
      case Queued   => s"$worm[QUEUED]$brain"
      case Active   => s"$worm[ACTIVE]$brain"
      case Complete => s"$worm[COMPLETE ✓]$brain"
