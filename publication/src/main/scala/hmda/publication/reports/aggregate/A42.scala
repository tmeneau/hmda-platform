package hmda.publication.reports.aggregate

import akka.NotUsed
import akka.stream.scaladsl.Source
import hmda.model.fi.lar.LoanApplicationRegister
import hmda.model.publication.reports.ValueDisposition
import hmda.model.publication.reports.EthnicityEnum._
import hmda.model.publication.reports.GenderEnum.{ Female, JointGender, Male }
import hmda.model.publication.reports.ApplicantIncomeEnum._
import hmda.model.publication.reports.MinorityStatusEnum._
import hmda.model.publication.reports.RaceEnum._
import hmda.publication.reports._
import hmda.publication.reports.util.DispositionType._
import hmda.publication.reports.util.EthnicityUtil.filterEthnicity
import hmda.publication.reports.util.GenderUtil.filterGender
import hmda.publication.reports.util.MinorityStatusUtil.filterMinorityStatus
import hmda.publication.reports.util.RaceUtil.filterRace
import hmda.publication.reports.util.ReportUtil._

import scala.concurrent.Future
import spray.json._

object A42 {
  val dispositions = List(ApplicationReceived, LoansOriginated, ApprovedButNotAccepted,
    ApplicationsDenied, ApplicationsWithdrawn, ClosedForIncompleteness)

  def reportFilters(lar: LoanApplicationRegister): Boolean = {
    lar.loan.loanType == 1 &&
      (lar.loan.propertyType == 1 || lar.loan.propertyType == 2) &&
      (lar.loan.purpose == 1)
  }

  def generate[ec: EC, mat: MAT, as: AS](
    larSource: Source[LoanApplicationRegister, NotUsed],
    fipsCode: Int
  ): Future[JsValue] = {

    val lars = larSource.filter(reportFilters)
    val incomeIntervals = larsByIncomeInterval(lars.filter(lar => lar.applicant.income != "NA"), calculateMedianIncomeIntervals(fipsCode))
    val msa = msaReport(fipsCode.toString).toJsonFormat
    val reportDate = formattedCurrentDate
    val yearF = calculateYear(lars)

    for {
      year <- yearF
      e1 <- dispositionsOutput(filterEthnicity(lars, HispanicOrLatino))
      e1g <- dispositionsByGender(filterEthnicity(lars, HispanicOrLatino))
      e2 <- dispositionsOutput(filterEthnicity(lars, NotHispanicOrLatino))
      e2g <- dispositionsByGender(filterEthnicity(lars, NotHispanicOrLatino))
      e3 <- dispositionsOutput(filterEthnicity(lars, JointEthnicity))
      e3g <- dispositionsByGender(filterEthnicity(lars, JointEthnicity))
      e4 <- dispositionsOutput(filterEthnicity(lars, NotAvailable))
      e4g <- dispositionsByGender(filterEthnicity(lars, NotAvailable))

      r1 <- dispositionsOutput(filterRace(lars, AmericanIndianOrAlaskaNative))
      r1g <- dispositionsByGender(filterRace(lars, AmericanIndianOrAlaskaNative))
      r2 <- dispositionsOutput(filterRace(lars, Asian))
      r2g <- dispositionsByGender(filterRace(lars, Asian))
      r3 <- dispositionsOutput(filterRace(lars, BlackOrAfricanAmerican))
      r3g <- dispositionsByGender(filterRace(lars, BlackOrAfricanAmerican))
      r4 <- dispositionsOutput(filterRace(lars, HawaiianOrPacific))
      r4g <- dispositionsByGender(filterRace(lars, HawaiianOrPacific))
      r5 <- dispositionsOutput(filterRace(lars, White))
      r5g <- dispositionsByGender(filterRace(lars, White))
      r6 <- dispositionsOutput(filterRace(lars, TwoOrMoreMinority))
      r6g <- dispositionsByGender(filterRace(lars, TwoOrMoreMinority))
      r7 <- dispositionsOutput(filterRace(lars, JointRace))
      r7g <- dispositionsByGender(filterRace(lars, JointRace))
      r8 <- dispositionsOutput(filterRace(lars, NotProvided))
      r8g <- dispositionsByGender(filterRace(lars, NotProvided))

      m1 <- dispositionsOutput(filterMinorityStatus(lars, WhiteNonHispanic))
      m1g <- dispositionsByGender(filterMinorityStatus(lars, WhiteNonHispanic))
      m2 <- dispositionsOutput(filterMinorityStatus(lars, OtherIncludingHispanic))
      m2g <- dispositionsByGender(filterMinorityStatus(lars, OtherIncludingHispanic))

      i1 <- dispositionsOutput(incomeIntervals(LessThan50PercentOfMSAMedian))
      i2 <- dispositionsOutput(incomeIntervals(Between50And79PercentOfMSAMedian))
      i3 <- dispositionsOutput(incomeIntervals(Between80And99PercentOfMSAMedian))
      i4 <- dispositionsOutput(incomeIntervals(Between100And119PercentOfMSAMedian))
      i5 <- dispositionsOutput(incomeIntervals(GreaterThan120PercentOfMSAMedian))
      i6 <- dispositionsOutput(lars.filter(lar => lar.applicant.income == "NA"))

      total <- dispositionsOutput(lars)
    } yield {
      s"""
         |{
         |    "table": "4-2",
         |    "type": "Aggregate",
         |    "description": "Disposition of applications for conventional home-purchase loans 1- to 4- family and manufactured home dwellings, by race, ethnicity, gender and income of applicant",
         |    "year": "$year",
         |    "reportDate": "$reportDate",
         |    "msa": $msa,
         |    "ethnicities": [
         |        {
         |            "ethnicity": "Hispanic or Latino",
         |            "dispositions": $e1,
         |            "genders": $e1g
         |        },
         |        {
         |            "ethnicity": "Not Hispanic or Latino",
         |            "dispositions": $e2,
         |            "genders": $e2g
         |        },
         |        {
         |            "ethnicity": "Joint (Hispanic or Latino/Not Hispanic or Latino)",
         |            "dispositions": $e3,
         |            "genders": $e3g
         |        },
         |        {
         |            "ethnicity": "Ethnicity Not Available",
         |            "dispositions": $e4,
         |            "genders": $e4g
         |        }
         |    ],
         |    "races": [
         |        {
         |            "race": "American Indian/Alaska Native",
         |            "dispositions": $r1,
         |            "genders": $r1g
         |        },
         |        {
         |            "race": "Asian",
         |            "dispositions": $r2,
         |            "genders": $r2g
         |        },
         |        {
         |            "race": "Black or African American",
         |            "dispositions": $r3,
         |            "genders": $r3g
         |        },
         |        {
         |            "race": "Native Hawaiian or Other Pacific Islander",
         |            "dispositions": $r4,
         |            "genders": $r4g
         |        },
         |        {
         |            "race": "White",
         |            "dispositions": $r5,
         |            "genders": $r5g
         |        },
         |        {
         |            "race": "2 or more minority races",
         |            "dispositions": $r6,
         |            "genders": $r6g
         |        },
         |        {
         |            "race": "Joint (White/Minority Race)",
         |            "dispositions": $r7,
         |            "genders": $r7g
         |        },
         |        {
         |            "race": "Race Not Available",
         |            "dispositions": $r8,
         |            "genders": $r8g
         |        }
         |    ],
         |    "minorityStatuses": [
         |        {
         |            "minorityStatus": "White Non-Hispanic",
         |            "dispositions": $m1,
         |            "genders": $m1g
         |        },
         |        {
         |            "minorityStatus": "Others, Including Hispanic",
         |            "dispositions": $m2,
         |            "genders": $m2g
         |        }
         |    ],
         |    "incomes": [
         |        {
         |            "income": "Less than 50% of MSA/MD median",
         |            "dispositions": $i1
         |        },
         |        {
         |            "income": "50-79% of MSA/MD median",
         |            "dispositions": $i2
         |        },
         |        {
         |            "income": "80-99% of MSA/MD median",
         |            "dispositions": $i3
         |        },
         |        {
         |            "income": "100-119% of MSA/MD median",
         |            "dispositions": $i4
         |        },
         |        {
         |            "income": "120% or more of MSA/MD median",
         |            "dispositions": $i5
         |        },
         |        {
         |            "income": "Income Not Available",
         |            "dispositions": $i6
         |        }
         |    ],
         |    "total": $total
         |}
         |
       """.stripMargin.parseJson
    }
  }

  private def dispositionsOutput[ec: EC, mat: MAT, as: AS](larSource: Source[LoanApplicationRegister, NotUsed]): Future[String] = {
    val calculatedDispositions: Future[List[ValueDisposition]] = Future.sequence(
      dispositions.map(_.calculateValueDisposition(larSource))
    )

    calculatedDispositions.map { list =>
      list.map(disp => disp.toJsonFormat).mkString("[", ",", "]")
    }
  }

  private def dispositionsByGender[ec: EC, mat: MAT, as: AS](larSource: Source[LoanApplicationRegister, NotUsed]): Future[String] = {
    for {
      male <- dispositionsOutput(filterGender(larSource, Male))
      female <- dispositionsOutput(filterGender(larSource, Female))
      joint <- dispositionsOutput(filterGender(larSource, JointGender))
    } yield {

      s"""
       |
       |[
       | {
       |     "gender": "Male",
       |     "dispositions": $male
       | },
       | {
       |     "gender": "Female",
       |     "dispositions": $female
       | },
       | {
       |     "gender": "Joint (Male/Female)",
       |     "dispositions": $joint
       | }
       |]
       |
     """.stripMargin

    }
  }

}
