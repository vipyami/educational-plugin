package com.jetbrains.edu.kotlin.checker

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.edu.learning.checker.gradle.GradleTaskCheckerProvider
import org.jetbrains.kotlin.idea.run.KotlinRunConfigurationProducer
import org.jetbrains.kotlin.psi.KtElement

class KtTaskCheckerProvider : GradleTaskCheckerProvider() {

  override fun mainClassForFile(project: Project, file: VirtualFile): String? {
    val psiFile = PsiManager.getInstance(project).findFile(file)
    if (psiFile != null) {
      println("KtTaskCheckerProvider: can't find `$file`")
      return null
    }
    val ktElements = PsiTreeUtil.findChildrenOfType(psiFile, KtElement::class.java)
    println("KtTaskCheckerProvider: ktElements: `$ktElements`")
    val container = KotlinRunConfigurationProducer.getEntryPointContainer(ktElements.first())
    println("KtTaskCheckerProvider: container: `$container`")
    val startClassFqName = KotlinRunConfigurationProducer.getStartClassFqName(container)
    println("KtTaskCheckerProvider: startClassFqName: `$startClassFqName`")
    return startClassFqName

  }
}
