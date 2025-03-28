package otoroshi.next.controllers

import otoroshi.cluster.StatsView
import otoroshi.utils.syntax.implicits.BetterSyntax

object Stats {
  def avgDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    (if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      stats.map(extractor).:+(value).fold(0.0)(_ + _) / (stats.size + 1)
    }).applyOn {
      case Double.NaN                    => 0.0
      case Double.NegativeInfinity       => 0.0
      case Double.PositiveInfinity       => 0.0
      case v if v.toString == "NaN"      => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v                             => v
    }
  }

  def sumDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      stats.map(extractor).:+(value).fold(0.0)(_ + _)
    }.applyOn {
      case Double.NaN                    => 0.0
      case Double.NegativeInfinity       => 0.0
      case Double.PositiveInfinity       => 0.0
      case v if v.toString == "NaN"      => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v                             => v
    }
  }
}
