package com.jetbrains.reactivemodel.models

import com.github.krukow.clj_ds.PersistentMap
import com.github.krukow.clj_ds.Persistents
import com.github.krukow.clj_lang.IPersistentMap
import com.github.krukow.clj_lang.PersistentHashMap
import com.jetbrains.reactivemodel.*
import com.jetbrains.reactivemodel.util.emptyMeta
import com.jetbrains.reactivemodel.util.withEmptyMeta
import com.jetbrains.reactivemodel.util.withMeta
import java.util.HashMap

public data class MapModel(val hmap: PersistentHashMap<Any, Model> = PersistentHashMap.emptyMap(),
                           override val meta: IPersistentMap<String, *> = emptyMeta())
: AssocModel<Any, MapModel>, Map<Any, Model?> by hmap {

  override fun <T> acceptVisitor(visitor: ModelVisitor<T>): T = visitor.visitMapModel(this)

  public constructor(m: Map<out Any, Model>, meta: IPersistentMap<String, *>)
  : this(PersistentHashMap.create(m), meta)

  public constructor(m: Map<out Any, Model>)
  : this(PersistentHashMap.create(m), emptyMeta())

  public fun assocMeta(key: String, value: Any?): MapModel {
    return MapModel(hmap, (meta as IPersistentMap<String, Any>).assoc(key, value))
  }

  public override fun assoc(key: Any, value: Model?): MapModel =
      if (value is AbsentModel) remove(key)
      else MapModel(hmap.plus(key, value), meta)

  public override fun find(key: Any): Model? = hmap.get(key)
  public fun remove(k: Any): MapModel = MapModel(hmap.minus(k), meta)

  override fun diffImpl(other: Model): Diff<Model>? {
    if (other !is MapModel) {
      throw AssertionError()
    }

    val diff = HashMap<Any, Diff<Model>>()
    hmap.keySet().union(other.hmap.keySet()).forEach {
      val value = this.find(it)
      val otherValue = other.find(it)
      if (value == null && otherValue != null) {
        diff.put(it, ValueDiff(otherValue))
      } else if (value != null && otherValue != null) {
        val valuesDiff = value.diff(otherValue)
        if (valuesDiff != null) {
          diff.put(it, valuesDiff)
        }
      } else {
        diff.put(it, ValueDiff(AbsentModel()))
      }
    }
    if (diff.isEmpty()) return null
    else return MapDiff(diff)
  }

  override fun patch(diff: Diff<Model>): MapModel {
    if (diff is ValueDiff<*>) {
      if (diff.newValue is AbsentModel) {
        throw AssertionError()
      }
      if (diff.newValue !is MapModel) {
        throw AssertionError()
      }
      return diff.newValue
    }
    if (diff !is MapDiff) {
      throw AssertionError()
    }
    var self = this
    for ((k, d) in diff.diff) {
      val value = this.find(k)
      if (value == null) {
        if (d is PrimitiveDiff) {
          // boolean
          self = self.assoc(k, PrimitiveModel(d.newValue))
        } else {
          if (d !is ValueDiff<*>) {
            throw AssertionError()
          }
          self = self.assoc(k, d.newValue)
        }
      } else if (d is ValueDiff<*> && d.newValue is AbsentModel) {
        self = self.remove(k)
      } else {
        self = self.assoc(k, value.patch(d))
      }
    }
    return self
  }
}

public data class MapDiff(val diff: Map<Any, Diff<Model>>) : Diff<MapModel> {
  override fun <T> acceptVisitor(visitor: DiffVisitor<T>): T = visitor.visitMapDiff(this)
}

public fun assocModelWithPath(p: Path, m: Model): MapModel =
    p.components.foldRight(m) { comp, m ->
      MapModel(hashMapOf(comp to m))
    } as MapModel
