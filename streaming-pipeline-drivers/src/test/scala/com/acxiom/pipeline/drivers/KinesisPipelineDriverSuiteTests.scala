package com.acxiom.pipeline.drivers

import java.nio.file.{Files, Path}
import java.time.Duration

import com.acxiom.pipeline._
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.{PutRecordsRequest, PutRecordsRequestEntry}
import org.apache.commons.io.FileUtils
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.scalatest.tags.Slow
import org.scalatest.{BeforeAndAfterAll, FunSpec, GivenWhenThen}
import org.testcontainers.containers.localstack.LocalStackContainer

import scala.collection.JavaConverters._

@Slow
class KinesisPipelineDriverSuiteTests extends FunSpec with BeforeAndAfterAll with GivenWhenThen {
  private val sparkLocalDir: Path = Files.createTempDirectory("sparkLocal")
  private val localstack = new LocalStackContainer()
    .withServices(LocalStackContainer.Service.KINESIS)
    .withStartupTimeout(Duration.ofMinutes(2))

  override def beforeAll(): Unit = {
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

    System.setProperty("com.amazonaws.sdk.disableCbor", "true")
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")

    // Start AWS mock services
    localstack.start()
  }

  override def afterAll(): Unit = {
    SparkTestHelper.sparkSession.sparkContext.cancelAllJobs()
    SparkTestHelper.sparkSession.sparkContext.stop()
    SparkTestHelper.sparkSession.stop()
    Logger.getRootLogger.setLevel(Level.INFO)

    // Shut down AWS mock services
    localstack.stop()

    // cleanup spark directories
    FileUtils.deleteDirectory(sparkLocalDir.toFile)
  }

  describe("Kinesis Pipeline Driver") {
    it("Should load data from Kinesis stream") {
      val endPointConfiguration = localstack.getEndpointConfiguration(LocalStackContainer.Service.KINESIS)
      val endPointURL = endPointConfiguration.getServiceEndpoint
      val streamName = "TEST_KINESIS_STREAM"
      val appName = "TEST_KINESIS_APP"
      val regionName = endPointConfiguration.getSigningRegion
      val credentialsProvider = localstack.getDefaultCredentialsProvider
      val awsAccessKey = credentialsProvider.getCredentials.getAWSAccessKeyId
      val awsAccessSecret = credentialsProvider.getCredentials.getAWSSecretKey

      val clientBuilder = AmazonKinesisClientBuilder.standard
      clientBuilder.setCredentials(credentialsProvider)
      clientBuilder.setEndpointConfiguration(endPointConfiguration)
      val kinesisClient = clientBuilder.build()
      kinesisClient.createStream(streamName, 1)

      while (!kinesisClient.describeStream(streamName).getStreamDescription.getStreamStatus.equals("ACTIVE")) {
        Thread.sleep(50)
      }

      val records = SparkTestHelper.dataRows.zipWithIndex.map(row => {
        val putRecordsRequestEntry  = new PutRecordsRequestEntry()
        import java.nio.ByteBuffer
        putRecordsRequestEntry.setData(ByteBuffer.wrap(row._1.mkString("|").getBytes))
        putRecordsRequestEntry.setPartitionKey(s"partitionKey-${row._2}")
        putRecordsRequestEntry
      })
      val result = kinesisClient.putRecords(new PutRecordsRequest().withStreamName(streamName).withRecords(records.asJavaCollection))
      println(result)

      var executionComplete = false
      SparkTestHelper.pipelineListener = new PipelineListener {
        override def executionFinished(pipelines: List[Pipeline], pipelineContext: PipelineContext): Option[PipelineContext] = {
          assert(pipelines.lengthCompare(1) == 0)
          val params = pipelineContext.parameters.getParametersByPipelineId("1")
          assert(params.isDefined)
          assert(params.get.parameters.contains("PROCESS_STREAMING_DATA"))
          assert(params.get.parameters("PROCESS_STREAMING_DATA").asInstanceOf[PipelineStepResponse].primaryReturn.isDefined)
          assert(params.get.parameters("PROCESS_STREAMING_DATA").asInstanceOf[PipelineStepResponse]
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

      And("the kinesis spark listener is running")
      val args = List("--driverSetupClass", "com.acxiom.pipeline.drivers.SparkTestDriverSetup", "--pipeline", "basic",
        "--globalInput", "global-input-value", "--endPointURL", endPointURL, "--streamName", streamName, "--appName",
        appName, "--regionName", regionName, "--awsAccessKey", awsAccessKey, "--awsAccessSecret", awsAccessSecret)
      KinesisPipelineDriver.main(args.toArray)
      Then("5 records should be processed")
      assert(executionComplete)
    }
  }
}
