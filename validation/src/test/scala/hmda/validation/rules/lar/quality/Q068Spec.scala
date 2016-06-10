package hmda.validation.rules.lar.quality

import hmda.model.fi.lar.LoanApplicationRegister
import hmda.validation.rules.EditCheck
import hmda.validation.rules.lar.LarEditCheckSpec

class Q068Spec extends LarEditCheckSpec {
  // Cases meeting preconditions
  property("passes when applicant is not a natural person and coapplicant is a natural person") {
    forAll(larGen) { lar =>
      val app = lar.applicant
      whenever(app.coEthnicity != 4 &&
        app.coRace1 != 7 &&
        app.coSex != 4 &&
        lar.actionTakenType != 6) {
        val notNaturalApplicant = lar.applicant.copy(ethnicity = 4, race1 = 7, sex = 4)
        val validLar = lar.copy(applicant = notNaturalApplicant)
        validLar.mustPass
      }
    }
  }
  property("passes when applicant is not a natural person and coapplicant does not exist") {
    forAll(larGen) { lar =>
      whenever(lar.actionTakenType != 6) {
        val validApplicant = lar.applicant.copy(ethnicity = 4, race1 = 7, sex = 4,
          coEthnicity = 5, coRace1 = 8, coSex = 5)
        val validLar = lar.copy(applicant = validApplicant)
        validLar.mustPass
      }
    }
  }
  property("fails when applicant and coapplicant both not a natural person") {
    forAll(larGen) { lar =>
      whenever(lar.actionTakenType != 6) {
        val notNaturalApps = lar.applicant.copy(ethnicity = 4, race1 = 7, sex = 4,
          coEthnicity = 4, coRace1 = 7, coSex = 4)
        val invalidLar = lar.copy(applicant = notNaturalApps)
        invalidLar.mustFail
      }
    }
  }

  // Cases not meeting preconditions
  property("passes when applicant is a natural person (and action taken is relevant)") {
    forAll(larGen) { lar =>
      whenever(lar.applicant.ethnicity != 4 && lar.actionTakenType != 6) {
        lar.mustPass
      }
    }
  }
  property("passes when action taken not 1-5,7,8") {
    forAll(larGen) { lar =>
      val validLar = lar.copy(actionTakenType = 6)
      validLar.mustPass
    }
  }
  override def check: EditCheck[LoanApplicationRegister] = Q068
}