/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.tuning


import scalaj.http._ 
import org.json4s._
import collection.mutable
import org.json4s.native.JsonMethods._
import native.Serialization.{read, write => swrite}


import java.util.{List => JList}

import scala.collection.JavaConverters._

import com.github.fommil.netlib.F2jBLAS
import org.apache.hadoop.fs.Path
import org.json4s.DefaultFormats._

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.internal.Logging
import org.apache.spark.ml._
import org.apache.spark.ml.evaluation.Evaluator
import org.apache.spark.ml.param._
import org.apache.spark.ml.util._
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.sql.types.StructType

/**
 * Params for [[CrossValidator]] and [[CrossValidatorModel]].
 */
private[ml] trait CrossValidatorParams extends ValidatorParams {
  /**
   * Param for number of folds for cross validation.  Must be >= 2.
   * Default: 3
   *
   * @group param
   */
  val numFolds: IntParam = new IntParam(this, "numFolds",
    "number of folds for cross validation (>= 2)", ParamValidators.gtEq(2))

  /** @group getParam */
  def getNumFolds: Int = $(numFolds)

  setDefault(numFolds -> 3)
}

/**
 * :: Experimental ::
 * K-fold cross validation.
 */
@Since("1.2.0")
@Experimental
class CrossValidator @Since("1.2.0") (@Since("1.4.0") override val uid: String)
  extends Estimator[CrossValidatorModel]
  with CrossValidatorParams with MLWritable with Logging {

  implicit val formats = DefaultFormats

  case class SigBounds(max: Double, min: Double)
  case class SigParameters(name: String, bounds:SigBounds, `type`: String)  
  case class SigExperiment(name: String, parameters: Array[SigParameters])

  
  var n_iter: Int = 0
  var token: String = ""
  var experiment_id: String= ""
  var suggestion_id: String = ""
  
  @Since("1.2.0")
  def this() = this(Identifiable.randomUID("cv"))

  private val f2jBLAS = new F2jBLAS

  def base_opt(experiment_id : String): String = {
    s"https://api.sigopt.com/v1/experiments/$experiment_id/suggestions"
  }

  def base_obs(experiment_id: String): String = {
    s"https://api.sigopt.com/v1/experiments/$experiment_id/observations"
  }
  /** @group setParam */
  @Since("1.2.0")
  def setEstimator(value: Estimator[_]): this.type = set(estimator, value)

  /** @group setParam */
  @Since("1.2.0")
  def setEstimatorParamMaps(value: Array[ParamMap]): this.type = set(estimatorParamMaps, value)
  
  /** @group setParam */
  @Since("1.2.0")
  def setEvaluator(value: Evaluator): this.type = set(evaluator, value)

  /** @group setParam */
  @Since("1.2.0")
  def setNumFolds(value: Int): this.type = set(numFolds, value)

  /** @group setParam */
  @Since("2.0.0")
  def setSeed(value: Long): this.type = set(seed, value)

  def setSigCV(name: String, token:String, iters:Int, bound_val: Array[(String, Double, Double, String)]) = {
    this.token = token
    this.n_iter = iters
    implicit val formats = DefaultFormats
    val post_url : String = "https://api.sigopt.com/v1/experiments"  //Endpoint for establishing an experiment
    val sigarray = mutable.ArrayBuffer[SigParameters]()
    for(i <- bound_val){sigarray += SigParameters(i._1.toString, SigBounds(i._2, i._3), i._4.toString)}
    val json_experiment:String = swrite(SigExperiment(name, sigarray.toArray))
    val experiment_response = Http(post_url).auth(token, "").postData(json_experiment).headers(Seq("content-type" -> "application/json")).asString.body
    val experiment_identity = (parse(experiment_response) \\ "id").extract[String] 
    this. experiment_id = experiment_identity  //with bounds set and an experiment set save the id for further us
  }

  def askSuggestion(estimator: Estimator[_])= {
    this.setEstimatorParamMaps(Array(estimator.extractParamMap))
    implicit val formats = DefaultFormats
    val paramGrid = mutable.Map.empty[Param[Any], Any]
    var suggestion_url: String = this.base_opt((this.experiment_id))
    val suggestion_response = parse(Http(suggestion_url).postData("").auth(this.token +":","").asString.body)
    var suggest_paramMap = ((suggestion_response \\ "data")(0) \\ "assignments").extract[Map[String, Double]]  //pulling out the most recent suggestions 
    this.suggestion_id = ((suggestion_response \\ "data")(0) \\ "id").extract[String]                       //identifying the current suggestion
    for (z <- suggest_paramMap){
      var param = z._1
      paramGrid.put(estimator.getParam(s"$param"), z._2)
    }
    
    var paramMaps = Array(new ParamMap)
    for((k,v) <- paramGrid){paramMaps.map(_.put(k.asInstanceOf[Param[Any]], v))}
    //suggest_paramMap.foreach(z => paramMaps.put(estimator.getParam(z._1), z._2))
   this.setEstimatorParamMaps(paramMaps.toArray)
  }

  def observeSuggestion(est: Estimator[_], metric: Double)= {
    case class Observe(suggestion: String, value: Double)
    var observation_url: String = this.base_obs(this.experiment_id)
    (Http(observation_url).postData(swrite(Observe(this.suggestion_id, metric))).auth(this.token, "").headers(Seq("content-type" -> "application/json"))).asString.body
    this.askSuggestion(est)
  }

  def SigFit(dataset: Dataset[_]): CrossValidatorModel = {
      val schema = dataset.schema
      transformSchema(schema)
      val sparkSession = dataset.sparkSession
      val est = $(estimator)
      val eval = $(evaluator)
      //need to grab the suggestion first
      val epm = $(estimatorParamMaps)
      val numModels = this.n_iter 
      val metrics = new Array[Double](numModels)
      val splits = MLUtils.kFold(dataset.toDF.rdd, $(numFolds), $(seed))
      splits.zipWithIndex.foreach { case ((training, validation), splitIndex) =>
        val trainingDataset = sparkSession.createDataFrame(training, schema).cache()
        val validationDataset = sparkSession.createDataFrame(validation, schema).cache()
        // multi-model training
        //logDebug(s"Train split $splitIndex with multiple sets of parameters.")
        val models = est.fit(trainingDataset, epm(0)) //.asInstanceOf[Model[_]]
        trainingDataset.unpersist()
        var i = 0 
        while (i < numModels) {
          // TODO: duplicate evaluator to take extra params from input
          val metric = eval.evaluate((models.asInstanceOf[Model[_]]).transform(validationDataset, epm(0)))
          metrics(i) += metric
          this.observeSuggestion(est, metric)
          //logDebug(s"Got metric $metric for model trained with ${epm}.")
          i += 1
        }
        validationDataset.unpersist()
      }
       f2jBLAS.dscal(numModels, 1.0 / $(numFolds), metrics, 1)
	   logInfo(s"Average cross-validation metrics: ${metrics.toSeq}")
	   val (bestMetric, bestIndex) =
	      if (eval.isLargerBetter) metrics.zipWithIndex.maxBy(_._1)
	      else metrics.zipWithIndex.minBy(_._1)
	   //logInfo(s"Best set of parameters:\n${epm}")
	   logInfo(s"Best cross-validation metric: $bestMetric.")
	   val bestModel = (est.fit(dataset, epm(0))).asInstanceOf[Model[_]]
	   copyValues(new CrossValidatorModel(uid, bestModel, metrics).setParent(this))

     // f2jBLAS.dscal(numModels, 1.0 / $(numFolds), metrics, 1)
      //logInfo(s"Average cross-validation metrics: ${metrics.toSeq}")
      // val (bestMetric, bestIndex) =
      //   if (eval.isLargerBetter) metrics.zipWithIndex.maxBy(_._1)
      //   else metrics.zipWithIndex.minBy(_._1)
      //logInfo(s"Best set of parameters:\n${epm}")
     // logInfo(s"Best cross-validation metric: $bestMetric.")
      // val bestModel = est.fit(dataset, epm.asInstanceOf[Model[_]])
       //val bestMetric = eval.evaluate(modin.transform(validationDataset, epm))
       //val bestModel = est.fit(dataset, epm).asInstanceOf[Model[_]]
    }
     // override def transformSchema(schema: StructType): StructType = transformSchemaImpl(schema)
    //   override def copy(extra: ParamMap): CrossValidator = {
    //   val copied = defaultCopy(extra).asInstanceOf[CrossValidator]
    //   if (copied.isDefined(estimator)) {
    //     copied.setEstimator(copied.getEstimator.copy(extra))
    //   }
    //   if (copied.isDefined(evaluator)) {
    //     copied.setEvaluator(copied.getEvaluator.copy(extra))
    //   }
    //   copied
    // }

  @Since("2.0.0")
  override def fit(dataset: Dataset[_]): CrossValidatorModel = {
    val schema = dataset.schema
    transformSchema(schema, logging = true)
    val sparkSession = dataset.sparkSession
    val est = $(estimator)
    val eval = $(evaluator)
    val epm = $(estimatorParamMaps)
    val numModels = epm.length
    val metrics = new Array[Double](epm.length)
    val splits = MLUtils.kFold(dataset.toDF.rdd, $(numFolds), $(seed))
    splits.zipWithIndex.foreach { case ((training, validation), splitIndex) =>
      val trainingDataset = sparkSession.createDataFrame(training, schema).cache()
      val validationDataset = sparkSession.createDataFrame(validation, schema).cache()
      // multi-model training
      logDebug(s"Train split $splitIndex with multiple sets of parameters.")
      val models = est.fit(trainingDataset, epm).asInstanceOf[Seq[Model[_]]]
      trainingDataset.unpersist()
      var i = 0
      while (i < numModels) {
        // TODO: duplicate evaluator to take extra params from input
        val metric = eval.evaluate(models(i).transform(validationDataset, epm(i)))
        logDebug(s"Got metric $metric for model trained with ${epm(i)}.")
        metrics(i) += metric
        i += 1
      }
      validationDataset.unpersist()
    }
    f2jBLAS.dscal(numModels, 1.0 / $(numFolds), metrics, 1)
    logInfo(s"Average cross-validation metrics: ${metrics.toSeq}")
    val (bestMetric, bestIndex) =
      if (eval.isLargerBetter) metrics.zipWithIndex.maxBy(_._1)
      else metrics.zipWithIndex.minBy(_._1)
    logInfo(s"Best set of parameters:\n${epm(bestIndex)}")
    logInfo(s"Best cross-validation metric: $bestMetric.")
    val bestModel = est.fit(dataset, epm(bestIndex)).asInstanceOf[Model[_]]
    copyValues(new CrossValidatorModel(uid, bestModel, metrics).setParent(this))
  }

  @Since("1.4.0")
  override def transformSchema(schema: StructType): StructType = transformSchemaImpl(schema)

  @Since("1.4.0")
  override def copy(extra: ParamMap): CrossValidator = {
    val copied = defaultCopy(extra).asInstanceOf[CrossValidator]
    if (copied.isDefined(estimator)) {
      copied.setEstimator(copied.getEstimator.copy(extra))
    }
    if (copied.isDefined(evaluator)) {
      copied.setEvaluator(copied.getEvaluator.copy(extra))
    }
    copied
  }

  // Currently, this only works if all [[Param]]s in [[estimatorParamMaps]] are simple types.
  // E.g., this may fail if a [[Param]] is an instance of an [[Estimator]].
  // However, this case should be unusual.
  @Since("1.6.0")
  override def write: MLWriter = new CrossValidator.CrossValidatorWriter(this)
}

@Since("1.6.0")
object CrossValidator extends MLReadable[CrossValidator] {

  @Since("1.6.0")
  override def read: MLReader[CrossValidator] = new CrossValidatorReader

  @Since("1.6.0")
  override def load(path: String): CrossValidator = super.load(path)

  private[CrossValidator] class CrossValidatorWriter(instance: CrossValidator) extends MLWriter {

    ValidatorParams.validateParams(instance)

    override protected def saveImpl(path: String): Unit =
      ValidatorParams.saveImpl(path, instance, sc)
  }

  private class CrossValidatorReader extends MLReader[CrossValidator] {

    /** Checked against metadata when loading model */
    private val className = classOf[CrossValidator].getName

    override def load(path: String): CrossValidator = {
      implicit val format = DefaultFormats

      val (metadata, estimator, evaluator, estimatorParamMaps) =
        ValidatorParams.loadImpl(path, sc, className)
      val numFolds = (metadata.params \ "numFolds").extract[Int]
      val seed = (metadata.params \ "seed").extract[Long]
      new CrossValidator(metadata.uid)
        .setEstimator(estimator)
        .setEvaluator(evaluator)
        .setEstimatorParamMaps(estimatorParamMaps)
        .setNumFolds(numFolds)
        .setSeed(seed)
    }
  }
}

/**
 * :: Experimental ::
 * Model from k-fold cross validation.
 *
 * @param bestModel The best model selected from k-fold cross validation.
 * @param avgMetrics Average cross-validation metrics for each paramMap in
 *                   [[CrossValidator.estimatorParamMaps]], in the corresponding order.
 */
@Since("1.2.0")
@Experimental
class CrossValidatorModel private[ml] (
    @Since("1.4.0") override val uid: String,
    @Since("1.2.0") val bestModel: Model[_],
    @Since("1.5.0") val avgMetrics: Array[Double])
  extends Model[CrossValidatorModel] with CrossValidatorParams with MLWritable {

  /** A Python-friendly auxiliary constructor. */
  private[ml] def this(uid: String, bestModel: Model[_], avgMetrics: JList[Double]) = {
    this(uid, bestModel, avgMetrics.asScala.toArray)
  }

  @Since("2.0.0")
  override def transform(dataset: Dataset[_]): DataFrame = {
    transformSchema(dataset.schema, logging = true)
    bestModel.transform(dataset)
  }

  @Since("1.4.0")
  override def transformSchema(schema: StructType): StructType = {
    bestModel.transformSchema(schema)
  }

  @Since("1.4.0")
  override def copy(extra: ParamMap): CrossValidatorModel = {
    val copied = new CrossValidatorModel(
      uid,
      bestModel.copy(extra).asInstanceOf[Model[_]],
      avgMetrics.clone())
    copyValues(copied, extra).setParent(parent)
  }

  @Since("1.6.0")
  override def write: MLWriter = new CrossValidatorModel.CrossValidatorModelWriter(this)
}

@Since("1.6.0")
object CrossValidatorModel extends MLReadable[CrossValidatorModel] {

  @Since("1.6.0")
  override def read: MLReader[CrossValidatorModel] = new CrossValidatorModelReader

  @Since("1.6.0")
  override def load(path: String): CrossValidatorModel = super.load(path)

  private[CrossValidatorModel]
  class CrossValidatorModelWriter(instance: CrossValidatorModel) extends MLWriter {

    ValidatorParams.validateParams(instance)

    override protected def saveImpl(path: String): Unit = {
      import org.json4s.JsonDSL._
      val extraMetadata = "avgMetrics" -> instance.avgMetrics.toSeq
      ValidatorParams.saveImpl(path, instance, sc, Some(extraMetadata))
      val bestModelPath = new Path(path, "bestModel").toString
      instance.bestModel.asInstanceOf[MLWritable].save(bestModelPath)
    }
  }

  private class CrossValidatorModelReader extends MLReader[CrossValidatorModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[CrossValidatorModel].getName

    override def load(path: String): CrossValidatorModel = {
      implicit val format = DefaultFormats

      val (metadata, estimator, evaluator, estimatorParamMaps) =
        ValidatorParams.loadImpl(path, sc, className)
      val numFolds = (metadata.params \ "numFolds").extract[Int]
      val seed = (metadata.params \ "seed").extract[Long]
      val bestModelPath = new Path(path, "bestModel").toString
      val bestModel = DefaultParamsReader.loadParamsInstance[Model[_]](bestModelPath, sc)
      val avgMetrics = (metadata.metadata \ "avgMetrics").extract[Seq[Double]].toArray
      val model = new CrossValidatorModel(metadata.uid, bestModel, avgMetrics)
      model.set(model.estimator, estimator)
        .set(model.evaluator, evaluator)
        .set(model.estimatorParamMaps, estimatorParamMaps)
        .set(model.numFolds, numFolds)
        .set(model.seed, seed)
    }
  }
}