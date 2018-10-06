// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore

import com.intellij.credentialStore.kdbx.IncorrectMasterPasswordException
import com.intellij.credentialStore.keePass.KeePassFileManager
import com.intellij.credentialStore.keePass.MasterKeyFileStorage
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.impl.PasswordSafeImpl
import com.intellij.ide.passwordSafe.impl.createPersistentCredentialStore
import com.intellij.ide.passwordSafe.impl.getDefaultKeePassDbFile
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurableBase
import com.intellij.openapi.options.ConfigurableUi
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.TextFieldWithHistoryWithBrowseButton
import com.intellij.ui.components.RadioButton
import com.intellij.ui.layout.*
import com.intellij.util.io.exists
import com.intellij.util.io.isDirectory
import com.intellij.util.text.nullize
import gnu.trove.THashMap
import java.io.File
import java.nio.file.Paths
import javax.swing.JPanel
import kotlin.properties.Delegates.notNull

internal class PasswordSafeConfigurable(private val settings: PasswordSafeSettings) : ConfigurableBase<PasswordSafeConfigurableUi, PasswordSafeSettings>("application.passwordSafe",
                                                                                                                                                         "Passwords",
                                                                                                                                                         "reference.ide.settings.password.safe") {
  override fun getSettings() = settings

  override fun createUi() = PasswordSafeConfigurableUi()
}

internal class PasswordSafeConfigurableUi : ConfigurableUi<PasswordSafeSettings> {
  private val inKeychain = RadioButton("In native Keychain")

  private val inKeePass = RadioButton("In KeePass")
  private var keePassDbFile: TextFieldWithHistoryWithBrowseButton by notNull()

  private val rememberPasswordsUntilClosing = RadioButton("Do not save, forget passwords after restart")

  private val modeToRow = THashMap<ProviderType, Row>()

  override fun reset(settings: PasswordSafeSettings) {
    when (settings.providerType) {
      ProviderType.MEMORY_ONLY -> rememberPasswordsUntilClosing.isSelected = true
      ProviderType.KEYCHAIN -> inKeychain.isSelected = true
      ProviderType.KEEPASS -> inKeePass.isSelected = true
      else -> throw IllegalStateException("Unknown provider type: ${settings.providerType}")
    }

    @Suppress("IfThenToElvis")
    keePassDbFile.text = settings.keepassDb ?: getDefaultKeePassDbFile().toString()
    updateEnabledState()
  }

  override fun isModified(settings: PasswordSafeSettings): Boolean {
    return getNewProviderType() != settings.providerType || isKeepassFileLocationChanged(settings)
  }

  private fun isKeepassFileLocationChanged(settings: PasswordSafeSettings): Boolean {
    return getNewProviderType() == ProviderType.KEEPASS && getNewDbFileAsString() != settings.keepassDb
  }

  override fun apply(settings: PasswordSafeSettings) {
    val providerType = getNewProviderType()
    val passwordSafe = PasswordSafe.instance as PasswordSafeImpl
    if (settings.providerType != providerType) {
      @Suppress("NON_EXHAUSTIVE_WHEN")
      when (providerType) {
        ProviderType.MEMORY_ONLY -> closeCurrentStoreIfKeePass()

        ProviderType.KEYCHAIN -> {
          passwordSafe.currentProvider = createPersistentCredentialStore()!!
        }

        ProviderType.KEEPASS -> createAndSaveKeePassDatabaseWithNewOptions(settings)
        else -> throw IllegalStateException("Unknown provider type: $providerType")
      }
    }
    else if (isKeepassFileLocationChanged(settings)) {
      createAndSaveKeePassDatabaseWithNewOptions(settings)
    }

    settings.providerType = providerType
  }

  private fun createAndSaveKeePassDatabaseWithNewOptions(settings: PasswordSafeSettings) {
    // existing in-memory KeePass database is not used, the same as if switched to KEYCHAIN
    // for KeePass not clear - should we append in-memory credentials to existing database or not
    // (and if database doesn't exist, should we append or not), so, wait first user request (prefer to keep implementation simple)
    closeCurrentStoreIfKeePass()

    val newDbFile = getNewDbFile() ?: throw ConfigurationException("KeePass database path is empty.")
    if (newDbFile.isDirectory()) {
      // we do not normalize as we do on file choose because if user decoded to type path manually,
      // it should be valid path and better to avoid any magic here
      throw ConfigurationException("KeePass database file is directory.")
    }
    if (!newDbFile.fileName.toString().endsWith(".kdbx")) {
      throw ConfigurationException("KeePass database file should ends with \".kdbx\".")
    }

    settings.keepassDb = newDbFile.toString()
    try {
      KeePassCredentialStore(dbFile = newDbFile, masterKeyFile = getDefaultMasterPasswordFile()).save()
    }
    catch (e: IncorrectMasterPasswordException) {
      throw ConfigurationException("Master password for KeePass database is not correct (\"Clear\" can be used to reset database).")
    }
  }

  private fun getNewDbFile() = getNewDbFileAsString()?.let { Paths.get(it) }

  private fun getNewDbFileAsString() = keePassDbFile.text.trim().nullize()

  private fun updateEnabledState() {
    modeToRow[ProviderType.KEEPASS]?.subRowsEnabled = getNewProviderType() == ProviderType.KEEPASS
  }

  override fun getComponent(): JPanel {
    return panel {
      row { label("Save passwords:") }

      buttonGroup({ updateEnabledState() }) {
        if (SystemInfo.isLinux || isMacOsCredentialStoreSupported) {
          row {
            inKeychain()
          }
        }

        modeToRow[ProviderType.KEEPASS] = row {
          inKeePass()
          row("Database:") {
            val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleLocalFileDescriptor().withFileFilter {
              it.isDirectory || it.name.endsWith(".kdbx")
            }
            keePassDbFile = textFieldWithBrowseButton("KeePass Database File",
                                                      fileChooserDescriptor = fileChooserDescriptor,
                                                      fileChosen = {
                                                        when {
                                                          it.isDirectory -> "${it.path}${File.separator}$DB_FILE_NAME"
                                                          else -> it.path
                                                        }
                                                      },
                                                      comment = if (SystemInfo.isWindows) null else "Stored using weak encryption. It is recommended to store on encrypted volume for additional security.")
            gearButton(
              ClearKeePassDatabaseAction(),
              ImportKeePassDatabaseAction(),
              ChangeKeePassDatabaseMasterPasswordAction()
            )
          }
        }

        row {
          rememberPasswordsUntilClosing()
        }
      }
    }
  }

  private fun createKeePassFileManager(): KeePassFileManager? {
    return KeePassFileManager(getNewDbFile() ?: return null, getDefaultMasterPasswordFile())
  }

  private fun getNewProviderType(): ProviderType {
    return when {
      rememberPasswordsUntilClosing.isSelected -> ProviderType.MEMORY_ONLY
      inKeePass.isSelected -> ProviderType.KEEPASS
      else -> ProviderType.KEYCHAIN
    }
  }

  private inner class ClearKeePassDatabaseAction : DumbAwareAction("Clear") {
    override fun actionPerformed(event: AnActionEvent) {
      if (!MessageDialogBuilder.yesNo("Clear Passwords", "Are you sure want to remove all passwords?").yesText("Remove Passwords").isYes) {
        return
      }

      closeCurrentStoreIfKeePass()

      LOG.info("Passwords cleared", Error())
      createKeePassFileManager()?.clear()
    }

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = getNewDbFile()?.exists() ?: false
    }
  }

  private inner class ImportKeePassDatabaseAction : DumbAwareAction("Import") {
    override fun actionPerformed(event: AnActionEvent) {
      closeCurrentStoreIfKeePass()

      FileChooserDescriptorFactory.createSingleLocalFileDescriptor()
        .withFileFilter {
          !it.isDirectory && it.nameSequence.endsWith(".kdbx")
        }
        .chooseFile(event) {
          createKeePassFileManager()?.import(Paths.get(it.path), event)
        }
    }
  }

  private inner class ChangeKeePassDatabaseMasterPasswordAction : DumbAwareAction("${if (MasterKeyFileStorage(getDefaultMasterPasswordFile()).isAutoGenerated()) "Set" else "Change"} Master Password") {
    override fun actionPerformed(event: AnActionEvent) {
      closeCurrentStoreIfKeePass()

      // even if current provider is not KEEPASS, all actions for db file must be applied immediately (show error if new master password not applicable for existing db file)
      if (createKeePassFileManager()?.askAndSetMasterKey(event) == true) {
        templatePresentation.text = "Change Master Password"
      }
    }

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = getNewDbFileAsString() != null
    }
  }
}

// we must save and close opened KeePass database before any action that can modify KeePass database files
private fun closeCurrentStoreIfKeePass() {
  (PasswordSafe.instance as PasswordSafeImpl).closeCurrentStoreIfKeePass()
}