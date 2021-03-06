package hmda.publication.regulator.lar

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
import akka.stream.Supervision.Decider
import akka.stream.alpakka.s3.javadsl.S3Client
import akka.stream.alpakka.s3.{ MemoryBufferType, S3Settings }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import akka.util.{ ByteString, Timeout }
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials }
import hmda.model.fi.lar.LoanApplicationRegister
import hmda.persistence.messages.events.processing.CommonHmdaValidatorEvents.LarValidated
import hmda.persistence.messages.events.pubsub.PubSubEvents.SubmissionSignedPubSub
import hmda.persistence.model.HmdaActor
import hmda.persistence.processing.HmdaQuery._
import hmda.persistence.processing.PubSubTopics
import hmda.query.repository.filing.LarConverter._
import hmda.query.repository.filing.LoanApplicationRegisterCassandraRepository

import scala.concurrent.duration._

object ModifiedLarPublisher {
  val name = "SubmissionSignedModifiedLarSubscriber"
  def props(supervisor: ActorRef): Props = Props(new ModifiedLarPublisher(supervisor))
}

class ModifiedLarPublisher(supervisor: ActorRef) extends HmdaActor with LoanApplicationRegisterCassandraRepository {

  val decider: Decider = { e =>
    repositoryLog.error("Unhandled error in stream", e)
    Supervision.Resume
  }

  override implicit def system: ActorSystem = context.system
  val materializerSettings = ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  override implicit def materializer: ActorMaterializer = ActorMaterializer(materializerSettings)(system)

  val mediator = DistributedPubSub(context.system).mediator

  mediator ! Subscribe(PubSubTopics.submissionSigned, self)

  val fetchSize = config.getInt("hmda.publication.fetchsize")
  val actorTimeout = config.getInt("hmda.actor.timeout")

  implicit val timeout = Timeout(actorTimeout.seconds)

  val accessKeyId = config.getString("hmda.publication.aws.access-key-id")
  val secretAccess = config.getString("hmda.publication.aws.secret-access-key ")
  val region = config.getString("hmda.publication.aws.region")
  val bucket = config.getString("hmda.publication.aws.public-bucket")
  val environment = config.getString("hmda.publication.aws.environment")

  val awsCredentials = new AWSStaticCredentialsProvider(
    new BasicAWSCredentials(accessKeyId, secretAccess)
  )
  val awsSettings = new S3Settings(MemoryBufferType, None, awsCredentials, region, false)
  val s3Client = new S3Client(awsSettings, context.system, materializer)

  override def receive: Receive = {

    case SubscribeAck(Subscribe(PubSubTopics.submissionSigned, None, `self`)) =>
      log.info(s"${self.path} subscribed to ${PubSubTopics.submissionSigned}")

    case SubmissionSignedPubSub(submissionId) =>
      val institutionId = submissionId.institutionId
      val fileName = s"$institutionId.txt"
      val s3Sink = s3Client.multipartUpload(bucket, s"$environment/lar/$fileName")
      log.info(s"${self.path} received submission signed event with submission id: ${submissionId.toString}")
      val persistenceId = s"HmdaFileValidator-$submissionId"
      val larSource = events(persistenceId).map {
        case LarValidated(lar, _) => lar
        case _ => LoanApplicationRegister()
      }

      val mlarSource = larSource
        .filter(lar => !lar.isEmpty)
        .map(lar => toModifiedLar(lar))
        .map(mLar => mLar.toCSV + "\n")
        .map(s => ByteString(s))

      mlarSource.runWith(s3Sink)

    case _ => //do nothing

  }

}
