/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.nodes.function.builtins.clinic;

import com.oracle.graal.python.annotations.ArgumentClinic.PrimitiveType;
import com.oracle.graal.python.annotations.ClinicConverterFactory;
import com.oracle.graal.python.annotations.ClinicConverterFactory.DefaultValue;
import com.oracle.graal.python.annotations.ClinicConverterFactory.UseDefaultForNone;
import com.oracle.graal.python.lib.PyNumberIndexNode;
import com.oracle.graal.python.nodes.util.CastToJavaLongExactNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;

public abstract class LongIndexConverterNode extends LongConversionBaseNode {
    protected LongIndexConverterNode(long defaultValue, boolean useDefaultForNone) {
        super(defaultValue, useDefaultForNone);
    }

    @Specialization(guards = "!isHandledPNone(value)")
    static long doOthers(VirtualFrame frame, Object value,
                    @Cached PyNumberIndexNode indexNode,
                    @Cached CastToJavaLongExactNode cast) {
        return cast.execute(indexNode.execute(frame, value));
    }

    @ClinicConverterFactory(shortCircuitPrimitive = {PrimitiveType.Int, PrimitiveType.Long})
    public static LongIndexConverterNode create(@DefaultValue long defaultValue, @UseDefaultForNone boolean useDefaultForNone) {
        return LongIndexConverterNodeGen.create(defaultValue, useDefaultForNone);
    }

    @ClinicConverterFactory(shortCircuitPrimitive = {PrimitiveType.Int, PrimitiveType.Long})
    public static LongIndexConverterNode create(@UseDefaultForNone boolean useDefaultForNone) {
        assert !useDefaultForNone : "defaultValue must be provided if useDefaultForNone is true";
        return LongIndexConverterNodeGen.create(0L, false);
    }

    public static LongIndexConverterNode create() {
        return LongIndexConverterNode.create(false);
    }
}