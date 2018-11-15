package io.hydrosphere.serving.kafka

import akka.actor.ActorRef
import io.grpc.ServerBuilder
import io.hydrosphere.serving.grpc.Headers
import io.hydrosphere.serving.kafka.config.Configuration
import io.hydrosphere.serving.kafka.grpc.PredictionGrpcApi
import io.hydrosphere.serving.kafka.kafka_messages.KafkaServingMessage
import io.hydrosphere.serving.kafka.predict._
import io.hydrosphere.serving.kafka.stream.{PredictTransformer, Producer}
import io.hydrosphere.serving.kafka.utils.ServerBuilderWrapper
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext


object Flow {
  def apply()(
    implicit kafkaServing: KafkaServingStream,
    applicationUpdateService: UpdateService[Seq[Application]],
    predictor: PredictService,
    predictionApi: PredictionGrpcApi,
    config: Configuration,
    producer: Producer[Array[Byte], KafkaServingMessage]
  ): Flow = {
    val flow = new Flow()
    flow.start()
    flow
  }
}

class Flow()(
  implicit kafkaServing: KafkaServingStream,
  applicationUpdateService: UpdateService[Seq[Application]],
  predictor: PredictService,
  config: Configuration,
  predictionApi: PredictionGrpcApi,
  producer: Producer[Array[Byte], KafkaServingMessage]
) extends Logging {

  private final val grpcPort = config.grpc.port

  final private val builder = ServerBuilderWrapper(ServerBuilder.forPort(grpcPort))
    .addService(PredictionServiceGrpc.bindService(predictionApi, scala.concurrent.ExecutionContext.global))

  Headers.interceptors.foreach(builder.intercept)

  final val server = builder.build

  def start(): Unit = {
    logger.info("Starting kafka serving app")
    kafkaServing.streamForAll {
      case (app, stream) =>
        stream
          .filterV(_.requestOrError.isRequest)
          .transformV(() => new PredictTransformer(predictor, app))
          .branchV(_.requestOrError.isRequest, _.requestOrError.isError)
    }

    server.start()
    logger.info(s"grpc server on port $grpcPort started")
    server.awaitTermination()
  }

  def stop(): Unit = {
    if (!server.isShutdown) {
      server.shutdownNow()
    }

    if (!server.isTerminated) {
      server.awaitTermination()
    }
    logger.info(s"grpc server on port $grpcPort stopped")
  }
}