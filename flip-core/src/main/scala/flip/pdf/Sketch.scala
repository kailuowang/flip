package flip.pdf

import cats.data.NonEmptyList
import cats.implicits._
import flip._
import flip.cmap.Cmap
import flip.conf.{PeriodicSketchConf, SketchConf}
import flip.hcounter.HCounter
import flip.measure.Measure
import flip.pdf.update.EqualSpaceCdfUpdate
import flip.plot.DensityPlot
import flip.range.{RangeM, RangeP}

import scala.language.higherKinds
import scala.util.Try

/**
  * Sketch is a probabilistic summarization data structure that quantizes and
  * stores data streams. And It is a nonparametric density estimation algorithm.
  * However, unlike other quantization algorithms such as histogram, Sketch
  * picks quantization points adaptively for incoming data. Thus, Sketch does
  * not have distortion without requiring a prior knowledge of the incoming
  * dataset.
  */
trait Sketch[A] extends DataBinningDist[A] {

  /**
    * Internal structure list of Sketch. Order: young -> old
    * */
  def structures: Structures

  def conf: SketchConf

}

trait SketchPropOps[S[_] <: Sketch[_]] extends DataBinningDistOps[S] with SketchPropLaws[S] {

  // create ops

  def sample[A](dist: S[A]): (S[A], A)

  // read ops

  /**
    * Get the number of effective elements be memorized.
    * */
  def count[A](sketch: S[A], from: A, to: A): Count

  def sum(sketch: S[_]): Count

  // update ops

  def modifyStructure[A](sketch: S[A], f: Structures => Structures): S[A]

  def narrowUpdate[A](sketch: S[A], as: List[(A, Count)]): S[A]

  def deepUpdate[A](sketch: S[A], as: List[(A, Count)]): (S[A], Option[Structure])

  //  def clear(sketch: S): S

}

trait SketchPropLaws[S[_] <: Sketch[_]] { self: SketchPropOps[S] =>

  def flatDensity: Double = (1 / Cmap.max) * (1 / (1 - Cmap.min / Cmap.max))

  def probability[A](sketch: S[A], start: A, end: A): Double = {
    val count = self.count(sketch, start, end)
    val sum = self.sum(sketch)
    val measure = sketch.measure.asInstanceOf[Measure[A]]

    if (sum > 0) count / sum else flatDensity * RangeM.bare(start, end, measure).roughLength
  }

  def rearrange[A](sketch: S[A]): S[A] = deepUpdate(sketch, Nil)._1

  def sampling[A](sketch: S[A]): DensityPlot = {
    samplingForRanges(sketch, samplingPoints(sketch))
  }

  def samplingPoints[A](sketch: S[A]): List[RangeM[A]] = {
    val cmap = youngCmap(sketch)
    val rangePs = cmap.bin
    val measure = sketch.measure.asInstanceOf[Measure[A]]
    rangePs.map(rangeP => rangeP.modifyMeasure(measure))
  }

  def samplingForRanges[A](sketch: S[A], ranges: List[RangeM[A]]): DensityPlot = {
    val rangeProbs = ranges.map(range => (range, probability(sketch, range.start, range.end)))
    val rangeDensities = rangeProbs
      .map { case (rangeM, prob) => (RangeP.forRangeM(rangeM), Try(prob / rangeM.roughLength).toOption) }
      .flatMap { case (range, densityO) => densityO.map(density => (range, density)) }
    DensityPlot.disjoint(rangeDensities)
  }

  def fastPdf[A](sketch: S[A], a: A): Double = {
    val cmap = youngCmap(sketch)
    val p = sketch.measure.asInstanceOf[Measure[A]].to(a)
    val idx = cmap(p)
    val rangePs = cmap.range(idx - 1) :: cmap.range(idx) :: cmap.range(idx + 1) :: Nil
    val rangeMs = rangePs.map(rangeP => rangeP.modifyMeasure(sketch.measure.asInstanceOf[Measure[A]]))
    val sampling = samplingForRanges(sketch, rangeMs)
    sampling.interpolation(p)
  }

  override def cdf[A](sketch: S[A], a: A): Double = {
    val cdf = cdfPlot(sketch)
    val p = sketch.measure.asInstanceOf[Measure[A]].to(a)
    cdf.interpolation(p)
  }

  def cdfPlot[A](sketch: S[A]): DensityPlot = {
    val pdf = sampling(sketch)
    pdf.cumulative
  }

  def median[A](sketch: S[A]): Double = {
    val cdf = cdfPlot(sketch)
    val icdf = cdf.inverse
    icdf.interpolation(0.5)
  }

  def cmapNo(sketch: S[_]): Int = sketch.structures.size.toInt

  def cmapSize(sketch: S[_]): Int =
    sketch.structures.head._1.size

  def counterNo(sketch: S[_]): Int =
    sketch.structures.head._2.depth

  def counterSize(sketch: S[_]): Int =
    sketch.structures.head._2.width

  def youngCmap(sketch: S[_]): Cmap =
    sketch.structures.head._1

  def domain[A](sketch: S[A]): RangeM[A] = {
    val youngCmap = self.youngCmap(sketch)
    val head = youngCmap.headRange.start
    val last = youngCmap.lastRange.end
    val measure = sketch.measure.asInstanceOf[Measure[A]]

    RangeM(measure.from(head), measure.from(last))(measure)
  }

  // construct

  def counter(conf: SketchConf, seed: Int): HCounter =
    if (conf.cmap.size > conf.counter.size) HCounter(conf.counter, -1)
    else HCounter.emptyUncompressed(conf.cmap.size)

  def structures(conf: SketchConf): Structures =
    NonEmptyList.of((Cmap(conf.cmap), counter(conf, -1)))

  def concatStructures[A](as: List[(A, Count)], measure: Measure[A], conf: SketchConf): Structures = {
    val ps = as.map { case (a, c) => (measure.to(a), c) }
    val cmap = EqualSpaceCdfUpdate.updateCmap(
      DensityPlot.empty,
      ps,
      1000,
      conf.dataKernelWindow,
      conf.boundaryCorrection,
      conf.cmap.size
    )

    NonEmptyList.of((cmap, counter(conf, -1)))
  }

}

object Sketch extends SketchPrimPropOps[Sketch] { self =>

  def apply[A](measure: Measure[A], structure: Structures)(implicit conf: SketchConf): Sketch[A] =
    SimpleSketch(measure, conf, structure)

  /**
    * @param measure  measure of Sketch
    * */
  def empty[A](implicit measure: Measure[A], conf: SketchConf): Sketch[A] = conf match {
    case conf: PeriodicSketchConf => PeriodicSketch.empty(measure, conf)
    case _ => SimpleSketch.empty(measure, conf)
  }

  def concat[A](ps: List[(A, Count)])(implicit measure: Measure[A], conf: SketchConf): Sketch[A] = conf match {
    case conf: PeriodicSketchConf => PeriodicSketch.concat(ps)(measure, conf)
    case _ => SimpleSketch.concat(ps)(measure, conf)
  }

  // mapping ops

  def modifyStructure[A](sketch: Sketch[A], f: Structures => Structures): Sketch[A] = sketch match {
    case sketch: RecurSketch[_] => RecurSketch.modifyStructure(sketch, f)
    case sketch: AdaptiveSketch[_] => AdaptiveSketch.modifyStructure(sketch, f)
    case _ => SimpleSketch.modifyStructure(sketch, f)
  }

  // syntatic sugars

  def update[A](sketch: Sketch[A], as: List[(A, Count)]): Sketch[A] = sketch match {
    case (sketch: RecurSketch[A]) => RecurSketch.update(sketch, as)
    case (sketch: AdaptiveSketch[A]) => AdaptiveSketch.update(sketch, as)
    case (sketch: SimpleSketch[A]) => SimpleSketch.update(sketch, as)
    case _ => narrowUpdate(sketch, as)
  }

  // overrides

  override def count[A](sketch: Sketch[A], start: A, end: A): Count = sketch match {
    case (sketch: AdaptiveSketch[A]) => AdaptiveSketch.count(sketch, start, end)
    case _ => super.count(sketch, start, end)
  }

  override def sum(sketch: Sketch[_]): Count = sketch match {
    case (sketch: AdaptiveSketch[_]) => AdaptiveSketch.sum(sketch)
    case _ => super.sum(sketch)
  }

  override def narrowUpdate[A](sketch: Sketch[A], as: List[(A, Count)]): Sketch[A] = sketch match {
    case (sketch: AdaptiveSketch[A]) => AdaptiveSketch.narrowUpdate(sketch, as)
    case _ => super.narrowUpdate(sketch, as)
  }

  override def rearrange[A](sketch: Sketch[A]): Sketch[A] = sketch match {
    case (sketch: AdaptiveSketch[A]) => AdaptiveSketch.rearrange(sketch)
    case _ => super.rearrange(sketch)
  }

  override def pdf[A](dist: Sketch[A], a: A): Count = fastPdf(dist, a)

}
