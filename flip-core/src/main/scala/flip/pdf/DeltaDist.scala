package flip.pdf

import flip.conf.SmoothDistConf
import flip.measure.Measure
import flip.rand._
import flip.range._

/**
  * Dirac delta function.
  *
  * @see <a href="https://en.wikipedia.org/wiki/Dirac_delta_function">Dirac delta function - Wikipedia</a>
  */
case class DeltaDist[A](measure: Measure[A], conf: SmoothDistConf, pole: A, rng: IRng = IRng(0)) extends NumericDist[A]

trait DeltaDistOps extends NumericDistOps[DeltaDist] {

  override def pdf[A](dist: DeltaDist[A], a: A): Double = {
    val pole = dist.measure.to(dist.pole)

    if (a != pole) 0.0 else Double.PositiveInfinity
  }

  override def cdf[A](dist: DeltaDist[A], a: A): Double = {
    val p = dist.measure.to(a)
    val pole = dist.measure.to(dist.pole)

    if (p >= pole) 1.0 else 0.0
  }

  def icdf[A](dist: DeltaDist[A], p: Double): A = dist.pole

}

object DeltaDist extends DeltaDistOps {

  def modifyRng[A](dist: DeltaDist[A], f: IRng => IRng): DeltaDist[A] =
    DeltaDist(dist.measure, dist.conf, dist.pole, f(dist.rng))

}
