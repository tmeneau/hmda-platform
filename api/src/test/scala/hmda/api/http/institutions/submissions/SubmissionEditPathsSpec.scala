package hmda.api.http.institutions.submissions

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.ask
import hmda.api.http.InstitutionHttpApiAsyncSpec
import hmda.api.model.{ EditResult, _ }
import hmda.model.fi._
import hmda.model.fi.lar.LarGenerators
import hmda.model.fi.ts.{ TransmittalSheet, TsGenerators }
import hmda.model.validation._
import hmda.persistence.HmdaSupervisor.FindProcessingActor
import hmda.persistence.messages.CommonMessages.GetState
import hmda.persistence.messages.commands.processing.HmdaFileValidatorState.{ HmdaVerificationState, SVState }
import hmda.persistence.processing.HmdaFileValidator
import hmda.persistence.processing.HmdaFileValidator.GetSVState
import hmda.validation.engine._
import spray.json.{ JsNumber, JsObject }

import scala.concurrent.Future

class SubmissionEditPathsSpec extends InstitutionHttpApiAsyncSpec with LarGenerators with TsGenerators {

  override def beforeAll(): Unit = {
    super.beforeAll()
    loadValidationErrors()
  }

  val s020Description = "Agency code must = 1, 2, 3, 5, 7, 9. The agency that submits the data must be the same as the reported agency code."
  val s010Description = "The first record identifier in the file must = 1 (TS). The second and all subsequent record identifiers must = 2 (LAR)."
  val v280Description = "MSA/MD must = a valid Metropolitan Statistical Area or Metropolitan Division (if appropriate) code for period being processed or NA."
  val v285Description = "State must = a valid FIPS code or (NA where MSA/MD = NA)."
  val q007Description = "If action taken type = 2, then the total number of these loans should be ≤ 15% of the total number of loan applications."
  val q666Description = "The Loan/Application Number data field contains all alphabetical characters following the pattern of a name or numbers and dashes following the pattern of a Social Security number. Institutions are strongly encouraged not to use the applicant’s or borrower’s name or Social Security number in the Loan/Application Number field, for privacy reasons. Please review the data in this field and consider updating your file, if appropriate."

  val s020info = EditInfo("S020", s020Description)
  val s010info = EditInfo("S010", s010Description)
  val v280info = EditInfo("V280", v280Description)
  val v285info = EditInfo("V285", v285Description)
  val q007info = EditInfo("Q007", q007Description)
  val q666info = EditInfo("Q666", q666Description)

  "return summary of validation errors" in {
    getWithCfpbHeaders(s"/institutions/0/filings/2017/submissions/1/edits") ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK
      val r = responseAs[SummaryEditResults]
      r.syntactical mustBe EditCollection(Seq(s010info, s020info))
      r.validity mustBe EditCollection(Seq(v280info, v285info))
      r.quality mustBe VerifiableEditCollection(verified = false, Seq(q666info))
      r.`macro` mustBe VerifiableEditCollection(verified = false, Seq(q007info))
    }
  }

  val s010 = Seq(EditResultRow(RowId("loan1"), JsObject(("Record Identifier", JsNumber(111)))))
  "return row details for an edit" in {
    val path = "/institutions/0/filings/2017/submissions/1/edits/S010"
    val expected = EditResult("S010", s010, path, 1, 1)

    getWithCfpbHeaders(path) ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK
      responseAs[EditResult] mustBe expected
    }
  }

  val q666Result = Seq(EditResultRow(RowId("loan1"), JsObject(("Record Identifier", JsNumber(111)))))
  "return row details for Q666" in {
    val path = "/institutions/0/filings/2017/submissions/1/edits/Q666"
    val expected = EditResult("Q666", q666Result, path, 1, 1)

    getWithCfpbHeaders(path) ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK
      responseAs[EditResult] mustBe expected
    }
  }

  ///// CSV /////

  "Return summary edits in CSV format" in {
    getWithCfpbHeaders(s"/institutions/0/filings/2017/submissions/1/edits/csv") ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK
      responseAs[String] must include("editType, editId, loanId")
      responseAs[String] must include("Syntactical, S020, Transmittal Sheet")
      responseAs[String] must include("Validity, V285, loan2")
    }
  }

  ///// Verification /////

  "Set up: get submission to ValidatedWithErrors state" in {
    postWithCfpbHeaders("/institutions/0/filings/2017/submissions/2", file) ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      Thread.sleep(5000) // wait for the submission to complete validation
      status mustBe StatusCodes.Accepted

      // Check for correct baseline validation state.
      //   Incorrect baseline will invalidate the other tests in this section.
      val fState: Future[(HmdaVerificationState, Boolean)] = for {
        s <- fHmdaValidatorActor(2)
        xs <- (s ? GetState).mapTo[HmdaVerificationState]
        svState <- (s ? GetSVState).mapTo[SVState]
      } yield (xs, svState.containsSVEdits)

      fState.map {
        case ((state: HmdaVerificationState, svEdits: Boolean)) =>
          svEdits mustBe false
          state.macroVerified mustBe false
          state.qualityVerified mustBe false
      }

    }
  }

  "Verify Macro edits endpoint: Responds with correct json and updates validation state" in {
    val verification = EditsVerification(true)
    val currentStatus = ValidatedWithErrors

    postWithCfpbHeaders("/institutions/0/filings/2017/submissions/2/edits/macro", verification) ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK

      // test that it responds correctly
      responseAs[EditsVerifiedResponse] mustBe EditsVerifiedResponse(true, currentStatus)

      // test that it updates validation state
      fVerificationState.map { state =>
        state.qualityVerified mustBe false
        state.macroVerified mustBe true
      }
    }
  }

  "Verify Quality edits endpoint: Responds with correct json, updates validation state, updates submission state" in {
    val verification = EditsVerification(true)
    val currentStatus = Validated

    postWithCfpbHeaders("/institutions/0/filings/2017/submissions/2/edits/quality", verification) ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK

      // test that it responds correctly
      responseAs[EditsVerifiedResponse] mustBe EditsVerifiedResponse(true, currentStatus)

      // test that it updates validation state
      fVerificationState.map { state =>
        state.qualityVerified mustBe true
        state.macroVerified mustBe true
      }
    }
  }

  "Un-verify quality edits: updates submission state correctly back to `ValidatedWithErrors`" in {
    val verification = EditsVerification(false)
    val currentStatus = ValidatedWithErrors

    postWithCfpbHeaders("/institutions/0/filings/2017/submissions/2/edits/quality", verification) ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.OK
      responseAs[EditsVerifiedResponse] mustBe EditsVerifiedResponse(false, currentStatus)

      fVerificationState.map { state =>
        state.qualityVerified mustBe false
        state.macroVerified mustBe true
      }
    }
  }

  ///// 404 (Not Found) Responses /////

  "Edits endpoint: return 404 for nonexistent institution" in {
    getWithCfpbHeaders(s"/institutions/xxxxx/filings/2017/submissions/1/edits") ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.NotFound
      responseAs[ErrorResponse].message mustBe "Institution xxxxx not found"
    }
  }
  "Edits endpoint: return 404 for nonexistent filing period" in {
    getWithCfpbHeaders(s"/institutions/0/filings/1980/submissions/1/edits") ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.NotFound
      responseAs[ErrorResponse].message mustBe "1980 filing period not found for institution 0"
    }
  }
  "Edits endpoint: return 404 for nonexistent submission" in {
    getWithCfpbHeaders(s"/institutions/0/filings/2017/submissions/0/edits") ~> institutionsRoutes(supervisor, querySupervisor, validationStats) ~> check {
      status mustBe StatusCodes.NotFound
      responseAs[ErrorResponse].message mustBe "Submission 0 not found for 2017 filing period"
    }
  }

  ///// Helper Methods /////

  private def loadValidationErrors(): Unit = {
    val s1 = SyntacticalValidationError("loan1", "S010", false)
    val s2 = SyntacticalValidationError("loan1", "S020", false)
    val v1 = ValidityValidationError("loan1", "V280", false)
    val v2 = ValidityValidationError("loan2", "V285", false)
    val v3 = ValidityValidationError("loan3", "V285", false)
    val q1 = QualityValidationError("loan1", "Q666", false)
    val m1 = MacroValidationError("Q007")

    val l1 = sampleLar
    val lar1 = l1.copy(
      loan = l1.loan.copy(id = "loan1"),
      id = 111,
      agencyCode = 222,
      geography = l1.geography.copy(msa = "333")
    )

    val l2 = sampleLar
    val lar2 = l2.copy(
      loan = l2.loan.copy(id = "loan2"),
      geography = l2.geography.copy(state = "444", msa = "555")
    )

    val l3 = sampleLar
    val lar3 = l3.copy(
      loan = l3.loan.copy(id = "loan3"),
      geography = l3.geography.copy(state = "666", msa = "777")
    )

    val ts = tsGen.sample.getOrElse(TransmittalSheet()).copy(agencyCode = 888)

    val larValidationErrors = LarValidationErrors(Seq(s1, s2, v1, v2, v3, q1, m1))
    val tsValidationErrors = TsValidationErrors(Seq(s2.copy(ts = true)))

    for {
      h <- fHmdaValidatorActor(1)
    } yield {
      h ! larValidationErrors
      h ! tsValidationErrors
      h ! lar1
      h ! lar2
      h ! lar3
      h ! ts
    }

  }

  private def fVerificationState: Future[HmdaVerificationState] = {
    for {
      s <- fHmdaValidatorActor(2)
      xs <- (s ? GetState).mapTo[HmdaVerificationState]
    } yield xs
  }

  private def fHmdaValidatorActor(seqNr: Int): Future[ActorRef] = {
    val id = "0"
    val period = "2017"
    val submissionId = SubmissionId(id, period, seqNr)
    (supervisor ? FindProcessingActor(HmdaFileValidator.name, submissionId)).mapTo[ActorRef]
  }

  val csv = "1|externalTest0|3|201502221111|2017|35-0704860|10|Passes Bank|555 Passes Court|Passes City|CA|92130|Passes Bank Parent|555 Passes Court Parent|Passes City|CA|92130|Passes Person|555-555-5555|555-555-5555|pperson@passes.com\n" +
    "2|externalTest0|3|10164 |20170224|1|1|3|1|21|3|1|20170326|45460|18|153|0501.00|2|2|5| | | | |5| | | | |1|2|31|0| | | |NA   |2|1\n" +
    "2|externalTest0|3|10174 |20170224|1|1|2|1|60|3|1|20170402|45460|18|153|0503.00|2|2|5| | | | |5| | | | |1|2|210|0| | | |NA   |2|2\n" +
    "2|externalTest0|3|10370 |20170228|1|1|3|1|73|3|3|20170326|45460|18|153|0505.00|2|2|5| | | | |5| | | | |1|2|89|0|4| | |NA   |2|1"

  val file = multiPartFile(csv, "sample.txt")
}
