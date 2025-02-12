/* Copyright (c) 2018, 2022, Oracle and/or its affiliates.
 * Copyright (C) 1996-2017 Python Software Foundation
 *
 * Licensed under the PYTHON SOFTWARE FOUNDATION LICENSE VERSION 2
 */
/* Boolean object interface */

#ifndef Py_BOOLOBJECT_H
#define Py_BOOLOBJECT_H
#ifdef __cplusplus
extern "C" {
#endif


PyAPI_DATA(PyTypeObject) PyBool_Type;

#define PyBool_Check(x) (Py_TYPE(x) == &PyBool_Type)

/* Py_False and Py_True are the only two bools in existence.
Don't forget to apply Py_INCREF() when returning either!!! */

/* Don't use these directly */
PyAPI_DATA(struct _longobject*) _Py_FalseStructReference;
PyAPI_DATA(struct _longobject*) _Py_TrueStructReference;
#define _Py_TrueStruct (*_Py_TrueStructReference)
#define _Py_FalseStruct (*_Py_FalseStructReference)

/* Use these macros */
#define Py_False ((PyObject *) _Py_FalseStructReference)
#define Py_True ((PyObject *) _Py_TrueStructReference)

/* Macros for returning Py_True or Py_False, respectively */
#define Py_RETURN_TRUE return Py_INCREF(Py_True), Py_True
#define Py_RETURN_FALSE return Py_INCREF(Py_False), Py_False

/* Function to return a bool from a C long */
PyAPI_FUNC(PyObject *) PyBool_FromLong(long);

#ifdef __cplusplus
}
#endif
#endif /* !Py_BOOLOBJECT_H */
