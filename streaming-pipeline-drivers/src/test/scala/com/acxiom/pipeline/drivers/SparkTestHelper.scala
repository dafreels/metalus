package com.acxiom.pipeline.drivers

import com.acxiom.pipeline._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}

object SparkTestHelper {
  val MASTER = "local[2]"
  val APPNAME = "file-steps-spark"
  var sparkConf: SparkConf = _
  var sparkSession: SparkSession = _
  var pipelineListener: PipelineListener = _

  val dataRows = List(List("1", "2", "3", "4", "5"),
    List("6", "7", "8", "9", "10"),
    List("11", "12", "13", "14", "15"),
    List("16", "17", "18", "19", "20"),
    List("21", "22", "23", "24", "25"))

  val MESSAGE_PROCESSING_STEP = PipelineStep(Some("PROCESS_STREAMING_DATA"), Some("Parses Streaming data"), None, Some("Pipeline"),
    Some(List(Parameter(Some("string"), Some("dataFrame"), Some(true), None, Some("!initialDataFrame")))),
    Some(EngineMeta(Some("MockTestSteps.processIncomingData"))), None)
  val BASIC_PIPELINE = List(Pipeline(Some("1"), Some("Basic Pipeline"), Some(List(MESSAGE_PROCESSING_STEP))))
}

case class SparkTestDriverSetup(parameters: Map[String, Any]) extends DriverSetup {

  val ctx = PipelineContext(Some(SparkTestHelper.sparkConf), Some(SparkTestHelper.sparkSession), Some(parameters),
    PipelineSecurityManager(),
    PipelineParameters(List(PipelineParameter("0", Map[String, Any]()), PipelineParameter("1", Map[String, Any]()))),
    Some(if (parameters.contains("stepPackages")) {
      parameters("stepPackages").asInstanceOf[String]
        .split(",").toList
    } else {
      List("com.acxiom.pipeline.steps", "com.acxiom.pipeline.drivers")
    }),
    PipelineStepMapper(),
    Some(SparkTestHelper.pipelineListener),
    Some(SparkTestHelper.sparkSession.sparkContext.collectionAccumulator[PipelineStepMessage]("stepMessages")))

  override def pipelines: List[Pipeline] = {
    parameters.getOrElse("pipeline", "basic") match {
      case "basic" => SparkTestHelper.BASIC_PIPELINE
    }
  }

  override def initialPipelineId: String = ""

  override def pipelineContext: PipelineContext = ctx
}

object MockTestSteps {
  val ZERO = 0
  val ONE = 1
  val FIVE = 5

  def processIncomingData(dataFrame: DataFrame, pipelineContext: PipelineContext): PipelineStepResponse = {
    val stepId = pipelineContext.getGlobalString("stepId").getOrElse("")
    val pipelineId = pipelineContext.getGlobalString("pipelineId").getOrElse("")
    val count = dataFrame.count()
    if (count != FIVE) {
      pipelineContext.addStepMessage(PipelineStepMessage(s"Row count was wrong $count", stepId, pipelineId, PipelineStepMessageType.error))
    }

    val fieldDelimiter = pipelineContext.getGlobalString("fieldDelimiter").getOrElse(",")
    val stepMessages = pipelineContext.stepMessages.get
    dataFrame.foreach(row => {
      val columns = row.getString(ONE).split(fieldDelimiter.toCharArray()(0))
      if (columns.lengthCompare(FIVE) != ZERO) {
        stepMessages.add(PipelineStepMessage(s"Column count was wrong: ${columns.length}",
          stepId, pipelineId, PipelineStepMessageType.error))
      }
    })

    assert(dataFrame.select("topic").distinct().collect()(0).getString(ZERO) == pipelineContext.globals.get("topics"))

    PipelineStepResponse(Some(true), None)
  }
}