
package investphere.analytics

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.{ Committer, Consumer }
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSource
import com.google.protobuf.any.{ Any => ScalaPBAny }
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import investphere.cart.proto

object InvestphereEventConsumer {

  private val log =
    LoggerFactory.getLogger("investphere.analytics.InvestphereEventConsumer")

  def init(system: ActorSystem[_]): Unit = {
    implicit val sys: ActorSystem[_] = system
    implicit val ec: ExecutionContext =
      system.executionContext

    val topic = system.settings.config
      .getString("investphere-analytics-service.investphere-kafka-topic")
    val consumerSettings =
      ConsumerSettings(
        system,
        new StringDeserializer,
        new ByteArrayDeserializer).withGroupId("investphere-analytics")
    val committerSettings = CommitterSettings(system)

    RestartSource 
      .onFailuresWithBackoff(
        RestartSettings(
          minBackoff = 1.second,
          maxBackoff = 30.seconds,
          randomFactor = 0.1)) { () =>
        Consumer
          .committableSource(
            consumerSettings,
            Subscriptions.topics(topic)
          ) 
          .mapAsync(1) { msg =>
            handleRecord(msg.record).map(_ => msg.committableOffset)
          }
          .via(Committer.flow(committerSettings)) 
      }
      .run()
  }

  private def handleRecord(
      record: ConsumerRecord[String, Array[Byte]]): Future[Done] = {
    val bytes = record.value()
    val x = ScalaPBAny.parseFrom(bytes) 
    val typeUrl = x.typeUrl
    try {
      val inputBytes = x.value.newCodedInput()
      val event =
        typeUrl match {
          case "investphere-service/investphere.ItemAdded" =>
            proto.ItemAdded.parseFrom(inputBytes)
          
          case "investphere-service/investphere.ItemQuantityAdjusted" =>
            proto.ItemQuantityAdjusted.parseFrom(inputBytes)
          case "investphere-service/investphere.ItemRemoved" =>
            proto.ItemRemoved.parseFrom(inputBytes)
          
          case "investphere-service/investphere.CheckedOut" =>
            proto.CheckedOut.parseFrom(inputBytes)
          case _ =>
            throw new IllegalArgumentException(
              s"unknown record type [$typeUrl]")
        }

      event match {
        case proto.ItemAdded(cartId, itemId, quantity, _) =>
          log.info("ItemAdded: {} {} to cart {}", quantity, itemId, cartId)
        
        case proto.ItemQuantityAdjusted(cartId, itemId, quantity, _) =>
          log.info(
            "ItemQuantityAdjusted: {} {} to cart {}",
            quantity,
            itemId,
            cartId)
        case proto.ItemRemoved(cartId, itemId, _) =>
          log.info("ItemRemoved: {} removed from cart {}", itemId, cartId)
        
        case proto.CheckedOut(cartId, _) =>
          log.info("CheckedOut: cart {} checked out", cartId)
      }

      Future.successful(Done)
    } catch {
      case NonFatal(e) =>
        log.error("Could not process event of type [{}]", typeUrl, e)
        // continue with next
        Future.successful(Done)
    }
  }

}

