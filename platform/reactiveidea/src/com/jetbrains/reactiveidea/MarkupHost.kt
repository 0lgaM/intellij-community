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

import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.event.MarkupModelListenerEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.ColorUtil
import com.intellij.util.Alarm
import com.jetbrains.reactivemodel.*
import com.jetbrains.reactivemodel.models.AbsentModel
import com.jetbrains.reactivemodel.models.MapModel
import com.jetbrains.reactivemodel.models.PrimitiveModel
import com.jetbrains.reactivemodel.util.Lifetime
import java.awt.Color
import java.util.HashMap

public class MarkupHost(val markupModel: MarkupModelEx,
                        val reactiveModel: ReactiveModel,
                        val path: Path,
                        val lifetime: Lifetime,
                        init: Initializer) : Host {
  var markupIdFactory = 0

  val markupIdKey = Key<String>("com.jetbrains.reactiveidea.markupId." + reactiveModel.name)
  volatile var disposed = false;

  init {
    val markupListenerDisposable = { }
    lifetime += {
      disposed = true
      Disposer.dispose(markupListenerDisposable)
    }

    val markupBuffer = HashMap<String, Model>()
    val flushAlarm = Alarm(markupListenerDisposable)

    markupModel.addMarkupModelListener(markupListenerDisposable, object : MarkupModelListenerEx {
      override fun flush(bulk: Boolean) {
        if (bulk) {
          doFlush()
        } else {
          flushAlarm.cancelAllRequests()
          flushAlarm.addRequest({ doFlush() }, 200)
        }
      }

      private fun doFlush() {
        reactiveModel.transaction { m ->
          if (!markupBuffer.isEmpty() && !disposed) {
            var result = m;
            for ((k, v) in markupBuffer) {
              result = (path / k).putIn(result, v)
            }
            markupBuffer.clear()
            result
          } else m
        }
      }

      override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean) {
        if (renderersChanged) {
          val markupId = highlighter.getUserData(markupIdKey)
          if (markupId != null) {
            markupBuffer[markupId] = marshalHighlighter(highlighter)
          }
        }
      }

      override fun beforeRemoved(highlighter: RangeHighlighterEx) {
        val markupId = highlighter.getUserData(markupIdKey)
        if (markupId != null) {
          markupBuffer[markupId] = AbsentModel()
        }
      }

      override fun afterAdded(highlighter: RangeHighlighterEx) {
        val markupId = markupIdFactory++.toString()
        highlighter.putUserData(markupIdKey, markupId)
        markupBuffer[markupId] = marshalHighlighter(highlighter)
      }
    })

    init += {
      it.putIn(path,
          MapModel(markupModel.getAllHighlighters()
              .map { highlighter ->
                (markupIdFactory++).toString() to marshalHighlighter(highlighter)
              }
              .toMap()))
    }
  }
}

private fun unmarshalTextAttributes(model: Model?): TextAttributes? =
    if (model is MapModel) TextAttributes(
        toColor(model["foreground"]),
        toColor(model["background"]),
        toColor(model["effectColor"]),
        toEffectType(model["effectType"]),
        (model["fontType"] as PrimitiveModel<Int>).value)
    else null


private fun marshalTextAttributes(textAttributes: TextAttributes?): Model =
    if (textAttributes == null) AbsentModel()
    else MapModel(hashMapOf(
        "foreground" to toColorModel(textAttributes.getForegroundColor()),
        "background" to toColorModel(textAttributes.getBackgroundColor()),
        "effectColor" to toColorModel(textAttributes.getEffectColor()),
        "effectType" to toEffectTypeModel(textAttributes.getEffectType()),
        "fontType" to PrimitiveModel(textAttributes.getFontType())
    ))


private fun toEffectType(model: Model?): EffectType? =
    if (model is AbsentModel || model == null) null
    else EffectType.valueOf((model as PrimitiveModel<String>).value)

private fun toColor(model: Model?): Color? =
    if (model is AbsentModel) null
    else ColorUtil.fromHex((model as PrimitiveModel<String>).value)

private fun toEffectTypeModel(effectType: EffectType?): Model =
    if (effectType == null) AbsentModel()
    else PrimitiveModel(effectType.toString())

private fun toColorModel(color: Color?): Model =
    if (color == null) AbsentModel()
    else PrimitiveModel(ColorUtil.toHex(color))


private fun marshalHighlighter(highlighter: RangeHighlighter): MapModel =
    MapModel(hashMapOf(
        "startOffset" to PrimitiveModel(highlighter.getStartOffset()),
        "endOffset" to PrimitiveModel(highlighter.getEndOffset()),
        "attrs" to marshalTextAttributes(highlighter.getTextAttributes())
    ))
