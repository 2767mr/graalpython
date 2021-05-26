/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.lib;

import static com.oracle.graal.python.builtins.PythonBuiltinClassType.TypeError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.ValueError;

import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.type.SpecialMethodSlot;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PNodeWithContext;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.call.special.CallUnaryMethodNode;
import com.oracle.graal.python.nodes.call.special.LookupSpecialMethodSlotNode;
import com.oracle.graal.python.nodes.object.GetClassNode;
import com.oracle.graal.python.nodes.util.CastToJavaIntLossyNode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Equivalent of CPython's {@code PyObject_Size} and {@code PyObject_Length} (alias of the former).
 * Returns length of objects by calling their {@code __len__}. Throws an exception if they don't
 * have any. Coerces the result to size integer using {@link PyNumberAsSizeNode}.
 */
@ImportStatic(SpecialMethodSlot.class)
@GenerateUncached
public abstract class PyObjectSizeNode extends PNodeWithContext {
    public abstract int execute(Frame frame, Object object);

    protected abstract Object executeObject(Frame frame, Object object);

    @Specialization(rewriteOn = UnexpectedResultException.class)
    int doInt(VirtualFrame frame, Object object,
                    @Shared("getClass") @Cached GetClassNode getClassNode,
                    @Shared("lookupLen") @Cached(parameters = "Len") LookupSpecialMethodSlotNode lookupLen,
                    @Shared("callLen") @Cached CallUnaryMethodNode callLen,
                    @Shared("index") @Cached PyNumberIndexNode indexNode,
                    @Shared("castLossy") @Cached CastToJavaIntLossyNode castLossy,
                    @Shared("asSize") @Cached PyNumberAsSizeNode asSizeNode,
                    @Shared("raise") @Cached PRaiseNode raiseNode) throws UnexpectedResultException {
        Object lenDescr = lookupLen.execute(frame, getClassNode.execute(object), object);
        if (lenDescr == PNone.NO_VALUE) {
            throw raiseNode.raise(TypeError, ErrorMessages.OBJ_HAS_NO_LEN, object);
        }
        try {
            return checkLen(raiseNode, callLen.executeInt(frame, lenDescr, object));
        } catch (UnexpectedResultException e) {
            int len = PyObjectSizeNode.convertLen(frame, indexNode, castLossy, asSizeNode, e.getResult());
            int result = PyObjectSizeNode.checkLen(raiseNode, len);
            throw new UnexpectedResultException(result);
        }
    }

    @Specialization(replaces = "doInt")
    int doObject(VirtualFrame frame, Object object,
                    @Shared("getClass") @Cached GetClassNode getClassNode,
                    @Shared("lookupLen") @Cached(parameters = "Len") LookupSpecialMethodSlotNode lookupLen,
                    @Shared("callLen") @Cached CallUnaryMethodNode callLen,
                    @Shared("index") @Cached PyNumberIndexNode indexNode,
                    @Shared("castLossy") @Cached CastToJavaIntLossyNode castLossy,
                    @Shared("asSize") @Cached PyNumberAsSizeNode asSizeNode,
                    @Shared("raise") @Cached PRaiseNode raiseNode) {
        Object lenDescr = lookupLen.execute(frame, getClassNode.execute(object), object);
        if (lenDescr == PNone.NO_VALUE) {
            throw raiseNode.raise(TypeError, ErrorMessages.OBJ_HAS_NO_LEN, object);
        }
        Object result = callLen.executeObject(frame, lenDescr, object);
        int len = PyObjectSizeNode.convertLen(frame, indexNode, castLossy, asSizeNode, result);
        return PyObjectSizeNode.checkLen(raiseNode, len);
    }

    static int checkLen(PRaiseNode raiseNode, int len) {
        if (len < 0) {
            throw raiseNode.raise(ValueError, ErrorMessages.LEN_SHOULD_RETURN_GT_ZERO);
        }
        return len;
    }

    static int convertLen(VirtualFrame frame, PyNumberIndexNode indexNode, CastToJavaIntLossyNode castLossy, PyNumberAsSizeNode asSizeNode, Object result) {
        int len;
        Object index = indexNode.execute(frame, result);
        try {
            len = asSizeNode.executeExact(frame, index);
        } catch (PException e) {
            len = castLossy.execute(index);
            if (len >= 0) {
                throw e;
            }
        }
        return len;
    }

    public static PyObjectSizeNode create() {
        return PyObjectSizeNodeGen.create();
    }

    public static PyObjectSizeNode getUncached() {
        return PyObjectSizeNodeGen.getUncached();
    }
}
