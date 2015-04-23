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

import com.intellij.openapi.editor.event.DocumentAdapter
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.Key
import com.jetbrains.reactivemodel.*
import com.jetbrains.reactivemodel.models.ListModel
import com.jetbrains.reactivemodel.models.MapModel
import com.jetbrains.reactivemodel.models.PrimitiveModel
import com.jetbrains.reactivemodel.signals.reaction
import com.jetbrains.reactivemodel.util.Guard
import com.jetbrains.reactivemodel.util.Lifetime

public class DocumentHost(val lifetime: Lifetime, val reactiveModel: ReactiveModel, val path: Path, val doc: DocumentImpl) {
  private val TIMESTAMP: Key<Int> = Key("com.jetbrains.reactiveidea.timestamp")
  private val recursionGuard = Guard()

  init {
    val listener = object : DocumentAdapter() {
      override fun documentChanged(e: DocumentEvent) {
        val transaction: (MapModel) -> MapModel = { m ->
          if (recursionGuard.locked) {
            m
          } else if (e.isWholeTextReplaced()) {
            (path / "text").putIn(m, PrimitiveModel(doc.getText()))
          } else {
            val result = (path / "events" / Last).putIn(m, documentEvent(e))
            val events = (path / "events").getIn(result) as ListModel
            doc.putUserData(TIMESTAMP, events.size())
            result
          }
        }
        reactiveModel.transaction(transaction)
      }
    }

    val textSignal = reaction(true, "document text", reactiveModel.subscribe(lifetime, path / "text")) { model ->
      if (model == null) null
      else (model as PrimitiveModel<String>).value
    }

    reaction(true, "init document text", textSignal) { text ->
      if (text != null) {
        doc.setText(text)
      }
    }.lifetime.terminate()

    doc.addDocumentListener(listener)
    lifetime += {
      doc.removeDocumentListener(listener)
    }

    reactiveModel.transaction { m ->
      var result = (path / "text").putIn(m, PrimitiveModel(doc.getText()))
      result = (path / "events").putIn(result, ListModel())

      result
    }

    val eventsList = reaction(true, "cast events to ListModel", reactiveModel.subscribe(lifetime, (path / "events"))) {
      if (it != null) it as ListModel
      else null
    }

    val listenToDocumentEvents = reaction(true, "listen to model events", eventsList) { evts ->
      if (evts != null) {
        var timestamp = doc.getUserData(TIMESTAMP)
        if (timestamp == null) {
          timestamp = 0
        }
        doc.putUserData(TIMESTAMP, evts.size())
        recursionGuard.lock {
          for (i in (timestamp..evts.size() - 1)) {
            val eventModel = evts[i]
            play(eventModel, doc)
          }
        }
      }
    }

    lifetime += {
      listenToDocumentEvents.lifetime.terminate()
    }

  }
}

private fun play(event: Model, doc: DocumentImpl) {
  if (event !is MapModel) {
    throw AssertionError()
  }

  val offset = (event["offset"] as PrimitiveModel<Int>).value
  val len = (event["len"] as PrimitiveModel<Int>).value
  val text = (event["text"] as PrimitiveModel<String>).value

  doc.replaceString(offset, offset + len, text)
}

fun documentEvent(e: DocumentEvent): Model = MapModel(hashMapOf(
    "offset" to PrimitiveModel(e.getOffset()),
    "len" to PrimitiveModel(e.getOldFragment().length()),
    "text" to PrimitiveModel(e.getNewFragment().toString())
))
