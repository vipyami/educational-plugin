package com.jetbrains.edu.android

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class TestAction : AnAction("Test Action") {

  override fun actionPerformed(e: AnActionEvent) {
    Messages.showInfoMessage("Test action", "Test Action")
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = true
  }
}