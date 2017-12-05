package com.jetbrains.edu.android

import com.android.ddmlib.IDevice
import com.android.sdklib.internal.avd.AvdInfo
import com.android.tools.idea.avdmanager.AvdManagerConnection
import com.android.tools.idea.avdmanager.AvdOptionsModel
import com.android.tools.idea.avdmanager.AvdWizardUtils
import com.android.tools.idea.ddms.adb.AdbService
import com.android.tools.idea.run.LaunchableAndroidDevice
import com.google.common.util.concurrent.ListenableFuture
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.jetbrains.edu.learning.actions.CheckAction
import com.jetbrains.edu.learning.checker.CheckResult
import com.jetbrains.edu.learning.checker.TaskChecker
import com.jetbrains.edu.learning.checker.TestsOutputParser
import com.jetbrains.edu.learning.courseFormat.CheckStatus
import com.jetbrains.edu.learning.courseFormat.tasks.EduTask
import org.jetbrains.android.sdk.AndroidSdkUtils

internal class AndroidChecker(task: EduTask, project: Project) : TaskChecker<EduTask>(task, project) {

  override fun validateEnvironment(): Boolean {
    try {
      if (startEmulatorIfExists()) return true
      Messages.showInfoMessage(myProject, "Android emulator is required to check tasks. New emulator will be created and launched.", "Android Emulator not Found")
      val avdOptionsModel = AvdOptionsModel(null)
      val dialog = AvdWizardUtils.createAvdWizard(null, myProject, avdOptionsModel)
      if (dialog.showAndGet()) {
        val avd = avdOptionsModel.createdAvd
        return launchEmulator(avd)
      }
    } catch (e: Exception) {
      // ignore
    }

    return false
  }

  override fun check(): CheckResult = try {
    val taskIndex = myTask.index
    val lessonIndex = myTask.lesson.index

    val cmd = GeneralCommandLine()
            .withExePath(GRADLEW)
            .withWorkDirectory(myProject.baseDir.path)
            .withParameters(":lesson$lessonIndex:task$taskIndex:$CONNECTED_ANDROID_TEST")
    getTestOutput(cmd.createProcess(), cmd.commandLineString)
  } catch (e: Exception) {
    LOG.warn(e)
    CheckResult(CheckStatus.Unchecked, CheckAction.FAILED_CHECK_LAUNCH)
  }

  @Throws(Exception::class)
  private fun startEmulatorIfExists(): Boolean {
    val adbFile = AndroidSdkUtils.getAdb(myProject)
    if (adbFile == null) {
      LOG.warn("Can't find adbFile location")
      return false
    }
    val abd = AdbService.getInstance().getDebugBridge(adbFile).get()
    if (abd.devices.any { it.isEmulator && it.avdName != null }) return true // there is running emulator

    for (avd in AvdManagerConnection.getDefaultAvdManagerConnection().getAvds(true)) {
      if (launchEmulator(avd)) return true
    }
    return false
  }

  @Throws(Exception::class)
  private fun launchEmulator(avd: AvdInfo): Boolean {
    if (avd.status == AvdInfo.AvdStatus.OK) {
      val launchableAndroidDevice = LaunchableAndroidDevice(avd)
      val device = ProgressManager.getInstance().run(object : Task.WithResult<IDevice, Exception>(myProject, "Launching Emulator", false) {
        private var future: ListenableFuture<IDevice>? = null

        @Throws(Exception::class)
        override fun compute(indicator: ProgressIndicator): IDevice {
          indicator.isIndeterminate = true
          ApplicationManager.getApplication().invokeAndWait { future = launchableAndroidDevice.launch(myProject) }
          return future!!.get()
        }

        override fun onCancel() {
          future?.cancel(true)
        }
      })
      if (device != null) return true
    }
    return false
  }

  companion object {

    private val LOG = Logger.getInstance(AndroidChecker::class.java)

    private val GRADLEW = "./gradlew"
    private val CONNECTED_ANDROID_TEST = "connectedAndroidTest"

    private fun getTestOutput(testProcess: Process, commandLine: String): CheckResult {
      val handler = CapturingProcessHandler(testProcess, null, commandLine)
      val output = if (ProgressManager.getInstance().hasProgressIndicator()) {
        handler.runProcessWithProgressIndicator(ProgressManager.getInstance().progressIndicator)
      } else {
        handler.runProcess()
      }
      val buildSuccessful = output.exitCode == 0

      val testsOutput = TestsOutputParser.getTestsOutput(output, false)
      return if (testsOutput.isSuccess && !buildSuccessful) {
        CheckResult(CheckStatus.Unchecked, CheckAction.FAILED_CHECK_LAUNCH)
      } else {
        val status = if (testsOutput.isSuccess) CheckStatus.Solved else CheckStatus.Failed
        CheckResult(status, testsOutput.message)
      }
    }
  }
}
