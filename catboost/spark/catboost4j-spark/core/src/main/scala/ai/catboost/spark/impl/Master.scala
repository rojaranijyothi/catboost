package ai.catboost.spark.impl

import collection.mutable
import concurrent.duration.Duration
import concurrent.{Await,Future}
import concurrent.ExecutionContext.Implicits.global

import scala.util.control.Breaks._

import java.io.{BufferedReader,InputStreamReader,PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file._

import java.util.concurrent.Callable
import java.util.regex.Pattern

import org.apache.commons.io.FileUtils

import org.apache.spark.sql.SparkSession

import ru.yandex.catboost.spark.catboost4j_spark.core.src.native_impl

import ai.catboost.CatBoostError
import ai.catboost.spark._

object Master {
  // use this method to create Master instances
  def apply(
    preprocessedTrainPool: Pool,
    preprocessedTestPools: Array[Pool],
    catBoostJsonParamsForMasterString: String
  ) : Master = {
    val savedPoolsFuture = Future {
      val threadCount = SparkHelpers.getThreadCountForDriver(preprocessedTrainPool.data.sparkSession)

      val trainPoolAsFile = DataHelpers.downloadQuantizedPoolToTempFile(
        preprocessedTrainPool,
        includeFeatures=false,
        threadCount
      )
      val testPoolsAsFiles = preprocessedTestPools.map {
        testPool => DataHelpers.downloadQuantizedPoolToTempFile(
          testPool,
          includeFeatures=true,
          threadCount
        )
      }.toArray

      (trainPoolAsFile, testPoolsAsFiles)
    }
    new Master(preprocessedTrainPool.data.sparkSession, savedPoolsFuture, catBoostJsonParamsForMasterString)
  }
}


class Master(
  val spark: SparkSession,
  val savedPoolsFuture : Future[(Path, Array[Path])],
  val catBoostJsonParamsForMasterString: String,

  // will be set in trainCallback, called from the trainingDriver's run()
  var nativeModelResult : native_impl.TFullModel = null
) {
  private def saveHostsListToFile(hostsFilePath: Path, workersInfo: Array[WorkerInfo]) = {
    val pw = new PrintWriter(hostsFilePath.toFile)
    try {
      for (workerInfo <- workersInfo) {
        pw.println(s"${workerInfo.host}:${workerInfo.port}")
      }
    } finally {
      pw.close
    }
  }

  /**
   * If master failed because of lost connection to workers throws  CatBoostWorkersConnectionLostException
   */
  def trainCallback(workersInfo: Array[WorkerInfo])  = {
    if (nativeModelResult != null) {
      throw new CatBoostError(
        "[Internal error] trainCallback is called again despite nativeModelResult already assigned"
      )
    }

    val tmpDirPath = Files.createTempDirectory("catboost_train")

    val hostsFilePath = tmpDirPath.resolve("worker_hosts.txt")
    saveHostsListToFile(hostsFilePath, workersInfo)
    val resultModelFilePath = tmpDirPath.resolve("result_model.cbm")

    val jsonParamsFile = tmpDirPath.resolve("json_params")
    Files.write(jsonParamsFile, catBoostJsonParamsForMasterString.getBytes(StandardCharsets.UTF_8))

    val args = mutable.ArrayBuffer[String](
      "--node-type", "Master",
      "--thread-count", SparkHelpers.getThreadCountForDriver(spark).toString,
      "--params-file", jsonParamsFile.toString,
      "--file-with-hosts", hostsFilePath.toString,
      "--hosts-already-contain-loaded-data",
      "--model-file", resultModelFilePath.toString
    )

    val driverNativeMemoryLimit = SparkHelpers.getDriverNativeMemoryLimit(spark)
    if (driverNativeMemoryLimit.isDefined) {
      args += ("--used-ram-limit", driverNativeMemoryLimit.get.toString)
    }

    val (savedTrainPool, savedTestPools) = Await.result(savedPoolsFuture, Duration.Inf)

    args += ("--learn-set", "spark-quantized://master-part:" + savedTrainPool.toString)
    if (!savedTestPools.isEmpty) {
      args += (
        "--test-set",
        savedTestPools.map(path => "spark-quantized://master-part:" + path).mkString(",")
      )
    }

    val masterAppProcess = RunClassInNewProcess(
      MasterApp.getClass,
      args = Some(args.toArray),
      redirectOutput = Some(ProcessBuilder.Redirect.INHERIT),
      redirectError = Some(ProcessBuilder.Redirect.PIPE)
    )

    /*
     * Parse PAR errors from stderr
     *  Very hackish but there's no other way to get information why the process was aborted
     */
    val failedBecauseOfWorkerConnectionLostRegexp = Pattern.compile(
      "^FAIL.*(got unexpected network error, no retries rest|reply isn't OK)$"
    )

    var failedBecauseOfWorkerConnectionLost = false

    val outputReader = new BufferedReader(new InputStreamReader(masterAppProcess.getInputStream()))
    try {
      breakable {
        while (true) {
          val line = outputReader.readLine
          if (line == null) {
            break
          }
          println("[CatBoost Master] " + line)

          if (failedBecauseOfWorkerConnectionLostRegexp.matcher(line).matches) {
            failedBecauseOfWorkerConnectionLost = true
          }
        }
      }
    } finally {
      outputReader.close
    }

    val returnValue = masterAppProcess.waitFor
    if (returnValue != 0) {
      if (failedBecauseOfWorkerConnectionLost) {
        throw new CatBoostWorkersConnectionLostException("")
      }
      throw new CatBoostError(s"Master process failed: exited with code $returnValue")
    }

    nativeModelResult = native_impl.native_impl.ReadModel(resultModelFilePath.toString)

    FileUtils.deleteDirectory(tmpDirPath.toFile)
  }
}
