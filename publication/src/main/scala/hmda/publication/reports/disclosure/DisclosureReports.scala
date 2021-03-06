package hmda.publication.reports.disclosure

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import hmda.model.institution.Institution
import hmda.persistence.messages.commands.institutions.InstitutionCommands.GetInstitutionByRespondentId
import hmda.persistence.model.HmdaSupervisorActor.FindActorByName
import hmda.publication.reports.protocol.disclosure.D5XProtocol._
import hmda.query.repository.filing.LoanApplicationRegisterCassandraRepository

import scala.concurrent.Future
import scala.concurrent.duration._
import spray.json._

class DisclosureReports(val sys: ActorSystem, val mat: ActorMaterializer) extends LoanApplicationRegisterCassandraRepository {

  override implicit def system: ActorSystem = sys
  override implicit def materializer: ActorMaterializer = mat
  val duration = config.getInt("hmda.actor-lookup-timeout")
  implicit val timeout = Timeout(duration.seconds)

  val larSource = readData(1000)

  def generateReports(fipsCode: Int, respId: String): Future[Unit] = {
    val institutionNameF = institutionName(respId)

    val d8XReports = List(D81, D82, D83, D84, D85, D86, D87)
    val d8XF = Future.sequence(d8XReports.map { report =>
      report.generate(larSource, fipsCode, respId, institutionNameF)
    })

    val d4XReports = List(D41, D42, D43, D44, D45, D46, D47)
    val d4XF = Future.sequence(d4XReports.map { report =>
      report.generate(larSource, fipsCode, respId, institutionNameF)
    })

    val d51F = D51.generate(larSource, fipsCode, respId, institutionNameF)
    d51F.map { d51 =>
      println(d51.toJson.prettyPrint)
    }

    //val d53F = D53.generate(larSource, fipsCode, respId, institutionNameF)

  }

  private def institutionName(respondentId: String): Future[String] = {
    val supervisor = system.actorSelection("/user/supervisor")
    val fInstitutionsActor = (supervisor ? FindActorByName("institutions")).mapTo[ActorRef]
    for {
      a <- fInstitutionsActor
      i <- (a ? GetInstitutionByRespondentId(respondentId)).mapTo[Institution]
    } yield i.respondent.name
  }

}
