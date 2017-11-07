package com.jetbrains.edu.android

import com.intellij.openapi.vfs.VirtualFile

data class EduGradleModule(val src: VirtualFile, val test: VirtualFile)