package com.tuananh.spark.streaming.training

import java.text.SimpleDateFormat
import java.util.{Calendar, Properties, UUID}
import com.google.gson.Gson
import com.mongodb.spark.MongoSpark
import kafka.serializer.StringDecoder
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.spark.sql.{SQLContext, SaveMode}
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}

object BicycleStreamingConsumer {
    case class BikeAggreration(bike_name: String, total: Int, date_time: String)

    var brokers = ""

    def main(args: Array[String]): Unit = {
        //Get the Kafka broker node
        brokers = util.Try(args(0)).getOrElse("localhost:9092")

        //Get the topic for consumer
        val outTopic = util.Try(args(1)).getOrElse("bike-data")

        val batchDuration = util.Try(args(2)).getOrElse("30").toInt //in second


        //Create streaming context from Spark
        val streamCtx = new StreamingContext(SparkCommon.conf, Seconds(batchDuration))
        val sparkCtx = streamCtx.sparkContext

        //Create SqlContext for using MongoDB
        lazy val sqlCtx = new SQLContext(sparkCtx)
        import sqlCtx.implicits._


        //Create Direct Kafka Streaming for Consumer
        val outTopicSet = Set(outTopic)
        val kafkaParams = Map[String, String](
            "bootstrap.servers" -> brokers,
            "key.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer"
        )

        val msg = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](
            streamCtx,
            kafkaParams,
            outTopicSet
        )

        /*----Code for process received data here---*/

        //Get the value from received message
        val value = msg.map(_._2)

        //Get the bicycle brand name
        val bicycles = value.map(x => x.split(",")(1))

        //Calculate the total bicycle brand name
        val bicyclesDStream = bicycles.map(bike => Tuple2(bike, 1))
        val aggregatedBicycles = bicyclesDStream.reduceByKey(_+_)

        //Print received data or data transformed from the received data
        aggregatedBicycles.print()

        //Retrieve each RDD and transform to create DataFrame with the schema is BikeAggreration class
        aggregatedBicycles.foreachRDD( rdd => {

            val today = Calendar.getInstance.getTime
            val formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

            //Mapping the RDD in the stream to new RDD with BikeAggreration class and convert to DataFrame
            val data = rdd.map(
                x => BikeAggreration(x._1, x._2, formatter.format(today))
            )

            //Insert data to MongoDB
            MongoSpark.save(data.toDF().write.mode(SaveMode.Append))
        })

        /*----Code for process received data here---*/

        //Start the stream
        streamCtx.start()
        streamCtx.awaitTermination()
    }
}