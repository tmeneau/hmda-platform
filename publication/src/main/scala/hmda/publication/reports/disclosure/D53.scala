package hmda.publication.reports.disclosure

import akka.NotUsed
import akka.stream.scaladsl.Source
import hmda.model.fi.lar.LoanApplicationRegister
import hmda.publication.reports._

import scala.concurrent.Future

object D53 {

  def filters(lar: LoanApplicationRegister): Boolean = {
    (lar.loan.propertyType == 1 || lar.loan.propertyType == 2) &&
      (lar.loan.purpose == 3)
  }

  def generate[ec: EC, mat: MAT, as: AS](
    larSource: Source[LoanApplicationRegister, NotUsed],
    fipsCode: Int,
    respondentId: String,
    institutionNameF: Future[String]
  ): Future[D5X] = {

    D5X.generateD5X("D53", filters, larSource, fipsCode, respondentId, institutionNameF)

  }

}
