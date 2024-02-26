//> using scala "2.13.11"
//> using dep "javax.jms:jms:1.1"
//> using dep "com.lightbend.akka::akka-stream-alpakka-jms:6.0.2"
//> using dep "com.typesafe.akka::akka-stream-typed:2.8.5"
//> using dep "com.ibm.mq:com.ibm.mq.allclient:9.3.4.1"
//> using dep "com.typesafe.scala-logging::scala-logging:3.9.5"
//> using dep "ch.qos.logback:logback-classic:1.5.0"
//> using dep "net.logstash.logback:logstash-logback-encoder:7.4"
//> using dep "io.github.sullis::jms-testkit:1.0.4"
//> using dep "com.typesafe.akka::akka-stream-testkit:2.8.5"
//> using dep "org.scalatest::scalatest:3.2.18"

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import akka.stream.alpakka.jms._
import akka.stream.alpakka.jms.scaladsl.{JmsConsumer, JmsConsumerControl, JmsProducer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{KillSwitches, ThrottleMode}
import javax.jms.{JMSException, TextMessage}
import org.scalatest.Inspectors._
import org.scalatest.time.Span.convertSpanToDuration
import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Random}
import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.jms.{AckEnvelope, AcknowledgeMode, ConnectionRetrySettings, Credentials, JmsConsumerSettings, JmsProducerSettings, JmsTextMessage}
import com.ibm.mq.jms.MQConnectionFactory
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.jms.JmsConstants
import com.ibm.msg.client.wmq.common.CommonConstants
import com.typesafe.config.ConfigFactory
import java.lang
import scala.concurrent.Await
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import akka.stream.alpakka.jms.JmsTextMessage
import akka.util.ByteString
import javax.jms.BytesMessage
import javax.jms.ConnectionFactory
import javax.jms.Message
import org.scalatest.Suite
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import akka.testkit.TestKit
import jmstestkit.JmsBroker
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import javax.jms._

trait JmsServerSupport extends AnyWordSpec
  with Matchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with ScalaFutures
  with Eventually {
  private var jmsBroker: JmsBroker = _
  private var connectionFactory: ConnectionFactory = _

  implicit val system: ActorSystem[_] = ActorSystem(Behaviors.empty[NotUsed], "test")
  protected val consumerConfig = system.settings.config.getConfig(JmsConsumerSettings.configPath)
  protected val producerConfig = system.settings.config.getConfig(JmsProducerSettings.configPath)
  protected val browseConfig = system.settings.config.getConfig(JmsBrowseSettings.configPath)

  override def beforeAll(): Unit = {
    jmsBroker = JmsBroker()
    connectionFactory = jmsBroker.createConnectionFactory
    Thread.sleep(500)
  }

  override protected def afterAll(): Unit = {
    system.terminate()
    if (jmsBroker != null && jmsBroker.isStarted) {
      jmsBroker.stop()
    }
  }

  protected def isQueueEmpty(queueName: String): Boolean = jmsBroker.service.checkQueueSize(queueName)

  protected def withConnectionFactory()(test: ConnectionFactory => Unit): Unit = {
    test(connectionFactory)
  }

  protected def createName(prefix: String) = prefix + Random.nextInt().toString
}

class JmsBufferedAckConnectorsSpec extends JmsServerSupport {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(2.minutes)

  "The JMS Ack Connectors" should {
    "publish and consume strings through a queue" in withConnectionFactory() { connectionFactory =>
      val queueName = createName("test")
      val jmsSink: Sink[String, Future[Done]] = JmsProducer.textSink(
        JmsProducerSettings(producerConfig, connectionFactory).withQueue(queueName)
      )

      val in = 0 to 25 map (i => ('a' + i).asInstanceOf[Char].toString)
      Source(in).runWith(jmsSink)

      val jmsSource: Source[AckEnvelope, JmsConsumerControl] = JmsConsumer.ackSource(
        JmsConsumerSettings(system, connectionFactory).withSessionCount(5).withQueue(queueName)
      )

      val result = jmsSource
        .take(in.size)
        .map(env => (env, env.message.asInstanceOf[TextMessage].getText))
        .map { case (env, text) => env.acknowledge(); text }
        .runWith(Sink.seq)

      result.futureValue should contain theSameElementsAs in

      jmsSource.takeWithin(1.second).runWith(Sink.seq).futureValue shouldBe empty
    }
  }
}