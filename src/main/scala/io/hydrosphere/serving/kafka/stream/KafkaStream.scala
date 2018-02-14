package io.hydrosphere.serving.kafka.stream

import org.apache.kafka.streams.kstream.{KStream, Predicate, ValueMapper}
import org.apache.logging.log4j.scala.Logging


object KafkaStream {
  def apply[K, V](kStream: KStream[K, V]) = new KafkaStream[K, V](kStream)
}

class KafkaStream[K, V](val underlying: KStream[K, V]) extends Stream[K, V] with Logging {

  override def mapV[V1](f: V => V1): KafkaStream[K, V1] = KafkaStream {
    underlying.mapValues[V1](new ValueMapper[V, V1] {
      override def apply(value: V): V1 = f(value)
    })
  }

  override def filterV(f: V => Boolean): KafkaStream[K, V] = KafkaStream {
    underlying.filter(new Predicate[K, V] {
      override def test(key: K, value: V): Boolean = f(value)
    })
  }

  def branchV(success: V => Boolean, failure: V => Boolean): DoubleStream[K, V] = {
    val array = underlying.branch(
      new Predicate[K, V] {
        override def test(key: K, value: V): Boolean = success(value)
      },
      new Predicate[K, V] {
        override def test(key: K, value: V): Boolean = failure(value)
      }
    )

    val successResult = array(0)
    val failureDesult = array(1)

    (KafkaStream(successResult).withLog(v => logger.debug(s"message handled: $v")),
      KafkaStream(failureDesult).withLog(v => logger.error(s"message failed: $v"))
    )
  }

  override def to(topicName: String): Unit = underlying.to(topicName)

}
