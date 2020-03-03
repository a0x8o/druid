/**
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
package kafka.controller

import java.util.Properties

import kafka.api.{ApiVersion, KAFKA_0_10_0_IV1, LeaderAndIsr}
import kafka.cluster.{Broker, EndPoint}
import kafka.common.TopicAndPartition
import kafka.utils.Json
import org.apache.zookeeper.data.Stat

import scala.collection.Seq

object ControllerZNode {
  def path = "/controller"
  def encode(brokerId: Int, timestamp: Long): Array[Byte] =
    Json.encode(Map("version" -> 1, "brokerid" -> brokerId, "timestamp" -> timestamp.toString)).getBytes("UTF-8")
  def decode(bytes: Array[Byte]): Option[Int] = Json.parseFull(new String(bytes, "UTF-8")).map { js =>
    js.asJsonObject("brokerid").to[Int]
  }
}

object ControllerEpochZNode {
  def path = "/controller_epoch"
  def encode(epoch: Int): Array[Byte] = epoch.toString.getBytes("UTF-8")
  def decode(bytes: Array[Byte]) : Int = new String(bytes, "UTF-8").toInt
}

object ConfigZNode {
  def path = "/config"
  def encode: Array[Byte] = null
}

object BrokersZNode {
  def path = "/brokers"
  def encode: Array[Byte] = null
}

object BrokerIdsZNode {
  def path = s"${BrokersZNode.path}/ids"
  def encode: Array[Byte] = null
}

object BrokerIdZNode {
  def path(id: Int) = s"${BrokerIdsZNode.path}/$id"
  def encode(id: Int,
             host: String,
             port: Int,
             advertisedEndpoints: Seq[EndPoint],
             jmxPort: Int,
             rack: Option[String],
             apiVersion: ApiVersion): Array[Byte] = {
    val version = if (apiVersion >= KAFKA_0_10_0_IV1) 4 else 2
    Broker.toJson(version, id, host, port, advertisedEndpoints, jmxPort, rack).getBytes("UTF-8")
  }

  def decode(id: Int, bytes: Array[Byte]): Broker = {
    Broker.createBroker(id, new String(bytes, "UTF-8"))
  }
}

object TopicsZNode {
  def path = s"${BrokersZNode.path}/topics"
  def encode: Array[Byte] = null
}

object TopicZNode {
  def path(topic: String) = s"${TopicsZNode.path}/$topic"
  def encode(assignment: Map[TopicAndPartition, Seq[Int]]): Array[Byte] = {
    val assignmentJson = assignment.map { case (partition, replicas) => partition.partition.toString -> replicas }
    Json.encode(Map("version" -> 1, "partitions" -> assignmentJson)).getBytes("UTF-8")
  }
  def decode(topic: String, bytes: Array[Byte]): Map[TopicAndPartition, Seq[Int]] = {
    Json.parseFull(new String(bytes, "UTF-8")).flatMap { js =>
      val assignmentJson = js.asJsonObject
      val partitionsJsonOpt = assignmentJson.get("partitions").map(_.asJsonObject)
      partitionsJsonOpt.map { partitionsJson =>
        partitionsJson.iterator.map { case (partition, replicas) =>
          TopicAndPartition(topic, partition.toInt) -> replicas.to[Seq[Int]]
        }
      }
    }.map(_.toMap).getOrElse(Map.empty)
  }
}

object TopicPartitionsZNode {
  def path(topic: String) = s"${TopicZNode.path(topic)}/partitions"
  def encode: Array[Byte] = null
}

object TopicPartitionZNode {
  def path(partition: TopicAndPartition) = s"${TopicPartitionsZNode.path(partition.topic)}/${partition.partition}"
  def encode: Array[Byte] = null
}

object TopicPartitionStateZNode {
  def path(partition: TopicAndPartition) = s"${TopicPartitionZNode.path(partition)}/state"
  def encode(leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch): Array[Byte] = {
    val leaderAndIsr = leaderIsrAndControllerEpoch.leaderAndIsr
    val controllerEpoch = leaderIsrAndControllerEpoch.controllerEpoch
    Json.encode(Map("version" -> 1, "leader" -> leaderAndIsr.leader, "leader_epoch" -> leaderAndIsr.leaderEpoch,
      "controller_epoch" -> controllerEpoch, "isr" -> leaderAndIsr.isr)).getBytes("UTF-8")
  }
  def decode(bytes: Array[Byte], stat: Stat): Option[LeaderIsrAndControllerEpoch] = {
    Json.parseFull(new String(bytes, "UTF-8")).map { js =>
      val leaderIsrAndEpochInfo = js.asJsonObject
      val leader = leaderIsrAndEpochInfo("leader").to[Int]
      val epoch = leaderIsrAndEpochInfo("leader_epoch").to[Int]
      val isr = leaderIsrAndEpochInfo("isr").to[List[Int]]
      val controllerEpoch = leaderIsrAndEpochInfo("controller_epoch").to[Int]
      val zkPathVersion = stat.getVersion
      LeaderIsrAndControllerEpoch(LeaderAndIsr(leader, epoch, isr, zkPathVersion), controllerEpoch)
    }
  }
}

object ConfigEntityTypeZNode {
  def path(entityType: String) = s"${ConfigZNode.path}/$entityType"
  def encode: Array[Byte] = null
}

object ConfigEntityZNode {
  def path(entityType: String, entityName: String) = s"${ConfigEntityTypeZNode.path(entityType)}/$entityName"
  def encode(config: Properties): Array[Byte] = {
    import scala.collection.JavaConverters._
    Json.encode(Map("version" -> 1, "config" -> config.asScala)).getBytes("UTF-8")
  }
  def decode(bytes: Array[Byte]): Option[Properties] = {
    Json.parseFull(new String(bytes, "UTF-8")).map { js =>
      val configOpt = js.asJsonObjectOption.flatMap(_.get("config").flatMap(_.asJsonObjectOption))
      val props = new Properties()
      configOpt.foreach(config => config.iterator.foreach { case (k, v) => props.setProperty(k, v.to[String]) })
      props
    }
  }
}

object IsrChangeNotificationZNode {
  def path = "/isr_change_notification"
  def encode: Array[Byte] = null
}

object IsrChangeNotificationSequenceZNode {
  val SequenceNumberPrefix = "isr_change_"
  def path(sequenceNumber: String) = s"${IsrChangeNotificationZNode.path}/$SequenceNumberPrefix$sequenceNumber"
  def encode(partitions: Set[TopicAndPartition]): Array[Byte] = {
    val partitionsJson = partitions.map(partition => Map("topic" -> partition.topic, "partition" -> partition.partition))
    Json.encode(Map("version" -> IsrChangeNotificationListener.version, "partitions" -> partitionsJson)).getBytes("UTF-8")
  }

  def decode(bytes: Array[Byte]): Set[TopicAndPartition] = {
    Json.parseFull(new String(bytes, "UTF-8")).map { js =>
      val partitionsJson = js.asJsonObject("partitions").asJsonArray
      partitionsJson.iterator.map { partitionsJson =>
        val partitionJson = partitionsJson.asJsonObject
        val topic = partitionJson("topic").to[String]
        val partition = partitionJson("partition").to[Int]
        TopicAndPartition(topic, partition)
      }
    }
  }.map(_.toSet).getOrElse(Set.empty)
  def sequenceNumber(path: String) = path.substring(path.lastIndexOf(SequenceNumberPrefix) + SequenceNumberPrefix.length)
}

object LogDirEventNotificationZNode {
  def path = "/log_dir_event_notification"
  def encode: Array[Byte] = null
}

object LogDirEventNotificationSequenceZNode {
  val SequenceNumberPrefix = "log_dir_event_"
  val LogDirFailureEvent = 1
  def path(sequenceNumber: String) = s"${LogDirEventNotificationZNode.path}/$SequenceNumberPrefix$sequenceNumber"
  def encode(brokerId: Int) =
    Json.encode(Map("version" -> 1, "broker" -> brokerId, "event" -> LogDirFailureEvent)).getBytes("UTF-8")
  def decode(bytes: Array[Byte]): Option[Int] = Json.parseFull(new String(bytes, "UTF-8")).map { js =>
    js.asJsonObject("broker").to[Int]
  }
  def sequenceNumber(path: String) = path.substring(path.lastIndexOf(SequenceNumberPrefix) + SequenceNumberPrefix.length)
}

object AdminZNode {
  def path = "/admin"
  def encode: Array[Byte] = null
}

object DeleteTopicsZNode {
  def path = s"${AdminZNode.path}/delete_topics"
  def encode: Array[Byte] = null
}

object DeleteTopicsTopicZNode {
  def path(topic: String) = s"${DeleteTopicsZNode.path}/$topic"
  def encode: Array[Byte] = null
}

object ReassignPartitionsZNode {
  def path = s"${AdminZNode.path}/reassign_partitions"
  def encode(reassignment: Map[TopicAndPartition, Seq[Int]]): Array[Byte] = {
    val reassignmentJson = reassignment.map { case (TopicAndPartition(topic, partition), replicas) =>
      Map("topic" -> topic, "partition" -> partition, "replicas" -> replicas)
    }
    Json.encode(Map("version" -> 1, "partitions" -> reassignmentJson)).getBytes("UTF-8")
  }
  def decode(bytes: Array[Byte]): Map[TopicAndPartition, Seq[Int]] = Json.parseFull(new String(bytes, "UTF-8")).flatMap { js =>
    val reassignmentJson = js.asJsonObject
    val partitionsJsonOpt = reassignmentJson.get("partitions")
    partitionsJsonOpt.map { partitionsJson =>
      partitionsJson.asJsonArray.iterator.map { partitionFieldsJs =>
        val partitionFields = partitionFieldsJs.asJsonObject
        val topic = partitionFields("topic").to[String]
        val partition = partitionFields("partition").to[Int]
        val replicas = partitionFields("replicas").to[Seq[Int]]
        TopicAndPartition(topic, partition) -> replicas
      }
    }
  }.map(_.toMap).getOrElse(Map.empty)
}

object PreferredReplicaElectionZNode {
  def path = s"${AdminZNode.path}/preferred_replica_election"
  def encode(partitions: Set[TopicAndPartition]): Array[Byte] =
    Json.encode(Map("version" -> 1, "partitions" -> partitions.map(tp => Map("topic" -> tp.topic, "partition" -> tp.partition)))).getBytes("UTF-8")
  def decode(bytes: Array[Byte]): Set[TopicAndPartition] = Json.parseFull(new String(bytes, "UTF-8")).map { js =>
    val partitionsJson = js.asJsonObject("partitions").asJsonArray
    partitionsJson.iterator.map { partitionsJson =>
      val partitionJson = partitionsJson.asJsonObject
      val topic = partitionJson("topic").to[String]
      val partition = partitionJson("partition").to[Int]
      TopicAndPartition(topic, partition)
    }
  }.map(_.toSet).getOrElse(Set.empty)
}