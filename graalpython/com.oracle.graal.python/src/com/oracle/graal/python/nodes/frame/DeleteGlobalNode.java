/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.nodes.frame;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.nodes.attributes.DeleteAttributeNode;
import com.oracle.graal.python.nodes.statement.StatementNode;
import com.oracle.graal.python.nodes.subscript.DeleteItemNode;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;

public abstract class DeleteGlobalNode extends StatementNode implements GlobalNode {
    private final String attributeId;
    protected final Assumption singleContextAssumption = PythonLanguage.getCurrent().singleContextAssumption;

    DeleteGlobalNode(String attributeId) {
        this.attributeId = attributeId;
    }

    public static DeleteGlobalNode create(String attributeId) {
        return DeleteGlobalNodeGen.create(attributeId);
    }

    public abstract Object execute(VirtualFrame frame, Object value);

    @Specialization(guards = {"getGlobals(frame) == cachedGlobals", "isDict(cachedGlobals)"}, assumptions = "singleContextAssumption", limit = "1")
    Object deleteDictCached(VirtualFrame frame,
                    @Cached(value = "getGlobals(frame)", weak = true) Object cachedGlobals,
                    @Cached DeleteItemNode deleteNode) {
        deleteNode.executeWith(frame, cachedGlobals, attributeId);
        return PNone.NONE;
    }

    @Specialization(guards = "isDict(getGlobals(frame))", replaces = "deleteDictCached")
    Object deleteDict(VirtualFrame frame,
                    @Cached DeleteItemNode deleteNode) {
        deleteNode.executeWith(frame, PArguments.getGlobals(frame), attributeId);
        return PNone.NONE;
    }

    @Specialization(guards = {"getGlobals(frame) == cachedGlobals", "isModule(cachedGlobals)"}, assumptions = "singleContextAssumption", limit = "1")
    Object deleteModuleCached(VirtualFrame frame,
                    @Cached(value = "getGlobals(frame)", weak = true) Object cachedGlobals,
                    @Cached DeleteAttributeNode storeNode) {
        storeNode.execute(frame, cachedGlobals, attributeId);
        return PNone.NONE;
    }

    @Specialization(guards = "isModule(getGlobals(frame))", replaces = "deleteModuleCached")
    Object deleteModule(VirtualFrame frame,
                    @Cached DeleteAttributeNode storeNode) {
        storeNode.execute(frame, PArguments.getGlobals(frame), attributeId);
        return PNone.NONE;
    }

    @Override
    public String getAttributeId() {
        return attributeId;
    }
}
