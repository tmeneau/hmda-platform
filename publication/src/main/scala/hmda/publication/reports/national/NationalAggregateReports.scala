package hmda.publication.reports.national

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import hmda.query.repository.filing.LoanApplicationRegisterCassandraRepository
import hmda.publication.reports.protocol.national.N5XProtocol._

import scala.concurrent.Future
import scala.concurrent.duration._
import spray.json._

class NationalAggregateReports(val sys: ActorSystem, val mat: ActorMaterializer) extends LoanApplicationRegisterCassandraRepository {
  override implicit def system: ActorSystem = sys
  override implicit def materializer: ActorMaterializer = mat
  val duration = config.getInt("hmda.actor-lookup-timeout")
  implicit val timeout = Timeout(duration.seconds)

  val larSource = readData(1000)

  def generateReports(fipsCode: Int, respId: String): Future[Unit] = {

    val n52F = N52.generate(larSource)
    n52F.map { n52 =>
      println(n52.toJson.prettyPrint)
    }

    val n53F = N53.generate(larSource)
    n53F.map(report => println(report.toJson.prettyPrint))
  }
}
