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
package com.jetbrains.reactivemodel

import com.github.krukow.clj_lang.IPersistentSet
import com.github.krukow.clj_lang.PersistentHashMap
import com.github.krukow.clj_lang.PersistentHashSet
import com.jetbrains.reactivemodel.models.ListModel
import com.jetbrains.reactivemodel.models.MapModel
import com.jetbrains.reactivemodel.models.PrimitiveModel
import com.jetbrains.reactivemodel.util.get
import java.util.*

private val tagMap = HashMap<String, Tag<*>>() // only read access after init

class Tag<T : Model>(val name: String) {
  init {
    tagMap[name] = this
  }

  fun<M : AssocModel<*, M>> getIn(rootModel: M): List<T> {
    val index = rootModel.meta.index()
    val pathes: PersistentHashSet<Path> = index[name] as? PersistentHashSet<Path> ?: PersistentHashSet.emptySet()
    return pathes.map { it.getIn(rootModel) as T }.toArrayList()
  }
}

public val tagsField: String = "@@@--^tags"

public val editorsTag: Tag<MapModel> = Tag("editor")

public fun getTag(name: String): Tag<*>? = tagMap[name]

public fun tagsModel(vararg tags: String): Model = ListModel(tags.map { PrimitiveModel(it) }.toArrayList())