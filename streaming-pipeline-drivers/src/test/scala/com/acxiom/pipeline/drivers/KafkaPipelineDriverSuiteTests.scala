package com.acxiom.pipeline.drivers

import java.nio.file.{Files, Path}
import java.util.Properties

import com.acxiom.pipeline._
import kafka.server.{KafkaConfig, KafkaServerStartable}
import org.apache.commons.io.FileUtils
import org.apache.curator.test.TestingServer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.scalatest.{BeforeAndAfterAll, FunSpec, GivenWhenThen}

class KafkaPipelineDriverSuiteTests extends FunSpec with BeforeAndAfterAll with GivenWhenThen {

  val kafkaLogs: Path = Files.createTempDirectory("kafkaLogs")
  val sparkLocalDir: Path = Files.createTempDirectory("sparkLocal")
  val testingServer = new TestingServer
  val kafkaProperties = new Properties()
  kafkaProperties.put("broker.id", "0")
  kafkaProperties.put("port", "9092")
  kafkaProperties.put("zookeeper.connect", testingServer.getConnectString)
  kafkaProperties.put("host.name", "127.0.0.1")
  kafkaProperties.put("auto.create.topics.enable", "true")
  kafkaProperties.put("offsets.topic.replication.factor", "1")
  kafkaProperties.put(KafkaConfig.LogDirsProp, kafkaLogs.toFile.getAbsolutePath)
  val server = new KafkaServerStartable(new KafkaConfig(kafkaProperties))

  override def beforeAll(): Unit = {
    testingServer.start()
    server.startup()

    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.apache.hadoop").setLevel(Level.WARN)
    Logger.getLogger("com.acxiom.pipeline").setLevel(Level.DEBUG)
    SparkTestHelper.sparkConf = new SparkConf()
      .setMaster(SparkTestHelper.MASTER)
      .setAppName(SparkTestHelper.APPNAME)
        .set("spark.local.dir", sparkLocalDir.toFile.getAbsolutePath)
    SparkTestHelper.sparkConf.set("spark.hadoop.io.compression.codecs",
      ",org.apache.hadoop.io.compress.BZip2Codec,org.apache.hadoop.io.compress.DeflateCodec," +
        "org.apache.hadoop.io.compress.GzipCodec,org.apache." +
        "hadoop.io.compress.Lz4Codec,org.apache.hadoop.io.compress.SnappyCodec")

    SparkTestHelper.sparkSession = SparkSession.builder().config(SparkTestHelper.sparkConf).getOrCreate()
  }

  override def afterAll(): Unit = {
    server.shutdown()
    server.awaitShutdown()
    testingServer.stop()

    SparkTestHelper.sparkSession.sparkContext.cancelAllJobs()
    SparkTestHelper.sparkSession.sparkContext.stop()
    SparkTestHelper.sparkSession.stop()
    Logger.getRootLogger.setLevel(Level.INFO)

    // cleanup spark directories
    FileUtils.deleteDirectory(sparkLocalDir.toFile)
    // cleanup the kafka directories
    FileUtils.deleteDirectory(kafkaLogs.toFile)
  }

  describe("Kafka Pipeline Driver") {
    it("Should process simple records from Kafka") {
      When("5 kafaka messages are posted")
      val props = new Properties()
      props.put("bootstrap.servers", "localhost:9092")
      props.put("acks", "all")
      props.put("retries", "0")
      props.put("batch.size", "16384")
      props.put("linger.ms", "1")
      props.put("buffer.memory", "33554432")
      props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      val producer = new KafkaProducer[String, String](props)
      val topic = "TEST"
      SparkTestHelper.dataRows.foreach(row =>
        producer.send(new ProducerRecord[String, String](topic, "InboundRecord", row.mkString("|"))))

      producer.flush()
      producer.close()

      var executionComplete = false
      SparkTestHelper.pipelineListener = new PipelineListener {
        override def executionFinished(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
          assert(pipelines.lengthCompare(1) == 0)
          val params = pipelineContext.parameters.getParametersByPipelineId("1")
          assert(params.isDefined)
          assert(params.get.parameters.contains("PROCESS_KAFKA_DATA"))
          assert(params.get.parameters("PROCESS_KAFKA_DATA").asInstanceOf[PipelineStepResponse].primaryReturn.isDefined)
          assert(params.get.parameters("PROCESS_KAFKA_DATA").asInstanceOf[PipelineStepResponse]
            .primaryReturn.getOrElse(false).asInstanceOf[Boolean])
          executionComplete = true
          None
        }
        override def registerStepException(exception: PipelineStepException, pipelineContext: PipelineContext): Unit = {
          exception match {
            case t: Throwable => fail(s"Pipeline Failed to run: ${t.getMessage}")
          }
        }
      }

      And("the kafka spark listener is running")
      val args = List("--driverSetupClass", "com.acxiom.pipeline.drivers.SparkTestDriverSetup", "--pipeline", "basic",
        "--globalInput", "global-input-value", "--topics", topic, "--kafkaNodes", "localhost:9092",
        "--terminationPeriod", "5000", "--fieldDelimiter", "|", "--duration-type", "seconds",
      "--duration", "1")
      KafkaPipelineDriver.main(args.toArray)
      Then("5 records should be processed")
      assert(executionComplete)
    }
  }
}
