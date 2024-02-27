//> using scala "2.13.11"
//> using dep "javax.jms:jms:1.1"
//> using dep "com.lightbend.akka::akka-stream-alpakka-jms:6.0.2"
//> using dep "com.typesafe.akka::akka-stream-typed:2.8.5"
//> using dep "com.ibm.mq:com.ibm.mq.allclient:9.3.4.1"
//> using dep "com.typesafe.scala-logging::scala-logging:3.9.5"
//> using dep "ch.qos.logback:logback-classic:1.5.0"
//> using dep "net.logstash.logback:logstash-logback-encoder:7.4"
//> using dep "io.github.sullis::jms-testkit:1.0.4"

import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.jms.{AckEnvelope, AcknowledgeMode, ConnectionRetrySettings, Credentials, JmsConsumerSettings, JmsProducerSettings, JmsTextMessage}
import akka.stream.alpakka.jms.scaladsl.JmsConsumer
import akka.stream.alpakka.jms.scaladsl.JmsConsumerControl
import akka.stream.alpakka.jms.scaladsl.JmsProducer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.ibm.mq.jms.MQConnectionFactory
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.jms.JmsConstants
import com.ibm.msg.client.wmq.common.CommonConstants
import com.typesafe.config.ConfigFactory

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.IterableHasAsJava

object AlpakkaJmsAckSourceUsageSample {
  case class IbmMQConnectionConfig(hostname: String, port: Int, queueManager: String, channel: String)

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem(Behaviors.empty[NotUsed], "AlpakkaJmsAckSourceUsageSample")
    implicit val ec = system.executionContext

    val connectionConfig: IbmMQConnectionConfig = IbmMQConnectionConfig(
      hostname = "localhost",
      port = 1414,
      queueManager = "QM1",
      channel = "DEV.APP.SVRCONN"
    )
    val connectionFactory = createQueueConnectionFactory(connectionConfig)
    val queue = "DEV.QUEUE.1"
    val credentials = Credentials("app", "passw0rd")

    val producerConfig = system.settings.config.getConfig(JmsProducerSettings.configPath)
    val producerSettings = JmsProducerSettings(producerConfig, connectionFactory)
      .withQueue(queue)
      .withCredentials(credentials)

    val consumerConfig = system.settings.config.getConfig(JmsConsumerSettings.configPath)
    val consumerSettings = JmsConsumerSettings(consumerConfig, connectionFactory)
      .withQueue(queue)
      .withCredentials(credentials)
      .withConnectionRetrySettings(ConnectionRetrySettings(system))
      .withSessionCount(4)
      .withBufferSize(2)
      .withAckTimeout(1.second)
      .withMaxPendingAcks(2)
      .withAcknowledgeMode(AcknowledgeMode.ClientAcknowledge)

    val jmsSink: Sink[JmsTextMessage, Future[Done]] = JmsProducer.sink(producerSettings)

    val msgsIn = 1 to 10 map { n =>
      JmsTextMessage(n.toString)
        .withProperty("Number", n)
        .withProperty("IsOdd", n % 2 == 1)
        .withProperty("IsEven", n % 2 == 0)
    }

    Source(msgsIn).runWith(jmsSink)


    val jmsSource: Source[AckEnvelope, JmsConsumerControl] = JmsConsumer.ackSource(consumerSettings)

    Await.ready(
      jmsSource
        .take(msgsIn.size)
        .map { ackEnvelope =>
          ackEnvelope.acknowledge()
          ackEnvelope.message
        }
        .runWith(Sink.seq)
        .map { msgs =>
          msgs.foreach(
            msg => println(s"MESSAGE: ${msg.getIntProperty("Number")}")
          )
        },
      120.second
    )

    Thread.sleep(3000)

    Await.ready(jmsSource.takeWithin(1.second).map { ackEnvelope =>
          ackEnvelope.acknowledge()
          ackEnvelope.message
        }
        .runWith(Sink.seq)
        .map { msgs =>
          msgs.foreach(
            msg => println(s"MESSAGE: ${msg.getIntProperty("Number")}")
          )
        },
      120.second
    )

    system.terminate()
  }

  private def createJmsConnectionFactory(
                                  config: IbmMQConnectionConfig,
                                  connectionFactory: MQConnectionFactory
                                ): MQConnectionFactory = {
    connectionFactory.setHostName(config.hostname)
    connectionFactory.setPort(config.port)
    connectionFactory.setQueueManager(config.queueManager)
    connectionFactory.setChannel(config.channel)
    // Connect to IBM MQ over TCP/IP
    connectionFactory.setTransportType(CommonConstants.WMQ_CM_CLIENT)
    connectionFactory.setIntProperty(JmsConstants.ASYNC_EXCEPTIONS, JmsConstants.ASYNC_EXCEPTIONS_ALL)

    val msgCompression = Seq(CommonConstants.WMQ_COMPMSG_ZLIBFAST).asJavaCollection
    connectionFactory.setMsgCompList(msgCompression)
    connectionFactory
  }

  private def createQueueConnectionFactory(config: IbmMQConnectionConfig): MQQueueConnectionFactory = {
    val connectionFactory = new MQQueueConnectionFactory()
    createJmsConnectionFactory(config, connectionFactory).asInstanceOf[MQQueueConnectionFactory]
  }
}
