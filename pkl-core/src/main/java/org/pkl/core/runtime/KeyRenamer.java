package org.pkl.core.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.SortedSet;
import org.pkl.core.util.Nullable;

public class KeyRenamer {
  private final Deque<SortedSet<Long>> elementDeletions = new ArrayDeque<>();
  private final Deque<Set<Object>> otherDeletions = new ArrayDeque<>();
  
  public void push(@Nullable SortedSet<Long> elementDeletions, @Nullable Set<Object> otherDeletions) {
    if (elementDeletions != null) {
      this.elementDeletions.push(elementDeletions);
    }
    if (otherDeletions != null) {
      this.otherDeletions.push(otherDeletions);
    }
  }
  
  public void pop(@Nullable SortedSet<Long> elementDeletions, @Nullable Set<Object> otherDeletions) {
    if (elementDeletions != null) {
      this.elementDeletions.pop();
    }
    if (otherDeletions != null) {
      this.otherDeletions.pop();
    }
  }
  
  public @Nullable Object toReferenceKey(Object key) {
    if (key instanceof Long index) {
      for (var deletions : this.elementDeletions) {
        if (deletions.contains(index)) {
          return null;
        }
        index -= deletions.subSet(0L, index).size();
      }
      if (((Long) key).longValue() != index) {
        return index;
      }
    }
    for (var deletions : this.otherDeletions) {
      if (deletions.contains(key)) {
        return null;
      }
    }
    return key;
  }
  
  public Object toDeclarationKey(Object key) {
    if (key instanceof Long index) {
      for (var deletions : this.elementDeletions) {
        index += deletions.subSet(0L, index + 1L).size();
      }
      return index;
    }
    return key;
  }
}
