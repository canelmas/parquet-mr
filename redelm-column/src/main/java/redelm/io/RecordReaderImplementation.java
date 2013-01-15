/**
 * Copyright 2012 Twitter, Inc.
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
package redelm.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import redelm.Log;
import redelm.column.ColumnReader;
import redelm.column.ColumnsStore;
import redelm.io.RecordReaderImplementation.Case;
import redelm.schema.MessageType;
import redelm.schema.PrimitiveType.Primitive;

/**
 * used to read reassembled records
 * @author Julien Le Dem
 *
 * @param <T> the type of the materialized record
 */
public class RecordReaderImplementation<T> extends RecordReader<T> {

  private static final Log LOG = Log.getLog(RecordReaderImplementation.class);
  private static final boolean DEBUG = Log.DEBUG;

  public static class Case {

    private int id;
    private final int startLevel;
    private final int depth;
    private final int nextLevel;
    private final boolean goingUp;
    private final boolean goingDown;
    private final int nextState;

    public Case(int startLevel, int depth, int nextLevel, int nextState) {
      this.startLevel = startLevel;
      this.depth = depth;
      this.nextLevel = nextLevel;
      this.nextState = nextState;
      goingUp = startLevel <= depth;
      goingDown = depth + 1 > nextLevel;
    }

    public void setID(int id) {
      this.id = id;
    }

    @Override
//    public int hashCode() {
//      int hashCode = 0;
//      if (goingUp) {
//        hashCode += 1 * (1 + startLevel) + 2 * (1 + depth);
//      }
//      if (goingDown) {
//        hashCode += 3 * (1 + depth) + 5 * (1 + nextLevel);
//      }
//      return hashCode;
//    }

    public int hashCode() {
      return 1 * startLevel + 2 * depth + 3 * nextLevel + 5 * nextState;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof Case) {
        return equals((Case)obj);
      }
      return false;
    };

//    public boolean equals(Case other) {
//      if (goingUp && !other.goingUp || !goingUp && other.goingUp) {
//        return false;
//      }
//      if (goingUp && other.goingUp && (startLevel != other.startLevel || depth != other.depth)) {
//        return false;
//      }
//      if (goingDown && !other.goingDown || !goingDown && other.goingDown) {
//        return false;
//      }
//      if (goingDown && other.goingDown && (depth != other.depth || nextLevel != other.nextLevel)) {
//        return false;
//      }
//      return true;
//    }

    public boolean equals(Case other) {
      return startLevel == other.startLevel && depth == other.depth && nextLevel == other.nextLevel && nextState == other.nextState;
    }

    public int getID() {
      return id;
    }

    public int getStartLevel() {
      return startLevel;
    }

    public int getDepth() {
      return depth;
    }
    public int getNextLevel() {
      return nextLevel;
    }

    public int getNextState() {
      return nextState;
    }

    public boolean isGoingUp() {
      return goingUp;
    }

    public boolean isGoingDown() {
      return goingDown;
    }

    @Override
    public String toString() {
      return "Case " + startLevel + " -> " + depth + " -> " + nextLevel + "; goto sate_"+getNextState();
    }

  }

  public static class State {

    public final int id;
    public final PrimitiveColumnIO primitiveColumnIO;
    public final int maxDefinitionLevel;
    public final int maxRepetitionLevel;
    public final Primitive primitive;
    public final ColumnReader column;
    public final String[] fieldPath; // indexed on currentLevel
    public final int[] indexFieldPath; // indexed on currentLevel
    public final String primitiveField;
    public final int primitiveFieldIndex;
    public final int[] nextLevel; //indexed on next r

    private int[] definitionLevelToDepth; // indexed on current d
    private State[] nextState; // indexed on next r
    private Case[][][] caseLookup;
    private List<Case> definedCases;
    private List<Case> undefinedCases;

    private State(int id, PrimitiveColumnIO primitiveColumnIO, ColumnReader column, int[] nextLevel) {
      this.id = id;
      this.primitiveColumnIO = primitiveColumnIO;
      this.maxDefinitionLevel = primitiveColumnIO.getDefinitionLevel();
      this.maxRepetitionLevel = primitiveColumnIO.getRepetitionLevel();
      this.column = column;
      this.nextLevel = nextLevel;
      this.primitive = primitiveColumnIO.getType().asPrimitiveType().getPrimitive();
      this.fieldPath = primitiveColumnIO.getFieldPath();
      this.primitiveField = fieldPath[fieldPath.length - 1];
      this.indexFieldPath = primitiveColumnIO.getIndexFieldPath();
      this.primitiveFieldIndex = indexFieldPath[indexFieldPath.length - 1];
    }

    public int getDepth(int definitionLevel) {
      return definitionLevelToDepth[definitionLevel];
    }

    public List<Case> getDefinedCases() {
      return definedCases;
    }

    public List<Case> getUndefinedCases() {
      return undefinedCases;
    }

    public Case getCase(int currentLevel, int d, int nextR) {
      return caseLookup[currentLevel][d][nextR];
    }
  }

  private final RecordConsumer recordConsumer;
  private final RecordMaterializer<T> recordMaterializer;

  private String endField;
  private int endIndex;
  private State[] states;

  /**
   *
   * @param root the root of the schema
   * @param leaves the leaves of the schema
   * @param validating
   * @param columns2
   */
  public RecordReaderImplementation(MessageColumnIO root, RecordMaterializer<T> recordMaterializer, boolean validating, ColumnsStore columnStore) {
    this.recordMaterializer = recordMaterializer;
    this.recordConsumer = validator(wrap(recordMaterializer), validating, root.getType());
    PrimitiveColumnIO[] leaves = root.getLeaves().toArray(new PrimitiveColumnIO[root.getLeaves().size()]);
    ColumnReader[] columns = new ColumnReader[leaves.length];
    int[][] nextReader = new int[leaves.length][];
    int[][] nextLevel = new int[leaves.length][];
    int[] firsts  = new int[256]; // "256 levels of nesting ought to be enough for anybody"
    // build the automaton
    for (int i = 0; i < leaves.length; i++) {
      PrimitiveColumnIO primitiveColumnIO = leaves[i];
      columns[i] = columnStore.getColumnReader(primitiveColumnIO.getColumnDescriptor());
      int repetitionLevel = primitiveColumnIO.getRepetitionLevel();
      nextReader[i] = new int[repetitionLevel+1];
      nextLevel[i] = new int[repetitionLevel+1];
      for (int r = 0; r <= repetitionLevel; ++r) {
        // remember which is the first for this level
        if (primitiveColumnIO.isFirst(r)) {
          firsts[r] = i;
        }
        int next;
        // figure out automaton transition
        if (r == 0) { // 0 always means jump to the next (the last one being a special case)
          next = i + 1;
        } else if (primitiveColumnIO.isLast(r)) { // when we are at the last of the current repetition level we jump back to the first
          next = firsts[r];
        } else { // otherwise we just go back to the next.
          next = i + 1;
        }
        // figure out which level down the tree we need to go back
        if (next == leaves.length) { // reached the end of the record => close all levels
          nextLevel[i][r] = 0;
        } else if (primitiveColumnIO.isLast(r)) { // reached the end of this level => close the repetition level
          ColumnIO parent = primitiveColumnIO.getParent(r);
          nextLevel[i][r] = parent.getFieldPath().length - 1;
        } else { // otherwise close until the next common parent
          nextLevel[i][r] = getCommonParentLevel(
              primitiveColumnIO.getFieldPath(),
              leaves[next].getFieldPath());
        }
        // sanity check: that would be a bug
        if (nextLevel[i][r] > leaves[i].getFieldPath().length-1) {
          throw new RuntimeException(Arrays.toString(leaves[i].getFieldPath())+" -("+r+")-> "+nextLevel[i][r]);
        }
        nextReader[i][r] = next;
      }
    }
    states = new State[leaves.length];
    for (int i = 0; i < leaves.length; i++) {
      states[i] = new State(i, leaves[i], columns[i], nextLevel[i]);

      int[] definitionLevelToDepth = new int[states[i].primitiveColumnIO.getDefinitionLevel() + 1];
      int depth = 0;
      // for each possible definition level, determine the depth at which to create groups
      for (int d = 0; d < definitionLevelToDepth.length; ++d) {
        while (depth < (states[i].fieldPath.length - 1)
          && d > states[i].primitiveColumnIO.getPath()[depth].getDefinitionLevel()) {
          ++ depth;
        }
        definitionLevelToDepth[d] = depth - 1;
      }
      states[i].definitionLevelToDepth = definitionLevelToDepth;

    }
    for (int i = 0; i < leaves.length; i++) {
      State state = states[i];
      int[] nextStateIds = nextReader[i];
      state.nextState = new State[nextStateIds.length];
      for (int j = 0; j < nextStateIds.length; j++) {
        state.nextState[j] = nextStateIds[j] == states.length ? null : states[nextStateIds[j]];
      }
    }
    for (int i = 0; i < states.length; i++) {
      State state = states[i];
      final Map<Case, Case> definedCases = new HashMap<Case, Case>();
      final Map<Case, Case> undefinedCases = new HashMap<Case, Case>();
      Case[][][] caseLookup = new Case[state.fieldPath.length][][];
      for (int currentLevel = 0; currentLevel < state.fieldPath.length; ++ currentLevel) {
        caseLookup[currentLevel] = new Case[state.maxDefinitionLevel+1][];
        for (int d = 0; d <= state.maxDefinitionLevel; ++ d) {
          caseLookup[currentLevel][d] = new Case[state.maxRepetitionLevel+1];
          for (int nextR = 0; nextR <= state.maxRepetitionLevel; ++ nextR) {
            int caseStartLevel = currentLevel;
            int caseDepth = Math.max(state.getDepth(d), caseStartLevel - 1);
            int caseNextLevel = Math.min(state.nextLevel[nextR], caseDepth + 1);
            Case currentCase = new Case(caseStartLevel, caseDepth, caseNextLevel, getNextReader(state.id, nextR));
            Map<Case, Case> cases = d == state.maxDefinitionLevel ? definedCases : undefinedCases;
            if (!cases.containsKey(currentCase)) {
//              System.out.println("adding "+currentCase);
              currentCase.setID(cases.size());
              cases.put(currentCase, currentCase);
            } else {
//              System.out.println("not adding "+currentCase);
              currentCase = cases.get(currentCase);
            }
//            System.out.println(currentLevel+", "+d+", "+nextR);
            caseLookup[currentLevel][d][nextR] = currentCase;
          }
        }
      }
      state.caseLookup = caseLookup;
      state.definedCases = new ArrayList<Case>(definedCases.values());
      state.undefinedCases = new ArrayList<Case>(undefinedCases.values());
      Comparator<Case> caseComparator = new Comparator<Case>() {
        @Override
        public int compare(Case o1, Case o2) {
          return o1.id - o2.id;
        }
      };
      Collections.sort(state.definedCases, caseComparator);
      Collections.sort(state.undefinedCases, caseComparator);
    }
  }

  private RecordConsumer validator(RecordConsumer recordConsumer, boolean validating, MessageType schema) {
    return validating ? new ValidatingRecordConsumer(recordConsumer, schema) : recordConsumer;
  }

  private RecordConsumer wrap(RecordConsumer recordConsumer) {
    if (Log.DEBUG) {
      return new RecordConsumerLoggingWrapper(recordConsumer);
    }
    return recordConsumer;
  }

  /* (non-Javadoc)
   * @see redelm.io.RecordReader#read(T[], int)
   */
  @Override
  public void read(T[] records, int count) {
    if (count > records.length) {
      throw new IllegalArgumentException("count is greater than records size");
    }
    for (int i = 0; i < count; i++) {
      records[i] = read();
    }
  }

  /**
   * @see redelm.io.RecordReader#read()
   */
  @Override
  public T read() {
    int currentLevel = 0;
    State currentState = states[0];
    startMessage();
    do {
      ColumnReader columnReader = currentState.column;
      int d = columnReader.getCurrentDefinitionLevel();
      // creating needed nested groups until the current field (opening tags)
      int depth = currentState.definitionLevelToDepth[d];
      for (; currentLevel <= depth; ++currentLevel) {
        startGroup(currentState, currentLevel);
      }
      // currentLevel = depth + 1 at this point
      // set the current value
      if (d >= currentState.maxDefinitionLevel) {
        // not null
        addPrimitive(currentState, columnReader);
      }
      columnReader.consume();

      int nextR = currentState.maxRepetitionLevel == 0 ? 0 : columnReader.getCurrentRepetitionLevel();
      // level to go to close current groups
      int next = currentState.nextLevel[nextR];
      for (; currentLevel > next; currentLevel--) {
        endGroup(currentState, currentLevel - 1);
      }

      currentState = currentState.nextState[nextR];
    } while (currentState != null);
    endMessage();
    return recordMaterializer.getCurrentRecord();
  }

  private void endGroup(State currentState, int level) {
    String field = currentState.fieldPath[level];
    int fieldIndex = currentState.indexFieldPath[level];
    endGroup(field, fieldIndex);
  }

  private void addPrimitive(State currentState, ColumnReader columnReader) {
    if (DEBUG) log(currentState.primitiveField +"(" + (currentState.fieldPath.length - 1) + ") = "+currentState.primitive.toString(columnReader));
    addPrimitive(columnReader, currentState.primitive, currentState.primitiveField, currentState.primitiveFieldIndex);
  }

  private void startGroup(State currentState, int level) {
    String field = currentState.fieldPath[level];
    int fieldIndex = currentState.indexFieldPath[level];
    if (DEBUG) log(field + "(" + level + ") = new Group()");
    startGroup(field, fieldIndex);
  }

  private void startMessage() {
    // reset state
    endField = null;
    recordConsumer.startMessage();
  }

  private void endMessage() {
    if (endField != null) {
      // close the previous field
      recordConsumer.endField(endField, endIndex);
      endField = null;
    }
    recordConsumer.endMessage();
  }

  private void addPrimitive(ColumnReader columnReader, Primitive primitive, String field, int index) {
    startField(field, index);
    primitive.addValueToRecordConsumer(recordConsumer, columnReader);
    endField(field, index);
  }

  private void endField(String field, int index) {
    if (endField != null) {
      recordConsumer.endField(endField, endIndex);
    }
    endField = field;
    endIndex = index;
  }

  private void startField(String field, int index) {
    if (endField != null && index == endIndex) {
      // skip the close/open tag
      endField = null;
    } else {
      if (endField != null) {
        // close the previous field
        recordConsumer.endField(endField, endIndex);
        endField = null;
      }
      recordConsumer.startField(field, index);
    }
  }

  private void endGroup(String field, int index) {
    if (endField != null) {
      // close the previous field
      recordConsumer.endField(endField, endIndex);
      endField = null;
    }
    recordConsumer.endGroup();
    endField(field, index);
  }

  private void startGroup(String field, int fieldIndex) {
    startField(field, fieldIndex);
    recordConsumer.startGroup();
  }

  private static void log(String string) {
    LOG.debug(string);
  }

  int getNextReader(int current, int nextRepetitionLevel) {
    State nextState = states[current].nextState[nextRepetitionLevel];
    return nextState == null ? states.length : nextState.id;
  }

  int getNextLevel(int current, int nextRepetitionLevel) {
    return states[current].nextLevel[nextRepetitionLevel];
  }

  private int getCommonParentLevel(String[] previous, String[] next) {
    int i = 0;
    while (i < previous.length && i < next.length && previous[i].equals(next[i])) {
      ++i;
    }
    return i;
  }

  protected int getStateCount() {
    return states.length;
  }

  protected State getState(int i) {
    return states[i];
  }

  protected RecordMaterializer<T> getMaterializer() {
    return recordMaterializer;
  }

  protected RecordConsumer getRecordConsumer() {
    return recordConsumer;
  }

}
