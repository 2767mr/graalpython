/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.objects.common;

import static com.oracle.truffle.api.CompilerDirectives.SLOWPATH_PROBABILITY;

import java.util.Arrays;
import java.util.Iterator;

import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary.ForEachNode;
import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary.HashingStorageIterable;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.function.PArguments.ThreadState;
import com.oracle.graal.python.lib.PyObjectRichCompareBool;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.LoopConditionProfile;

/**
 * Generic dictionary/set backing storage implementation.
 *
 * The basic algorithm is hash table with open addressing for collision resolution. For that we need
 * to have a fixed order of indexes to probe if collision happens. Simple implementations use linear
 * serach (+/-1) from the bucket where the collision happened. We use the same more advanced scheme
 * as PyPy/CPython. It relies on the fact that recurrence
 *
 * <code>
 * j = ((5*j) + 1) mod N^2
 * </code>
 *
 * generates all numbers from 0 to (N^2)-1, but not in linear order, i.e., for j=1, N=3, we get: 1 6
 * 7 4 5 2 3 0. In our case we set j to the index of the bucket that has the collision. To make this
 * scheme also dependent on the higher bits of the hash, we use this formula (also like CPython):
 *
 * <code>
 * pertrub >>= PERTURB_SHIFT
 * j = ((5*j) + pertrub + 1) mod N^2
 * </code>
 *
 * Which is not guaranteed to generate numbers from 0 to (N^2)-1 in general, but when the perturb
 * value is shifted often enough, it becomes 0, and then we're effectively using the original
 * version of the recurrence that does guarantee that.
 *
 * Additionally, we use the same trick as PyPy/CPython: there is one sparse array, which is the
 * actual hash table, but it does not contain the entries, it contains indices into compact arrays
 * with hashes and keys and values. This not only should be memory efficient and cache friendly, but
 * it preserves the insertion order, which is a requirement.
 *
 * On top of the PyPy/CPython design: we use the highest bit in the indices stored in the sparse
 * array to indicate whether given bucket participates in some collisions chain. When searching
 * through a collision chain, we can stop at items that do not have this bit set. The practical
 * implications of this is that for close to full maps, lookups of items that are not present in the
 * map are faster, because we can terminate the collisions chain chasing earlier.
 * 
 * Notable use case that does not (yet) work well with this approach: repeated insertion and removal
 * of the same key. This keeps on adding dummy entries when removing the entry and creating long
 * collisions chains that the insertion needs to follow to find a free slot. This all repeats until
 * the insertion concludes that the map is too full and rehashes it, in this case actually not
 * growing it, but just removing the dummy entries. The same seems to happen on CPython also, but
 * can be improved.
 *
 * Areas for future improvements:
 * <ul>
 * <li>Use byte[] array for the sparse indices array and determine the size of an index according to
 * the compact array size, i.e., 1 byte is enough for 256 items. This needs to also properly handle
 * the bit flag used to mark collisions.</li>
 * <li>Use another bit from the index in the sparse indices array to remember index of removed
 * items, i.e., dummy items would carry the old index and collision mask. Such dummy items can be
 * reused when inserting new items. This will help with the insert/remove of the same key
 * scenario.</li>
 * <li>Inline {@link ObjectHashMap} into {@code EconomicMapStorage} to save an indirection.</li>
 * <li>New strategy for long keys where the hashes array is used to store the keys, and the
 * keysAndValues array will store just values. Can be implemented by extending this class and
 * overriding few methods.</li>
 * <li>Flag that indicates that the hash-map is used as a storage for a set, so all values are
 * {@code None} and there is no need to allocate space for values in the keysAndValues array.</li>
 * </ul>
 */
public final class ObjectHashMap {
    /**
     * Every hash map will preallocate at least this many buckets (and corresponding # of slots for
     * the real items).
     */
    private static final int INITIAL_INDICES_SIZE = 8;

    /**
     * We limit the max size of preallocated hash maps. See the comment in the ctor.
     */
    private static final int MAX_PREALLOCATED_INDICES_SIZE = 1 << 20;

    /**
     * Indices that participate in a collision chain are marked with the sign bit.
     */
    private static final int COLLISION_MASK = 1 << 31;

    /**
     * We need some placeholders. Those masked with {@link #COLLISION_MASK} give numbers higher than
     * our max number of items, which is MAX_INT/2, because we cram they keys and values together
     * into one array.
     */
    private static final int DUMMY_INDEX = -2;
    private static final int EMPTY_INDEX = -1;

    private static void markCollision(int[] indices, int compactIndex) {
        assert indices[compactIndex] != EMPTY_INDEX;
        indices[compactIndex] = indices[compactIndex] | COLLISION_MASK;
    }

    private static boolean isCollision(int index) {
        return (index & COLLISION_MASK) != 0;
    }

    private static int unwrapIndex(int value) {
        return value & ~COLLISION_MASK;
    }

    /**
     * This is the factor how much the map grows when new entries are added. Note that we grow
     * according to the used slots for real items, not according to the buckets count, because when
     * "growing" we also remove dummy entries, so "growing" could mean that we also shrink.
     */
    private static final int GROWTH_RATE = 4;

    private static final long PERTURB_SHIFT = 5;
    // It takes at most this many >>> shifts to turn any long into 0
    private static final int PERTURB_SHIFTS_COUT = 13;

    // Sparse array with indices pointing to hashes and keysAndValues
    private int[] indices;

    // Compact arrays with the actual dict items:
    private long[] hashes;
    private Object[] keysAndValues;

    // How many real items are in the dict
    private int size;
    // How many of the slots in the hashes/keysAndValues arrays are occupied either with real item
    // or dummy item. Note: we compact those arrays on deletion if there are too many dummy entries,
    // but we do not compact the indices array to retain the collision sequences. On rehashing,
    // triggered from insertion, we do remove dummy entries and rearrange the collision sequences
    // (as a side effect of reinserting all the items again).
    private int usedHashes;
    // How many of the buckets in indices array are used. This may be larger by usedHashes if
    // we compacted on deletion.
    private int usedIndices;

    /**
     * If the map contains elements with potential side effects in __eq__, then this map may have to
     * restart collision resolution on a side effect. This flag is used for this. TODO: the restart
     * of collision resolution is not implemented yet.
     */
    private boolean hasSideEffectingKeys;

    public ObjectHashMap(int capacity, boolean hasSideEffects) {
        if (capacity <= INITIAL_INDICES_SIZE) {
            allocateData(INITIAL_INDICES_SIZE);
        } else {
            // We need the hash table of this size, in order to accommodate "capacity" many entries
            int indicesCapacity = capacity + (capacity / 3);
            if (indicesCapacity < 0 || indicesCapacity > MAX_PREALLOCATED_INDICES_SIZE) {
                // This oddity is here because in some cases we are asked to allocate very large
                // dict in a situation where CPython (probably) does not preallocate at all and
                // fails later during the actual insertion on something unrelated before it can
                // reach the memory limit. We'd fail on the memory limit earlier -> difference in
                // behavior, so we take it easy if the requested size is too large. Maybe we should
                // rather revisit all such callsites instead of fixing this here...
                allocateData(MAX_PREALLOCATED_INDICES_SIZE);
            } else {
                int pow2 = getNextPow2(indicesCapacity);
                assert pow2 > INITIAL_INDICES_SIZE;
                allocateData(pow2);
            }
        }
        hasSideEffectingKeys = hasSideEffects;
    }

    public ObjectHashMap() {
        // Note: there is no point in null values/keys, we should use empty storage for such cases,
        // this map will almost certainly have some actual elements
        allocateData(INITIAL_INDICES_SIZE);
    }

    private void allocateData(int newSize) {
        assert isPow2(newSize);
        indices = new int[newSize];
        Arrays.fill(indices, EMPTY_INDEX);
        // since we allow ourselves to fill only up to 3/4 of the hash table, we need this many
        // entries for the actual values: (we intentionally over-allocate by a small constant)
        int quarter = newSize >> 2;
        int usableSize = 3 * quarter + 2;
        hashes = new long[usableSize];
        keysAndValues = new Object[usableSize * 2];
    }

    public void setSideEffectingKeysFlag() {
        hasSideEffectingKeys = true;
    }

    public Object forEachUntyped(ForEachNode<Object> node, Object argIn, LoopConditionProfile loopProfile) {
        Object arg = argIn;
        loopProfile.profileCounted(usedHashes);
        LoopNode.reportLoopCount(node, usedHashes);
        for (int i = 0; loopProfile.inject(i < usedHashes); i++) {
            Object key = getKey(i);
            if (key != null) {
                arg = node.execute(key, arg);
            }
        }
        return arg;
    }

    public void clear() {
        size = 0;
        usedHashes = 0;
        usedIndices = 0;
        allocateData(INITIAL_INDICES_SIZE);
    }

    public ObjectHashMap copy() {
        ObjectHashMap result = new ObjectHashMap();
        result.size = size;
        result.usedHashes = usedHashes;
        result.usedIndices = usedIndices;
        result.hashes = PythonUtils.arrayCopyOf(hashes, hashes.length);
        result.indices = PythonUtils.arrayCopyOf(indices, indices.length);
        result.keysAndValues = PythonUtils.arrayCopyOf(keysAndValues, keysAndValues.length);
        result.hasSideEffectingKeys = hasSideEffectingKeys;
        return result;
    }

    public HashingStorageIterable<Object> keys() {
        return new HashingStorageIterable<>(new KeysIteratorWrapper());
    }

    public HashingStorageIterable<Object> reverseKeys() {
        return new HashingStorageIterable<>(new ReverseKeysIteratorWrapper());
    }

    public MapCursor getEntries() {
        return new MapCursor();
    }

    public boolean hasSideEffect() {
        return hasSideEffectingKeys;
    }

    final class KeysIteratorWrapper implements Iterator<Object> {
        private int index;

        public KeysIteratorWrapper() {
            moveToNextValue();
        }

        private void moveToNextValue() {
            while (index < usedHashes && getValue(index) == null) {
                index++;
            }
        }

        @Override
        public boolean hasNext() {
            return index < usedHashes;
        }

        @Override
        public Object next() {
            assert hasNext();
            Object result = getKey(index++);
            moveToNextValue();
            return result;
        }

        public int getState() {
            return index;
        }

        public void setState(int state) {
            index = state;
        }
    }

    public final class ReverseKeysIteratorWrapper implements Iterator<Object> {
        private int index = usedHashes - 1;

        public ReverseKeysIteratorWrapper() {
            moveToNextValue();
        }

        private void moveToNextValue() {
            while (index >= 0 && getValue(index) == null) {
                index--;
            }
        }

        @Override
        public boolean hasNext() {
            return index >= 0;
        }

        @Override
        public Object next() {
            assert hasNext();
            Object result = getKey(index--);
            moveToNextValue();
            return result;
        }

        public int getState() {
            return index;
        }

        public void setState(int state) {
            index = state;
        }
    }

    @CompilerDirectives.ValueType
    public static final class DictKey {
        private final Object value;
        private final long hash;

        DictKey(Object value, long hash) {
            this.value = value;
            this.hash = hash;
        }

        public Object getValue() {
            return value;
        }

        public long getPythonHash() {
            return hash;
        }
    }

    public final class MapCursor {
        private int index = -1;

        private void moveToNextValue() {
            while (index < usedHashes && ObjectHashMap.this.getValue(index) == null) {
                index++;
            }
        }

        public boolean advance() {
            index++;
            moveToNextValue();
            return index < usedHashes;
        }

        public DictKey getKey() {
            return new DictKey(ObjectHashMap.this.getKey(index), hashes[index]);
        }

        public Object getValue() {
            return ObjectHashMap.this.getValue(index);
        }
    }

    // -------------------------------
    // methods for actual manipulation of the data-structure

    private int getBucketsCount() {
        return getBucketsCount(indices);
    }

    private static int getBucketsCount(int[] indices) {
        return indices.length;
    }

    private boolean needsResize(int[] localIndices) {
        // when the hash table is 3/4 full, we resize on insertion
        int bucketsCount = getBucketsCount(localIndices);
        int bucketsCntQuarter = Math.max(1, bucketsCount >> 2);
        return usedIndices + bucketsCntQuarter > bucketsCount;
    }

    public int size() {
        return size;
    }

    @GenerateUncached
    public abstract static class GetNode extends Node {
        public final Object get(ThreadState state, ObjectHashMap map, DictKey key) {
            return execute(state, map, key.getValue(), key.getPythonHash());
        }

        public final Object get(ThreadState state, ObjectHashMap map, Object key, long keyHash) {
            return execute(state, map, key, keyHash);
        }

        abstract Object execute(ThreadState state, ObjectHashMap map, Object key, long keyHash);

        // "public" for testing...
        @Specialization
        public static Object doGetWithRestart(ThreadState state, ObjectHashMap map, Object key, long keyHash,
                        @Cached BranchProfile lookupRestart,
                        @Cached("createCountingProfile()") ConditionProfile foundNullKey,
                        @Cached("createCountingProfile()") ConditionProfile foundSameHashKey,
                        @Cached("createCountingProfile()") ConditionProfile foundEqKey,
                        @Cached("createCountingProfile()") ConditionProfile collisionFoundNoValue,
                        @Cached("createCountingProfile()") ConditionProfile collisionFoundEqKey,
                        @Cached ConditionProfile hasState,
                        @Cached PyObjectRichCompareBool.EqNode eqNode) {
            while (true) {
                try {
                    return doGet(state, map, key, keyHash, foundNullKey, foundSameHashKey,
                                    foundEqKey, collisionFoundNoValue, collisionFoundEqKey, hasState, eqNode);
                } catch (RestartLookupException ignore) {
                    lookupRestart.enter();
                }
            }
        }

        static Object doGet(ThreadState state, ObjectHashMap map, Object key, long keyHash,
                        ConditionProfile foundNullKey,
                        ConditionProfile foundSameHashKey,
                        ConditionProfile foundEqKey,
                        ConditionProfile collisionFoundNoValue,
                        ConditionProfile collisionFoundEqKey,
                        ConditionProfile hasState,
                        PyObjectRichCompareBool.EqNode eqNode) throws RestartLookupException {
            assert map.checkInternalState();
            int[] indices = map.indices;
            int indicesLen = indices.length;

            int compactIndex = map.getIndex(indicesLen, keyHash);
            int index = indices[compactIndex];
            if (foundNullKey.profile(index == EMPTY_INDEX)) {
                return null;
            }
            if (foundSameHashKey.profile(index != DUMMY_INDEX)) {
                int unwrappedIndex = unwrapIndex(index);
                if (foundEqKey.profile(map.keysEqual(indices, state, unwrappedIndex, key, keyHash, eqNode, hasState))) {
                    return map.getValue(unwrappedIndex);
                } else if (!isCollision(indices[compactIndex])) {
                    // ^ note: we need to re-read indices[compactIndex],
                    // it may have been changed during __eq__
                    return null;
                }
            }

            // collision: intentionally counted loop
            long perturb = keyHash;
            int searchLimit = getBucketsCount(indices) + PERTURB_SHIFTS_COUT;
            int i = 0;
            try {
                for (; i < searchLimit; i++) {
                    if (indices != map.indices) {
                        // guards against things happening in the safepoint on the backedge
                        throw RestartLookupException.INSTANCE;
                    }
                    perturb >>>= PERTURB_SHIFT;
                    compactIndex = map.nextIndex(indicesLen, compactIndex, perturb);
                    index = map.indices[compactIndex];
                    if (collisionFoundNoValue.profile(index == EMPTY_INDEX)) {
                        return null;
                    }
                    if (index != DUMMY_INDEX) {
                        int unwrappedIndex = unwrapIndex(index);
                        if (collisionFoundEqKey.profile(map.keysEqual(indices, state, unwrappedIndex, key, keyHash, eqNode, hasState))) {
                            return map.getValue(unwrappedIndex);
                        } else if (!isCollision(indices[compactIndex])) {
                            // ^ note: we need to re-read indices[compactIndex],
                            // it may have been changed during __eq__
                            return null;
                        }
                    }
                }
            } finally {
                LoopNode.reportLoopCount(eqNode, i);
            }
            // all values are dummies? Not possible, since we should have compacted the
            // hashes/keysAndValues arrays in "remove". We always keep some head-room, so there must
            // be at least few empty slots, and we must have hit one.
            throw CompilerDirectives.shouldNotReachHere();
        }
    }

    @GenerateUncached
    public abstract static class PutNode extends Node {
        public final void put(ThreadState state, ObjectHashMap map, DictKey key, Object value) {
            execute(state, map, key.getValue(), key.getPythonHash(), value);
        }

        public final void put(ThreadState state, ObjectHashMap map, Object key, long keyHash, Object value) {
            execute(state, map, key, keyHash, value);
        }

        abstract void execute(ThreadState state, ObjectHashMap map, Object key, long keyHash, Object value);

        // "public" for testing...
        @Specialization
        public static void doPutWithRestart(ThreadState state, ObjectHashMap map, Object key, long keyHash, Object value,
                        @Cached BranchProfile lookupRestart,
                        @Cached("createCountingProfile()") ConditionProfile foundNullKey,
                        @Cached("createCountingProfile()") ConditionProfile foundEqKey,
                        @Cached("createCountingProfile()") ConditionProfile collisionFoundNoValue,
                        @Cached("createCountingProfile()") ConditionProfile collisionFoundEqKey,
                        @Cached BranchProfile rehash1Profile,
                        @Cached BranchProfile rehash2Profile,
                        @Cached ConditionProfile hasState,
                        @Cached PyObjectRichCompareBool.EqNode eqNode) {
            while (true) {
                try {
                    doPut(state, map, key, keyHash, value, foundNullKey, foundEqKey,
                                    collisionFoundNoValue, collisionFoundEqKey, rehash1Profile, rehash2Profile,
                                    hasState, eqNode);
                    return;
                } catch (RestartLookupException ignore) {
                    lookupRestart.enter();
                }
            }
        }

        static void doPut(ThreadState state, ObjectHashMap map, Object key, long keyHash, Object value,
                        ConditionProfile foundNullKey,
                        ConditionProfile foundEqKey,
                        ConditionProfile collisionFoundNoValue,
                        ConditionProfile collisionFoundEqKey,
                        BranchProfile rehash1Profile,
                        BranchProfile rehash2Profile,
                        ConditionProfile hasState,
                        PyObjectRichCompareBool.EqNode eqNode) throws RestartLookupException {
            assert map.checkInternalState();
            int[] indices = map.indices;
            int indicesLen = indices.length;

            int compactIndex = map.getIndex(indicesLen, keyHash);
            int index = indices[compactIndex];
            if (foundNullKey.profile(index == EMPTY_INDEX)) {
                map.putInNewSlot(indices, rehash1Profile, key, keyHash, value, compactIndex);
                return;
            }

            if (foundEqKey.profile(index != DUMMY_INDEX && map.keysEqual(indices, state, unwrapIndex(index), key, keyHash, eqNode, hasState))) {
                // we found the key, override the value, Python does not override the key though
                map.setValue(unwrapIndex(index), value);
                return;
            }

            // collision
            markCollision(indices, compactIndex);
            long perturb = keyHash;
            int searchLimit = getBucketsCount(indices) + PERTURB_SHIFTS_COUT;
            int i = 0;
            try {
                for (; i < searchLimit; i++) {
                    if (indices != map.indices) {
                        // guards against things happening in the safepoint on the backedge
                        throw RestartLookupException.INSTANCE;
                    }
                    perturb >>>= PERTURB_SHIFT;
                    compactIndex = map.nextIndex(indicesLen, compactIndex, perturb);
                    index = indices[compactIndex];
                    if (collisionFoundNoValue.profile(index == EMPTY_INDEX)) {
                        map.putInNewSlot(indices, rehash2Profile, key, keyHash, value, compactIndex);
                        return;
                    }
                    if (collisionFoundEqKey.profile(index != DUMMY_INDEX && map.keysEqual(indices, state, unwrapIndex(index), key, keyHash, eqNode, hasState))) {
                        // we found the key, override the value, Python does not override the key
                        // though
                        map.setValue(unwrapIndex(index), value);
                        return;
                    }
                    markCollision(indices, compactIndex);
                }
            } finally {
                LoopNode.reportLoopCount(eqNode, i);
            }
            // all values are dummies? Not possible, since we should have compacted the
            // hashes/keysAndValues arrays in "remove". Also, there must be an unused slot
            // available, because we checked at the beginning that we have at least ~1/4 of space
            // left
            throw CompilerDirectives.shouldNotReachHere();
        }
    }

    // Internal helper: it is not profiling, never rehashes, and it assumes that the hash map never
    // contains the key that we are inserting
    private void insertNewKey(int[] localIndices, Object key, long keyHash, Object value) {
        assert localIndices == this.indices;
        int compactIndex = getIndex(localIndices.length, keyHash);
        int index = localIndices[compactIndex];
        if (index == EMPTY_INDEX) {
            putInNewSlot(localIndices, key, keyHash, value, compactIndex);
            return;
        }

        // collision
        markCollision(localIndices, compactIndex);
        long perturb = keyHash;
        int searchLimit = getBucketsCount(localIndices) + PERTURB_SHIFTS_COUT;
        for (int i = 0; i < searchLimit; i++) {
            perturb >>>= PERTURB_SHIFT;
            compactIndex = nextIndex(localIndices.length, compactIndex, perturb);
            index = localIndices[compactIndex];
            if (index == EMPTY_INDEX) {
                putInNewSlot(localIndices, key, keyHash, value, compactIndex);
                return;
            }
            markCollision(localIndices, compactIndex);
        }
        // all values are dummies? Not possible, since we should have compacted the
        // hashes/keysAndValues arrays in "remove". Also, there must be an unused slot available,
        // because we checked at the beginning that we have at least ~1/4 of space left
        throw CompilerDirectives.shouldNotReachHere();
    }

    private void putInNewSlot(int[] localIndices, BranchProfile rehashProfile, Object key, long keyHash, Object value, int compactIndex) {
        assert indices == localIndices;
        if (CompilerDirectives.injectBranchProbability(SLOWPATH_PROBABILITY, needsResize(localIndices))) {
            rehashProfile.enter();
            rehashAndPut(key, keyHash, value);
            return;
        }
        putInNewSlot(localIndices, key, keyHash, value, compactIndex);
    }

    private void putInNewSlot(int[] localIndices, Object key, long keyHash, Object value, int compactIndex) {
        size++;
        usedIndices++;
        int newIndex = usedHashes++;
        localIndices[compactIndex] = newIndex;
        setValue(newIndex, value);
        setKey(newIndex, key);
        hashes[newIndex] = keyHash;
    }

    private boolean needsCompaction() {
        // if more than quarter of all the slots are occupied by dummy values -> compact
        int quarterOfUsable = hashes.length >> 2;
        int dummyCnt = usedHashes - size;
        return dummyCnt > quarterOfUsable;
    }

    @GenerateUncached
    public abstract static class RemoveNode extends Node {
        public final void remove(ThreadState state, ObjectHashMap map, DictKey key) {
            execute(state, map, key.getValue(), key.getPythonHash());
        }

        public final void remove(ThreadState state, ObjectHashMap map, Object key, long keyHash) {
            execute(state, map, key, keyHash);
        }

        abstract void execute(ThreadState state, ObjectHashMap map, Object key, long keyHash);

        // "public" for testing...
        @Specialization
        public static void doRemoveWithRestart(ThreadState state, ObjectHashMap map, Object key, long keyHash,
                        @Cached BranchProfile lookupRestart,
                        @Cached("createCountingProfile()") ConditionProfile foundNullKey,
                        @Cached("createCountingProfile()") ConditionProfile foundEqKey,
                        @Cached("createCountingProfile()") ConditionProfile collisionFoundNoValue,
                        @Cached("createCountingProfile()") ConditionProfile collisionFoundEqKey,
                        @Cached BranchProfile compactProfile,
                        @Cached ConditionProfile hasState,
                        @Cached PyObjectRichCompareBool.EqNode eqNode) {
            while (true) {
                try {
                    doRemove(state, map, key, keyHash, foundNullKey, foundEqKey,
                                    collisionFoundNoValue, collisionFoundEqKey, compactProfile,
                                    hasState, eqNode);
                    return;
                } catch (RestartLookupException ignore) {
                    lookupRestart.enter();
                }
            }
        }

        static void doRemove(ThreadState state, ObjectHashMap map, Object key, long keyHash,
                        ConditionProfile foundNullKey,
                        ConditionProfile foundEqKey,
                        ConditionProfile collisionFoundNoValue,
                        ConditionProfile collisionFoundEqKey,
                        BranchProfile compactProfile,
                        ConditionProfile hasState,
                        PyObjectRichCompareBool.EqNode eqNode) throws RestartLookupException {
            assert map.checkInternalState();
            // TODO: move this to the point after we find the value to remove?
            if (CompilerDirectives.injectBranchProbability(SLOWPATH_PROBABILITY, map.needsCompaction())) {
                compactProfile.enter();
                map.compact();
            }
            int[] indices = map.indices;
            int indicesLen = indices.length;

            // Note: CPython is not shrinking the capacity of the hash table on delete, we do the
            // same
            int compactIndex = map.getIndex(indicesLen, keyHash);
            int index = indices[compactIndex];
            if (foundNullKey.profile(index == EMPTY_INDEX)) {
                return; // not found
            }

            int unwrappedIndex = unwrapIndex(index);
            if (foundEqKey.profile(index != DUMMY_INDEX && map.keysEqual(indices, state, unwrappedIndex, key, keyHash, eqNode, hasState))) {
                indices[compactIndex] = DUMMY_INDEX;
                map.setValue(unwrappedIndex, null);
                map.setKey(unwrappedIndex, null);
                map.size--;
                return;
            }

            // collision: intentionally counted loop
            long perturb = keyHash;
            int searchLimit = getBucketsCount(indices) + PERTURB_SHIFTS_COUT;
            int i = 0;
            try {
                for (; i < searchLimit; i++) {
                    if (indices != map.indices) {
                        // guards against things happening in the safepoint on the backedge
                        throw RestartLookupException.INSTANCE;
                    }
                    perturb >>>= PERTURB_SHIFT;
                    compactIndex = map.nextIndex(indicesLen, compactIndex, perturb);
                    index = indices[compactIndex];
                    if (collisionFoundNoValue.profile(index == EMPTY_INDEX)) {
                        return; // not found
                    }
                    unwrappedIndex = unwrapIndex(index);
                    if (collisionFoundEqKey.profile(index != DUMMY_INDEX && map.keysEqual(indices, state, unwrappedIndex, key, keyHash, eqNode, hasState))) {
                        indices[compactIndex] = DUMMY_INDEX;
                        map.setValue(unwrappedIndex, null);
                        map.setKey(unwrappedIndex, null);
                        map.size--;
                        return;
                    }
                }
            } finally {
                LoopNode.reportLoopCount(eqNode, i);
            }
            // all values are dummies? Not possible, since we should have compacted the
            // hashes/keysAndValues arrays at the top
            throw CompilerDirectives.shouldNotReachHere();
        }
    }

    private static final class RestartLookupException extends Exception {
        private static final long serialVersionUID = -5517471989238569331L;
        private static final RestartLookupException INSTANCE = new RestartLookupException();

        public RestartLookupException() {
            super(null, null);
        }

        @SuppressWarnings("sync-override")
        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private boolean keysEqual(int[] originalIndices, ThreadState state, int index, Object key, long keyHash,
                    PyObjectRichCompareBool.EqNode eqNode, ConditionProfile hasState) throws RestartLookupException {
        if (hashes[index] != keyHash) {
            return false;
        }
        Object originalKey = getKey(index);
        if (originalKey == key) {
            return true;
        }
        VirtualFrame frame = hasState.profile(state == null) ? null : PArguments.frameForCall(state);
        boolean result = eqNode.execute(frame, originalKey, key);
        if (getKey(index) != originalKey || indices != originalIndices) {
            // Either someone overridden the slot we are just examining, or rehasing reallocated the
            // indices array. We need to restart the lookup. Other situations are OK:
            //
            // New entry was added: if its key is different to what we look for we don't care, if
            // its key collides with what we look for, it will be put at the end of the collision
            // chain, and we'll find it.
            //
            // Entry was removed: if it was not the entry we're looking at right now, we don't care.
            // Removal could have triggered a compaction, which shuffles things around in the arrays
            // (hashes, keysAndValues), but does not reallocate the arrays or changes collision
            // sequences.
            throw RestartLookupException.INSTANCE;
        }
        return result;
    }

    /**
     * Called when we need space for new entry. It determines the new size from the number of slots
     * occupied by real values (i.e., does not count dummy entries), so the new size may be actually
     * smaller than the old size if there were many dummy entries. The rehashing also removes the
     * dummy entries.
     */
    @TruffleBoundary
    private void rehashAndPut(Object newKey, long newKeyHash, Object newValue) {
        int requiredIndicesSize = usedHashes * GROWTH_RATE;
        // We need the hash table of this size, in order to accommodate "requiredIndicesSize" items
        int indicesCapacity = requiredIndicesSize + (requiredIndicesSize / 3);
        if (indicesCapacity < INITIAL_INDICES_SIZE) {
            indicesCapacity = INITIAL_INDICES_SIZE;
        } else {
            indicesCapacity = getNextPow2(indicesCapacity);
            if (indicesCapacity << 1 < 0) {
                // some arrays we allocate are 2 times the size
                throw new OutOfMemoryError();
            }
        }
        long[] oldHashes = hashes;
        Object[] oldKeysAndValues = keysAndValues;
        int oldUsedSize = usedHashes;
        int oldSize = size;
        allocateData(indicesCapacity);
        size = 0;
        usedHashes = 0;
        usedIndices = 0;
        int[] localIndices = this.indices;
        for (int i = 0; i < oldUsedSize; i++) {
            if (getValue(i, oldKeysAndValues) != null) {
                final Object key = getKey(i, oldKeysAndValues);
                insertNewKey(localIndices, key, oldHashes[i], getValue(i, oldKeysAndValues));
            }
        }
        assert size == oldSize : String.format("size=%d, oldSize=%d, oldUsedSize=%d, usedHashes=%d, usedIndices=%d",
                        size, oldSize, oldUsedSize, usedHashes, usedIndices);
        insertNewKey(localIndices, newKey, newKeyHash, newValue);
    }

    @TruffleBoundary
    private void compact() {
        // shuffle[X] will tell us by how much value X found in 'indices' should be shuffled to left
        int[] shuffle = new int[hashes.length];
        int currentShuffle = 0;
        int dummyCount = 0;
        for (int i = 0; i < usedHashes; i++) {
            Object value = getValue(i);
            if (value == null) {
                currentShuffle++;
                dummyCount++;
            } else if (currentShuffle > 0) {
                assert getValue(i - currentShuffle) == null;
                assert getKey(i - currentShuffle) == null;
                setValue(i - currentShuffle, value);
                setKey(i - currentShuffle, getKey(i));
                setValue(i, null);
                setKey(i, null);
                hashes[i - currentShuffle] = hashes[i];
                shuffle[i] = currentShuffle;
            }
        }
        usedHashes -= dummyCount; // We've "removed" the dummy entries
        int[] localIndices = indices;
        for (int i = 0; i < localIndices.length; i++) {
            int index = localIndices[i];
            if (index != EMPTY_INDEX && index != DUMMY_INDEX) {
                boolean collision = isCollision(index);
                int unwrapped = unwrapIndex(index);
                int newIndex = unwrapped - shuffle[unwrapped];
                localIndices[i] = newIndex;
                if (collision) {
                    markCollision(localIndices, i);
                }
            } else if (index == DUMMY_INDEX) {
                dummyCount--;
            }
        }
        // Indices may contain dummy values removed from hashes and keysAndValues arrays in some
        // previous rounds of compaction, but we should have seen at least this many dummy values
        assert dummyCount <= 0;
    }

    private int nextIndex(int indicesLen, int i, long perturb) {
        return getIndex(indicesLen, i * 5L + perturb + 1L);
    }

    private int getIndex(int indicesLen, long hash) {
        // since buckets count is power of 2, the & works as modulo
        return (int) (hash & (indicesLen - 1));
    }

    public static Object getKey(int index, Object[] keysAndValues) {
        return keysAndValues[index << 1];
    }

    public static Object getValue(int index, Object[] keysAndValues) {
        return keysAndValues[(index << 1) + 1];
    }

    public Object getKey(int index) {
        return getKey(index, keysAndValues);
    }

    public Object getValue(int index) {
        return getValue(index, keysAndValues);
    }

    public void setValue(int index, Object value) {
        keysAndValues[(index << 1) + 1] = value;
    }

    public void setKey(int index, Object key) {
        keysAndValues[(index << 1)] = key;
    }

    private boolean checkInternalState() {
        // We must have at least one empty slot, collision resolution relies on the fact that it is
        // always going to find an empty slot
        assert usedIndices < indices.length : usedIndices;
        return true;
    }

    private int getNextPow2(int n) {
        if (isPow2(n)) {
            return n;
        }
        return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(n));
    }

    private boolean isPow2(int n) {
        return Integer.bitCount(n) == 1;
    }
}
