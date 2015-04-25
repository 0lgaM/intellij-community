/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.reactiveidea

import com.corundumstudio.socketio.Configuration
import com.corundumstudio.socketio.SocketIOClient
import com.corundumstudio.socketio.SocketIOServer
import com.corundumstudio.socketio.listener.ConnectListener
import com.corundumstudio.socketio.listener.DisconnectListener
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.nkzawa.emitter.Emitter
import com.github.nkzawa.socketio.client.IO
import com.github.nkzawa.socketio.client.Socket
import com.intellij.util.ui.UIUtil
import com.jetbrains.reactivemodel.*
import com.jetbrains.reactivemodel.models.*
import com.jetbrains.reactivemodel.util.Lifetime
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.HashMap

fun serverModel(lifetime: Lifetime, port: Int, onConnect: (ReactiveModel) -> Unit): SocketIOServer {
  val config = Configuration()
  config.setHostname("localhost")
  config.setPort(port)

  val server = SocketIOServer(config)


  val reactiveModel = ReactiveModel(lifetime) { diff ->
    val jsonObj = toJson(diff)
    val jsonStr = jsonObj.toString()

    val jsonNode = ObjectMapper().readTree(jsonStr)
    server.getBroadcastOperations().sendEvent("diff", jsonNode)
  }

  server.addConnectListener(object : ConnectListener {
    override fun onConnect(client: SocketIOClient) {
      UIUtil.invokeLaterIfNeeded {
        onConnect(reactiveModel)
      }
    }
  })


  server.addEventListener("diff", javaClass<JsonNode>(), { socketIOClient, json, ackRequest ->
    val diff = toDiff(JSONObject(json.toString()))
    UIUtil.invokeLaterIfNeeded {
//      println("applying diff on server $diff")
      reactiveModel.performTransaction { m ->
        m.patch(diff)
      }
    }
  })

  server.addDisconnectListener(object : DisconnectListener {
    override fun onDisconnect(client: SocketIOClient) {
    }
  });

  server.start()

  lifetime += {
    server.stop()
  }
  return server
}

fun clientModel(url: String, lifetime: Lifetime): ReactiveModel {

  val socket = IO.socket(url);

  val reactiveModel = ReactiveModel(lifetime) { diff ->
    socket.emit("diff", toJson(diff))
  }

  socket
      .on(Socket.EVENT_CONNECT, object : Emitter.Listener {
        override fun call(vararg p0: Any?) {
          println("client connect")
        }
      })
      .on("diff", object : Emitter.Listener {
        override fun call(vararg p0: Any?) {
          val json = p0[0] as JSONObject
          val diff = toDiff(json)
          UIUtil.invokeLaterIfNeeded {
            reactiveModel.performTransaction { m ->
//              println("apply diff $diff")
              m.patch(diff)
            }
          }
        }
      })
      .on(Socket.EVENT_DISCONNECT, object : Emitter.Listener {
        override fun call(vararg p0: Any?) {
          println("client disconnect")
        }
      });
  socket.connect();
  lifetime += {
    socket.disconnect()
  }
  return reactiveModel
}

val type = "@@@--^type"

fun toJson(diff: Diff<*>): JSONObject =
    diff.acceptVisitor(object : DiffVisitor<JSONObject> {
      override fun visitPrimitiveDiff(primitiveDiff: PrimitiveDiff) = JSONObject(hashMapOf(
          type to "primitive",
          "newValue" to primitiveDiff.newValue
      ))

      override fun visitListDiff(listDiff: ListDiff) = JSONObject(hashMapOf(
          type to "list",
          "index" to listDiff.index,
          "list" to JSONArray(listDiff.nueu.map { toJson(it) })
      ))

      override fun visitMapDiff(mapDiff: MapDiff) = pairsListToJSONObject(mapDiff.diff.map { entry ->
        entry.getKey() to toJson(entry.getValue())
      }.plus(type to "map"))

      override fun visitValueDiff(valueDiff: ValueDiff<*>) = JSONObject(hashMapOf(
          type to "value",
          "newValue" to toJson(valueDiff.newValue)
      ))
    })

fun toJson(model: Model): JSONObject =
    model.acceptVisitor(object : ModelVisitor<JSONObject> {
      override fun visitListModel(listModel: ListModel) = JSONObject(hashMapOf(
          type to "list",
          "list" to listModel.list.map { toJson(it) }
      ))

      override fun visitPrimitiveModel(primitive: PrimitiveModel<*>) = JSONObject(hashMapOf(
          type to "primitive",
          "value" to primitive.value
      ))

      override fun visitMapModel(mapModel: MapModel) = pairsListToJSONObject(mapModel.map { entry ->
        entry.getKey() to toJson(entry.getValue() as Model)
      }.plus(type to "map"))

      override fun visitAbsentModel(absent: AbsentModel) = JSONObject(hashMapOf(type to "absent"))
    })

fun toModel(json: JSONObject): Model =
    when (json.getString(type)) {
      "list" -> ListModel(toList(json.getJSONArray("list")).map { toModel(it as JSONObject) })
      "primitive" -> PrimitiveModel<Any>(json.get("value"))
      "map" -> MapModel(toMap(json).map { entry ->
        entry.getKey() to toModel(entry.getValue())
      }.toMap())
      "absent" -> AbsentModel()
      else -> throw AssertionError("unknown model type\n$json")
    }

fun toDiff(json: JSONObject): Diff<Model> =
    when (json.getString(type)!!) {
      "primitive" -> PrimitiveDiff(json.get("newValue"))
      "list" -> ListDiff(toList(json.getJSONArray("list")).map { toModel(it as JSONObject) }, json.getInt("index"))
      "map" -> MapDiff(toMap(json).map { entry -> entry.getKey() to toDiff(entry.getValue()) }.toMap())
      "value" -> ValueDiff<Model>(toModel(json.getJSONObject("newValue")))
      else -> throw AssertionError("unknown diff type\n$json")
    }

fun toMap(json: JSONObject): Map<String, JSONObject> {
  val result = HashMap<String, JSONObject>()
  for (k in json.keys()) {
    if (k != type) {
      result[k as String] = json.get(k) as JSONObject
    }
  }
  return result
}

fun toList(array: JSONArray): List<Any> {
  val result = ArrayList<Any>()
  for (i in (0..array.length() - 1)) {
    result.add(array.get(i))
  }
  return result
}

fun pairsListToJSONObject(entries: List<Pair<String, Any>>): JSONObject {
  val result = JSONObject()
  for ((k, v) in entries) {
    result.put(k, v)
  }
  return result
}

fun main(args: Array<String>) {
  val port = 12345
  val server = serverModel(Lifetime.Eternal, port, { model ->
    model.transaction { m ->
      Path("a").putIn(m, PrimitiveModel("abcd"))
    }
  })

  val clientModel = clientModel("http://localhost:" + port, Lifetime.Eternal)
}
