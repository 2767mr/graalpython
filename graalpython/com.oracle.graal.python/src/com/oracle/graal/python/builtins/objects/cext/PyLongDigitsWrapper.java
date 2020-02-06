/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.builtins.objects.cext;

import static com.oracle.graal.python.builtins.objects.cext.NativeCAPISymbols.FUN_GET_UINT32_ARRAY_TYPE_ID;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.objects.cext.CExtNodes.PCallCapiFunction;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.llvm.spi.NativeTypeLibrary;

/**
 * Emulates {@code ob_digit} of {@code struct _longobject} for Python integers.
 */
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(NativeTypeLibrary.class)
public final class PyLongDigitsWrapper extends PythonNativeWrapper {

    public PyLongDigitsWrapper(int delegate) {
        super(delegate);
    }

    public PyLongDigitsWrapper(long delegate) {
        super(delegate);
    }

    public PyLongDigitsWrapper(PInt delegate) {
        super(delegate);
    }

    @Override
    public int hashCode() {
        CompilerAsserts.neverPartOfCompilation();
        final int prime = 31;
        int result = 1;
        result = prime * result + PythonNativeWrapperLibrary.getUncached().getDelegate(this).hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        // n.b.: (tfel) This is hopefully fine here, since if we get to this
        // code path, we don't speculate that either of those objects is
        // constant anymore, so any caching on them won't happen anyway
        PythonNativeWrapperLibrary lib = PythonNativeWrapperLibrary.getUncached();
        return lib.getDelegate(this) == lib.getDelegate((PyLongDigitsWrapper) obj);
    }

    @ExportMessage
    final long getArraySize(
                    @CachedLibrary("this") PythonNativeWrapperLibrary lib) {
        Object delegate = lib.getDelegate(this);
        if (delegate instanceof Integer) {
            return Integer.BYTES / 4;
        } else if (delegate instanceof Long) {
            return Long.BYTES / 4;
        }
        assert delegate instanceof PInt;
        return ((PInt) delegate).bitCount();
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    final boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    final Object readArrayElement(long index,
                    @CachedLibrary("this") PythonNativeWrapperLibrary lib) throws InvalidArrayIndexException {
        Object delegate = lib.getDelegate(this);
        if (delegate instanceof Integer) {
            if (index == 0) {
                return ((Number) delegate).intValue();
            }
        } else if (delegate instanceof Long) {
            if (index >= 0 && index < 2) {
                return ((Number) delegate).intValue();
            }
        } else {
            byte[] bytes = ((PInt) delegate).toByteArray();
            if (index >= 0 && index < bytes.length / 4) {
                // the cast to int is safe since the length check already succeeded
                return getUInt32(bytes, (int) index);
            }
        }
        throw InvalidArrayIndexException.create(index);
    }

    private static long byteAsULong(byte b) {
        return ((long) b) & 0xFFL;
    }

    public static long getUInt32(byte[] bytes, int index) {
        return byteAsULong(bytes[index]) | (byteAsULong(bytes[index + 1]) << 8) | (byteAsULong(bytes[index + 2]) << 16) | (byteAsULong(bytes[index + 3]) << 24);
    }

    static int getCallSiteInlineCacheMaxDepth() {
        return PythonOptions.getCallSiteInlineCacheMaxDepth();
    }

    @ExportMessage
    final boolean isArrayElementReadable(long identifier,
                    @CachedLibrary("this") PythonNativeWrapperLibrary lib) {
        // also include the implicit null-terminator
        return 0 <= identifier && identifier <= getArraySize(lib);
    }

    @ExportMessage
    public void toNative(
                    @CachedLibrary("this") PythonNativeWrapperLibrary lib,
                    @Cached InvalidateNativeObjectsAllManagedNode invalidateNode,
                    @Cached PCallCapiFunction callToNativeNode) {
        invalidateNode.execute();
        if (!lib.isNative(this)) {
            Object ptr = callToNativeNode.call(NativeCAPISymbols.FUN_PY_TRUFFLE_INT_ARRAY_TO_NATIVE, this, getArraySize(lib));
            setNativePointer(ptr);
        }
    }

    @ExportMessage
    public boolean isPointer(
                    @Cached CExtNodes.IsPointerNode pIsPointerNode) {
        return pIsPointerNode.execute(this);
    }

    @ExportMessage
    public long asPointer(
                    @CachedLibrary(limit = "1") InteropLibrary interopLibrary,
                    @CachedLibrary("this") PythonNativeWrapperLibrary lib) throws UnsupportedMessageException {
        Object nativePointer = lib.getNativePointer(this);
        if (nativePointer instanceof Long) {
            return (long) nativePointer;
        }
        return interopLibrary.asPointer(nativePointer);
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    protected boolean hasNativeType() {
        return true;
    }

    @ExportMessage
    abstract static class GetNativeType {

        static Object callGetUInt32ArrayTypeIDUncached(PyLongDigitsWrapper digitsWrapper) {
            return PCallCapiFunction.getUncached().call(FUN_GET_UINT32_ARRAY_TYPE_ID, 0);
        }

        @Specialization(assumptions = "singleContextAssumption()")
        static Object doByteArray(@SuppressWarnings("unused") PyLongDigitsWrapper object,
                        @Exclusive @Cached("callGetUInt32ArrayTypeIDUncached(object)") Object nativeType) {
            return nativeType;
        }

        @Specialization(replaces = "doByteArray")
        static Object doByteArrayMultiCtx(@SuppressWarnings("unused") PyLongDigitsWrapper object,
                        @Cached PCallCapiFunction callGetTypeIDNode) {
            return callGetTypeIDNode.call(FUN_GET_UINT32_ARRAY_TYPE_ID, 0);
        }

        protected static Assumption singleContextAssumption() {
            return PythonLanguage.getCurrent().singleContextAssumption;
        }
    }
}
