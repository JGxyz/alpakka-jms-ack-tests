# Alpakka JMS ackSource with IBM MQ usage samples 

## Requirements

Requirements for running the tests:
- scala-cli: https://scala-cli.virtuslab.org/install,
- local IBM MQ server (for `AlpakkaJmsAckSourceUsageSample` and `AlpakkaJmsAckOriginalTestLocalIbmMqServer`):
  - build local image using the following commands:
    ```bash
    git clone https://github.com/ibm-messaging/mq-container.git
    cd mq-container
    make build-devserver
    ```
  - make sure that the image version aligns with the version specified in the `ibmmq-m1.yml` file (if not, change the version in the file to the one you have locally)

## Running the tests

To run the tests use the following commands:
```bash
scala-cli AlpakkaJmsAckSourceUsageSample.scala
scala-cli test ./tests/AlpakkaJmsAckOriginalTestJmsBrokerActiveMQ.scala
scala-cli test ./tests/AlpakkaJmsAckOriginalTestLocalIbmMqServer.scala
```