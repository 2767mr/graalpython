# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
import sys
import marshal
IS_GRAAL = sys.implementation.name == "graalpython"


if IS_GRAAL:
    get_code = lambda n,s: marshal.loads(__graalpython__.compile_cpyc(n,s))
else:
    def get_code(n,s):
        c = compile(s,n,"exec")
        import dis
        dis.dis(c)
        return c


CODE = get_code("bench.py", """
import sys
# def foo():
#   pass
len(sys.__name__)
""")


def measure(num):
    for i in range(num):
        exec(CODE)


def __benchmark__(num=5):
    measure(num)


##########################
# Measurements
"""
CPython
  Unmarshal: 0.012s
  Execution: 0.010s

Graalpython
  Interpreter
    GPCode
      Unmarshal: 0.197s
      Execution: 0.024s
    CPCode
      Unmarshal: 0.035s
      Execution: 0.951s
  Compiler
    GPCode
      Unmarshal: 0.168s
      Execution: 0.009s
    CPCode
      Unmarshal: 0.032s
      Execution: 0.009s
"""
