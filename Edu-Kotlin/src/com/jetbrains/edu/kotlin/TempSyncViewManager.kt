package com.jetbrains.edu.kotlin

import com.intellij.build.BuildContentManager
import com.intellij.build.SyncViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.openapi.project.Project


class TempSyncViewManager(project: Project, buildContentManager: BuildContentManager) : SyncViewManager(project, buildContentManager) {
  override fun onEvent(event: BuildEvent) {
    if (event is FinishBuildEvent) {
      val failureResult = event.result as FailureResult
      for (failure in failureResult.failures) {
        println("======Build Failure:\n${failure.message}")
      }
    }
  }
}

