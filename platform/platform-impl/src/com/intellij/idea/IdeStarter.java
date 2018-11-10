/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.idea;

import com.intellij.featureStatistics.fusCollectors.AppLifecycleUsageTriggerCollector;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.CommandLineProcessor;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.internal.statistic.eventLog.FeatureUsageLogger;
import com.intellij.internal.statistic.service.fus.collectors.FUSApplicationUsageTrigger;
import com.intellij.openapi.application.ApplicationStarterEx;
import com.intellij.openapi.application.JetBrainsProtocolHandler;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.impl.SystemDock;
import com.intellij.openapi.wm.impl.WindowManagerImpl;
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame;
import com.intellij.platform.PlatformProjectOpenProcessor;
import com.intellij.ui.CustomProtocolHandler;
import com.intellij.ui.Splash;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.EnumSet;

import static com.intellij.idea.IdeaApplication.initLAF;

public class IdeStarter extends ApplicationStarterEx {
  static final Logger LOG = Logger.getInstance("#com.intellij.idea.IdeaApplication");
  private Splash mySplash;
  private boolean myPerformProjectLoad;

  public IdeStarter(boolean performProjectLoad) {

    myPerformProjectLoad = performProjectLoad;
  }

  @Override
  public boolean isHeadless() {
    return false;
  }

  @Override
  public String getCommandName() {
    return null;
  }

  @Override
  public void premain(String[] args) {
    initLAF();
  }

  @Nullable
  public Splash showSplash() {
    final ApplicationInfoEx appInfo = ApplicationInfoImpl.getShadowInstance();
    final SplashScreen splashScreen = getSplashScreen();
    if (splashScreen == null) {
      mySplash = new Splash(appInfo);
      mySplash.show();
      return mySplash;
    }
    else {
      updateSplashScreen(appInfo, splashScreen);
      return null;
    }
  }

  private static void updateSplashScreen(@NotNull ApplicationInfoEx appInfo, @NotNull SplashScreen splashScreen) {
    final Graphics2D graphics = splashScreen.createGraphics();
    final Dimension size = splashScreen.getSize();
    if (Splash.showLicenseeInfo(graphics, 0, 0, size.height, appInfo.getSplashTextColor(), appInfo)) {
      splashScreen.update();
    }
  }

  @Nullable
  private static SplashScreen getSplashScreen() {
    try {
      return SplashScreen.getSplashScreen();
    }
    catch (Throwable t) {
      LOG.warn(t);
      return null;
    }
  }

  @Override
  public boolean canProcessExternalCommandLine() {
    return true;
  }

  @Override
  public void processExternalCommandLine(@NotNull String[] args, @Nullable String currentDirectory) {
    LOG.info("Request to open in " + currentDirectory + " with parameters: " + StringUtil.join(args, ","));

    if (args.length > 0) {
      String filename = args[0];
      File file = new File(currentDirectory, filename);

      if(file.exists()) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        if (virtualFile != null) {
          int line = -1;
          if (args.length > 2 && CustomProtocolHandler.LINE_NUMBER_ARG_NAME.equals(args[1])) {
            try {
              line = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
              LOG.error("Wrong line number:" + args[2]);
            }
          }
          EnumSet<PlatformProjectOpenProcessor.Option> options = EnumSet.noneOf(PlatformProjectOpenProcessor.Option.class);
          PlatformProjectOpenProcessor.doOpenProject(virtualFile, null, line, null, options);
        }
      }
      throw new IncorrectOperationException("Can't find file:" + file);
    }
  }

  private static Project loadProjectFromExternalCommandLine(String[] args) {
    Project project = null;
    if (args != null && args.length > 0 && args[0] != null) {
      LOG.info("IdeaApplication.loadProject");
      project = CommandLineProcessor.processExternalCommandLine(Arrays.asList(args), null);
    }
    return project;
  }

  @Override
  public void main(String[] args) {
    SystemDock.updateMenu();

    // if OS has dock, RecentProjectsManager will be already created, but not all OS have dock, so, we trigger creation here to ensure that RecentProjectsManager app listener will be added
    RecentProjectsManager.getInstance();

    // Event queue should not be changed during initialization of application components.
    // It also cannot be changed before initialization of application components because IdeEventQueue uses other
    // application components. So it is proper to perform replacement only here.
    final ApplicationEx app = ApplicationManagerEx.getApplicationEx();
    WindowManagerImpl windowManager = (WindowManagerImpl)WindowManager.getInstance();
    IdeEventQueue.getInstance().setWindowManager(windowManager);

    Ref<Boolean> willOpenProject = new Ref<>(Boolean.FALSE);
    AppLifecycleListener lifecyclePublisher = app.getMessageBus().syncPublisher(AppLifecycleListener.TOPIC);
    lifecyclePublisher.appFrameCreated(args, willOpenProject);

    LOG.info("App initialization took " + (System.nanoTime() - PluginManager.startupStart) / 1000000 + " ms");
    PluginManagerCore.dumpPluginClassStatistics();

    // Temporary check until the jre implementation has been checked and bundled
    if (Registry.is("ide.popup.enablePopupType")) {
      System.setProperty("jbre.popupwindow.settype", "true");
    }

    if (JetBrainsProtocolHandler.getCommand() != null || !willOpenProject.get()) {
      WelcomeFrame.showNow();
      lifecyclePublisher.welcomeScreenDisplayed();
    }
    else {
      windowManager.showFrame();
    }

    if (mySplash != null) {
      app.invokeLater(() -> {
        mySplash.dispose();
        mySplash = null; // Allow GC collect the splash window
      }, ModalityState.any());
    }

    TransactionGuard.submitTransaction(app, () -> {
      Project projectFromCommandLine = myPerformProjectLoad ? loadProjectFromExternalCommandLine(args) : null;
      app.getMessageBus().syncPublisher(AppLifecycleListener.TOPIC).appStarting(projectFromCommandLine);

      //noinspection SSBasedInspection
      SwingUtilities.invokeLater(PluginManager::reportPluginError);

      FUSApplicationUsageTrigger.getInstance().trigger(AppLifecycleUsageTriggerCollector.class, "ide.start");
      FeatureUsageLogger.INSTANCE.log("lifecycle", "app.started");
    });
  }

}
