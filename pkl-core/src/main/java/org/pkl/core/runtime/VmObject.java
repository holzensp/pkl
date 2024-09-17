/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.*;
import java.util.function.Function;
import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.pkl.core.ast.member.ObjectMember;
import org.pkl.core.util.CollectionUtils;
import org.pkl.core.util.EconomicMaps;
import org.pkl.core.util.Nullable;

/** Corresponds to `pkl.base#Object`. */
public abstract class VmObject extends VmObjectLike {
  private static final Object deletedElementIndicesKey = new Object();
  private static final Object deletedEntriesAndPropertiesKey = new Object();

  @CompilationFinal protected @Nullable VmObject parent;
  protected final UnmodifiableEconomicMap<Object, ObjectMember> members;
  protected final EconomicMap<Object, Object> cachedValues;

  protected int cachedHash;
  private boolean forced;

  public VmObject(
      MaterializedFrame enclosingFrame,
      @Nullable VmObject parent,
      UnmodifiableEconomicMap<Object, ObjectMember> members,
      EconomicMap<Object, Object> cachedValues) {
    super(enclosingFrame);
    this.parent = parent;
    this.members = members;
    this.cachedValues = extractDeletions(members, cachedValues);
  }

  public VmObject(
      MaterializedFrame enclosingFrame,
      @Nullable VmObject parent,
      UnmodifiableEconomicMap<Object, ObjectMember> members) {
    this(enclosingFrame, parent, members, EconomicMaps.create());
  }

  private static EconomicMap<Object, Object> extractDeletions(
      UnmodifiableEconomicMap<Object, ObjectMember> members,
      EconomicMap<Object, Object> cachedValues) {

    var elementDeletions = new TreeSet<Long>();
    var otherDeletions = new HashSet<>();
    for (var memberKey : members.getKeys()) {
      if (!members.get(memberKey).isDelete()) {
        continue;
      }
      if (memberKey instanceof Long key) {
        // TODO: Check whether member is an element
        elementDeletions.add(key);
      } else {
        otherDeletions.add(memberKey);
      }
    }
    if (!elementDeletions.isEmpty()) {
      EconomicMaps.put(cachedValues, deletedElementIndicesKey, elementDeletions);
    }
    if (!otherDeletions.isEmpty()) {
      EconomicMaps.put(cachedValues, deletedEntriesAndPropertiesKey, otherDeletions);
    }
    return cachedValues;
  }

  private @Nullable SortedSet<Long> getDeletedElementIndices() {
    if (!(cachedValues.get(deletedElementIndicesKey) instanceof SortedSet<?> set)) {
      return null;
    }
    //noinspection unchecked
    return (SortedSet<Long>) set;
  }

  private @Nullable Set<Object> getDeletedEntriesAndProperties() {
    if (!(cachedValues.get(deletedEntriesAndPropertiesKey) instanceof Set<?> set)) {
      return null;
    }
    //noinspection unchecked
    return (Set<Object>) set;
  }

  public Object toDeclarationKey(Object referenceKey) {
    if (!(referenceKey instanceof Long index) || index <= 0L) {
      return referenceKey;
    }
    var deletedElementIndices = getDeletedElementIndices();
    if (deletedElementIndices == null) {
      return referenceKey;
    }
    return index + deletedElementIndices.subSet(0L, index + 1L).size();
  }

  @Override
  public @Nullable Object toReferenceKey(Object declarationKey) {
    var member = members.get(declarationKey);
    if (member == null) {
      return declarationKey;
    }
    if (member.isDelete()) {
      return null;
    }
    // TODO: Beyond `index` not being `Long`; check whether the member is an element.
    if (!(declarationKey instanceof Long index)) {
      return declarationKey;
    }

    var deletedElementIndices = getDeletedElementIndices();
    if (deletedElementIndices == null) {
      return declarationKey;
    }

    return toReferenceElementKey(index, deletedElementIndices);
  }

  private @Nullable Long toReferenceElementKey(long declarationKey, SortedSet<Long> deletions) {
    if (deletions.contains(declarationKey)) {
      return null;
    }
    return declarationKey - deletions.subSet(0L, declarationKey).size();
  }

  public final void lateInitParent(VmObject parent) {
    assert this.parent == null;
    this.parent = parent;
  }

  @Override
  public @Nullable VmObject getParent() {
    return parent;
  }

  @Override
  public final boolean hasMember(Object key) {
    return EconomicMaps.containsKey(members, key);
  }

  @Override
  public final @Nullable ObjectMember getMember(Object key) {
    return EconomicMaps.get(members, key);
  }

  @Override
  public final UnmodifiableEconomicMap<Object, ObjectMember> getMembers() {
    return members;
  }

  @Override
  public @Nullable Object getCachedValue(Object key) {
    return EconomicMaps.get(cachedValues, key);
  }

  @Override
  public void setCachedValue(Object key, Object value, ObjectMember objectMember) {
    EconomicMaps.put(cachedValues, key, value);
  }

  @Override
  public final boolean hasCachedValue(Object key) {
    return EconomicMaps.containsKey(cachedValues, key);
  }

  @Override
  @TruffleBoundary
  public final boolean iterateMemberValues(MemberValueConsumer consumer) {
    var visited = new HashSet<>();
    return iterateMembers(
        (declarationKey, referenceKey, member) -> {
          if (referenceKey == null) return true;
          var alreadyVisited = !visited.add(referenceKey);
          // important to record hidden member as visited before skipping it
          // because any overriding member won't carry a `hidden` identifier
          if (alreadyVisited || member.isLocalOrExternalOrHidden()) return true;
          return consumer.accept(referenceKey, member, getCachedValue(referenceKey));
        });
  }

  @Override
  @TruffleBoundary
  public final boolean forceAndIterateMemberValues(ForcedMemberValueConsumer consumer) {
    force(false, false);
    return iterateAlreadyForcedMemberValues(consumer);
  }

  @Override
  @TruffleBoundary
  public final boolean iterateAlreadyForcedMemberValues(ForcedMemberValueConsumer consumer) {
    var visited = new HashSet<>();
    return iterateMembers(
        (declarationKey, referenceKey, member) -> {
          if (referenceKey == null) return true;
          var alreadyVisited = !visited.add(referenceKey);
          // important to record hidden member as visited before skipping it
          // because any overriding member won't carry a `hidden` identifier
          if (alreadyVisited || member.isLocalOrExternalOrHidden()) return true;
          Object cachedValue = getCachedValue(referenceKey);
          assert cachedValue != null; // forced
          return consumer.accept(referenceKey, member, cachedValue);
        });
  }

  @Override
  @TruffleBoundary
  public final boolean iterateMembers(MemberConsumer consumer) {
    var deletions = getDeletedEntriesAndProperties();
    return iterateMembers(
        consumer, deletions == null ? Collections.EMPTY_SET : deletions, (key) -> key);
  }

  @TruffleBoundary
  private boolean iterateMembers(
      MemberConsumer consumer, Set<Object> deleted, Function<Long, Long> converter) {
    var parent = getParent();
    var deletedElementIndices = getDeletedElementIndices();
    Function<Long, Long> newConverter =
        deletedElementIndices == null
            ? converter
            : (index) -> {
              var newIndex = toReferenceElementKey(index, deletedElementIndices);
              return newIndex == null ? null : converter.apply(newIndex);
            };
    var newDeleted = deleted;
    var deletedEntriesAndProperties = getDeletedEntriesAndProperties();
    if (deletedEntriesAndProperties != null) {
      newDeleted = new HashSet<>(deleted);
      newDeleted.addAll(deletedEntriesAndProperties);
    }
    if (parent != null && !parent.iterateMembers(consumer, newDeleted, newConverter)) {
      return false;
    }
    var entries = members.getEntries();
    while (entries.advance()) {
      var member = entries.getValue();
      if (member.isLocal()) continue;
      var declarationKey = entries.getKey();
      var referenceKey = declarationKey;
      if (deleted.contains(declarationKey)) {
        referenceKey = null;
      } else if (referenceKey instanceof Long index) {
        referenceKey = converter.apply(index);
      }
      if (!consumer.accept(declarationKey, referenceKey, member)) {
        return false;
      }
    }
    return true;
  }

  /** Evaluates this object's members. Skips local, hidden, and external members. */
  @Override
  @TruffleBoundary
  public final void force(boolean allowUndefinedValues, boolean recurse) {
    if (forced) return;

    if (recurse) forced = true;

    try {
      for (VmObjectLike owner = this; owner != null; owner = owner.getParent()) {
        var cursor = EconomicMaps.getEntries(owner.getMembers());
        var clazz = owner.getVmClass();
        while (cursor.advance()) {
          var memberKey = cursor.getKey();
          var member = cursor.getValue();
          // isAbstract() can occur when VmAbstractObject.toString() is called
          // on a prototype of an abstract class (e.g., in the Java debugger)
          if (member.isLocalOrExternalOrAbstractOrDelete() || clazz.isHiddenProperty(memberKey)) {
            continue;
          }

          var memberValue = getCachedValue(memberKey);
          if (memberValue == VmUtils.DELETE_MARKER) {
            continue;
          }
          if (member.isDelete()) {
            setCachedValue(memberKey, VmUtils.DELETE_MARKER);
            continue;
          }

          if (memberValue == null) {
            try {
              memberValue = VmUtils.doReadMember(this, owner, memberKey, member);
            } catch (VmUndefinedValueException e) {
              if (!allowUndefinedValues) throw e;
              continue;
            }
          }

          if (recurse) {
            VmValue.force(memberValue, allowUndefinedValues);
          }
        }
      }
    } catch (Throwable t) {
      forced = false;
      throw t;
    }
  }

  @Override
  public final void force(boolean allowUndefinedValues) {
    force(allowUndefinedValues, true);
  }

  public final String toString() {
    force(true, true);
    return VmValueRenderer.singleLine(Integer.MAX_VALUE).render(this);
  }

  /**
   * Exports this object's members. Skips local members, hidden members, class definitions, and type
   * aliases. Members that haven't been forced have a `null` value.
   */
  @TruffleBoundary
  protected final Map<String, Object> exportMembers() {
    var result = CollectionUtils.<String, Object>newLinkedHashMap(EconomicMaps.size(cachedValues));

    iterateMemberValues(
        (key, member, value) -> {
          if (member.isClass() || member.isTypeAlias()) return true;

          result.put(key.toString(), VmValue.exportNullable(value));
          return true;
        });

    return result;
  }
}
