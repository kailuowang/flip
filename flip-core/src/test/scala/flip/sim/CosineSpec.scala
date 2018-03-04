package flip.sim

import flip.{Histogram, HistogramConf, NumericDist}
import flip.pdf.Dist
import org.specs2.mutable._
import org.specs2.ScalaCheck
import flip.measure.syntax._

class CosineSpec extends Specification with ScalaCheck {

  "Cosine" should {

    "basic 1" in {
      val normal1 = Dist.normal(0.0, 1)
      val normal2 = Dist.normal(0.0, 1)
      val expect = 1.0

      val sampling = normal1.uniformSampling(-3.0, 3.0, 100)
      val cosineSim = Cosine(sampling, normal2)
      val cosine = cosineSim.simForSampling(sampling, normal2)
      val cosineDensity = cosineSim.simDensityForSampling(sampling, normal2)

      if(cosine ~= expect) ok
      else ko(s"Cosine similarity $cosine is not $expect. ")
    }

    "basic 2" in {
      implicit val histoConf: HistogramConf = HistogramConf(
        binNo = 100, start = -3.0, end = 3.0,
        counterSize = 100
      )
      val underlying = NumericDist.normal(0.0, 1)
      val (_, datas) = underlying.samples(100)
      val histo = Histogram.empty[Double]
      val utdHisto = histo.update(datas: _*)

      val underlyingSmp = underlying.uniformSampling(-3.0, 3.0, 1000)
      val cos = flip.sim.syntax.Cosine(underlyingSmp, utdHisto)

      if(cos > 1) ko(s"Theoretically, cosine similarity cannot be greater then 1. cos: $cos")
      else if(cos < 0) ko(s"Theoretically, cosine similarity cannot be smaller then 1. cos: $cos")
      else ok
    }

  }

}